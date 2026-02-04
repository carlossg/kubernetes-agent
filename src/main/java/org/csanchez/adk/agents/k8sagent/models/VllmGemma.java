package org.csanchez.adk.agents.k8sagent.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.StreamSupport.stream;

/**
 * Custom LLM implementation for vLLM-hosted Gemma models with native OpenAI function calling.
 * 
 * <p>This class wraps vLLM's OpenAI-compatible API to integrate Gemma models
 * with Google's Agent Development Kit (ADK) using native tool calling support.
 * 
 * <p>Requires vLLM server to be started with:
 * --enable-auto-tool-choice --tool-call-parser pythonic
 */
public class VllmGemma extends BaseLlm {

	private static final Logger logger = LoggerFactory.getLogger(VllmGemma.class);
	private static final ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new Jdk8Module());
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	
	// Maximum characters per tool response to avoid exceeding Gemma's 8K context limit
	private static final int MAX_TOOL_RESPONSE_LENGTH = 1500;

	private final String apiBaseUrl;
	private final OkHttpClient httpClient;
	private final String apiKey; // Optional, for secured endpoints

	/**
	 * Constructs a new VllmGemma instance.
	 *
	 * @param modelName The name of the Gemma model (e.g., "gemma-3-1b-it")
	 * @param apiBaseUrl The base URL of the vLLM server (e.g., "http://gemma-server.gemma-system.svc.cluster.local:8000")
	 * @param apiKey Optional API key for authentication
	 */
	public VllmGemma(String modelName, String apiBaseUrl, String apiKey) {
		super(modelName);
		this.apiBaseUrl = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl cannot be null");
		this.apiKey = apiKey;
		this.httpClient = new OkHttpClient.Builder()
				.connectTimeout(30, TimeUnit.SECONDS)
				.readTimeout(120, TimeUnit.SECONDS)
				.writeTimeout(30, TimeUnit.SECONDS)
				.build();
		logger.info("Initialized VllmGemma with model: {}, apiBaseUrl: {}", modelName, apiBaseUrl);
	}

	/**
	 * Returns a builder for constructing VllmGemma instances.
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
		// Set MDC context from model name for structured logging
		MDC.put("model", model());
		
		try {
			// Convert ADK LlmRequest to OpenAI-compatible format
			ObjectNode requestBody = buildOpenAiRequest(llmRequest, stream);
			
			logger.debug("Sending request to vLLM: {}", requestBody.toPrettyString());

			// Build HTTP request
			Request.Builder requestBuilder = new Request.Builder()
					.url(apiBaseUrl + "/v1/chat/completions")
					.post(RequestBody.create(requestBody.toString(), JSON));

			if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("not-needed")) {
				requestBuilder.header("Authorization", "Bearer " + apiKey);
			}

			Request request = requestBuilder.build();

			if (stream) {
				// Streaming not implemented yet, fall back to non-streaming
				logger.warn("Streaming not yet implemented for VllmGemma, using non-streaming mode");
				return generateNonStreaming(request);
			} else {
				return generateNonStreaming(request);
			}

		} catch (Exception e) {
			logger.error("Error generating content from vLLM", e);
			return Flowable.error(e);
		} finally {
			MDC.remove("model");
		}
	}

	private Flowable<LlmResponse> generateNonStreaming(Request request) {
		return Flowable.create(emitter -> {
			// Set MDC for this execution context
			MDC.put("model", model());
			
			try {
				Response response = httpClient.newCall(request).execute();
				
				if (!response.isSuccessful()) {
					String errorBody = response.body() != null ? response.body().string() : "No error details";
					
					// Enhanced error logging for tool calling/conversation failures
					boolean isToolRelatedError = response.code() == 400 && (
						errorBody.contains("tool") || 
						errorBody.contains("function") ||
						errorBody.contains("Conversation roles") ||
						errorBody.contains("alternate") ||
						errorBody.contains("Grammar error") ||
						errorBody.contains("JSON") ||
						errorBody.contains("Invalid type")
					);
					
					if (isToolRelatedError) {
						logger.error("Tool calling/conversation failure detected!");
						logger.error("HTTP Status: {}", response.code());
						logger.error("Error from vLLM: {}", errorBody);
						logger.error("Full request details are logged at DEBUG level (search for 'Sending request to vLLM')");
						logger.error("What happened:");
						if (errorBody.contains("Conversation roles")) {
							logger.error("- Conversation roles are not alternating correctly");
							logger.error("- This usually happens when tool responses are malformed");
							logger.error("- Check for 'Failed to serialize function response' warnings above");
						} else if (errorBody.contains("Grammar error") || errorBody.contains("Invalid type")) {
							logger.error("- Schema type validation failed (likely uppercase types not converted)");
						} else if (errorBody.contains("JSON")) {
							logger.error("- Invalid JSON in tool calls or responses");
						} else {
							logger.error("- Tool calling format issue detected");
						}
						logger.error("Recommendation: Use a larger model (Gemma 2 9B/27B) or reduce tool complexity");
					} else {
						logger.error("vLLM API error code={} body={}", response.code(), errorBody);
						if (response.code() == 400) {
							logger.error("Full request details available at DEBUG level");
						}
					}
					
					emitter.onError(new IOException("vLLM API error: " + response.code() + " - " + errorBody));
					return;
				}

				String responseBody = response.body().string();
				logger.debug("Received response from vLLM: {}", responseBody);

				// Parse OpenAI-compatible response
				JsonNode jsonResponse = objectMapper.readTree(responseBody);
				LlmResponse llmResponse = parseOpenAiResponse(jsonResponse);
				
				emitter.onNext(llmResponse);
				emitter.onComplete();

			} catch (Exception e) {
				logger.error("Error processing vLLM response", e);
				emitter.onError(e);
			} finally {
				MDC.remove("model");
			}
		}, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
	}

	/**
	 * Converts ADK LlmRequest to OpenAI-compatible JSON format with native function calling.
	 * 
	 * IMPORTANT: vLLM with Gemma models does NOT support the "tool" role message format.
	 * Instead, tool responses must be formatted as a "user" message containing the results.
	 * This is because Gemma's chat template expects strict user/assistant alternation.
	 */
	private ObjectNode buildOpenAiRequest(LlmRequest llmRequest, boolean stream) {
		ObjectNode requestBody = objectMapper.createObjectNode();
		requestBody.put("model", llmRequest.model().orElse(model()));
		requestBody.put("stream", stream);

		// Convert ADK Contents to OpenAI messages format
		ArrayNode messages = objectMapper.createArrayNode();
		
		List<Content> contents = llmRequest.contents();

		// Check if there are any tool messages in the conversation
		boolean hasToolMessages = contents.stream()
			.filter(c -> c.parts().isPresent())
			.flatMap(c -> c.parts().get().stream())
			.anyMatch(p -> p.functionResponse().isPresent());

		// Prepend System Instructions if present
		List<String> systemInstructions = llmRequest.getSystemInstructions();
		if (systemInstructions != null && !systemInstructions.isEmpty()) {
			ObjectNode systemMsg = objectMapper.createObjectNode();
			systemMsg.put("role", "system");
			systemMsg.put("content", String.join("\n", systemInstructions));
			messages.add(systemMsg);
		}
		
		// Track the last message role to ensure proper alternation
		String lastRole = systemInstructions != null && !systemInstructions.isEmpty() ? "system" : null;
		
		// Collect all function responses to format them as a single user message
		// This is required because vLLM/Gemma doesn't support the "tool" role
		List<String> pendingToolResponses = new ArrayList<>();
		
		for (Content content : contents) {
			// Map role
			String role = content.role().orElse("user");
			if (role.equals("model")) {
				role = "assistant";
			}

			// Extract text, tool calls, and function responses from parts
			StringBuilder messageContent = new StringBuilder();
			ArrayNode toolCalls = null;
			List<Part> functionResponseParts = new ArrayList<>();
			
			if (content.parts().isPresent()) {
				for (Part part : content.parts().get()) {
					if (part.text().isPresent()) {
						messageContent.append(part.text().get());
					}
					// Handle function call parts (from previous tool calls)
					if (part.functionCall().isPresent()) {
						var functionCall = part.functionCall().get();
						if (toolCalls == null) {
							toolCalls = objectMapper.createArrayNode();
						}
						String functionName = functionCall.name().orElse("unknown");
						// Use the actual tool call ID from vLLM if available, otherwise generate one
						String toolCallId = functionCall.id().orElse("call_" + functionName);
						ObjectNode toolCall = objectMapper.createObjectNode();
						toolCall.put("id", toolCallId);
						toolCall.put("type", "function");
						ObjectNode function = objectMapper.createObjectNode();
						function.put("name", functionName);
						try {
							function.put("arguments", objectMapper.writeValueAsString(functionCall.args()));
						} catch (Exception e) {
							logger.warn("Failed to serialize function args", e);
							function.put("arguments", "{}");
						}
						toolCall.set("function", function);
						toolCalls.add(toolCall);
					}
					// Collect function response parts to format as user message
					if (part.functionResponse().isPresent()) {
						functionResponseParts.add(part);
					}
				}
			}
			
			// If this Content has function responses, collect them for a user message
			// vLLM/Gemma doesn't support the "tool" role, so we format tool responses
			// as a user message instead
			if (!functionResponseParts.isEmpty()) {
				for (Part part : functionResponseParts) {
					var functionResponse = part.functionResponse().get();
					String responseName = functionResponse.name().orElse("unknown");
					try {
						String responseJson = objectMapper.writeValueAsString(functionResponse.response());
						// Truncate large responses to avoid exceeding Gemma's context limit
						String truncatedResponse = truncateToolResponse(responseJson, responseName);
						pendingToolResponses.add(String.format("Tool '%s' returned: %s", responseName, truncatedResponse));
					} catch (Exception e) {
						logger.warn("Failed to serialize function response for {}: {}", responseName, e.getMessage());
						pendingToolResponses.add(String.format("Tool '%s' returned: {}", responseName));
					}
				}
				// Skip adding a tool message - we'll add a user message with all responses later
			} else {
				// Before adding a user/assistant message, flush any pending tool responses
				if (!pendingToolResponses.isEmpty() && (role.equals("user") || role.equals("assistant"))) {
					// Add a user message with all collected tool responses
					ObjectNode toolResultsMessage = objectMapper.createObjectNode();
					toolResultsMessage.put("role", "user");
					StringBuilder toolResultsContent = new StringBuilder();
					toolResultsContent.append("Here are the results from the tools you called:\n\n");
					for (String response : pendingToolResponses) {
						toolResultsContent.append(response).append("\n\n");
					}
					toolResultsContent.append("Based on these results, please provide your analysis in the required JSON format.");
					toolResultsMessage.put("content", toolResultsContent.toString());
					messages.add(toolResultsMessage);
					lastRole = "user";
					pendingToolResponses.clear();
					logger.debug("Added user message with {} tool responses", pendingToolResponses.size());
				}
				
				// Add the main message (user/assistant) if it has content or tool calls
				if (messageContent.length() > 0 || toolCalls != null) {
					// Skip user message if we just added a tool results user message
					if (role.equals("user") && "user".equals(lastRole)) {
						logger.debug("Skipping consecutive user message to maintain alternation");
						continue;
					}
					
					ObjectNode message = objectMapper.createObjectNode();
					message.put("role", role);
					
					// For assistant messages with tool_calls, we need special handling
					// Since we're converting tool responses to user messages, we should
					// represent the assistant's tool calls as a description of what it did
					if (role.equals("assistant") && toolCalls != null && toolCalls.size() > 0) {
						// Convert tool calls to a text description instead of tool_calls format
						// This is needed because vLLM/Gemma doesn't support tool role messages
						StringBuilder assistantText = new StringBuilder();
						if (messageContent.length() > 0) {
							assistantText.append(messageContent.toString()).append("\n\n");
						}
						assistantText.append("I'll use the following tools to analyze: ");
						List<String> toolNames = new ArrayList<>();
						for (int i = 0; i < toolCalls.size(); i++) {
							JsonNode tc = toolCalls.get(i);
							String name = tc.get("function").get("name").asText();
							if (!toolNames.contains(name)) {
								toolNames.add(name);
							}
						}
						assistantText.append(String.join(", ", toolNames));
						message.put("content", assistantText.toString());
						// Do NOT add tool_calls since we're using user messages for responses
						logger.debug("Converted {} tool calls to assistant text for vLLM compatibility", toolCalls.size());
					} else if (messageContent.length() > 0) {
						message.put("content", messageContent.toString());
					} else {
						message.put("content", "");
					}
					
					messages.add(message);
					lastRole = role;
				}
			}
		}
		
		// Flush any remaining pending tool responses at the end
		if (!pendingToolResponses.isEmpty()) {
			ObjectNode toolResultsMessage = objectMapper.createObjectNode();
			toolResultsMessage.put("role", "user");
			StringBuilder toolResultsContent = new StringBuilder();
			toolResultsContent.append("Here are the results from the tools you called:\n\n");
			for (String response : pendingToolResponses) {
				toolResultsContent.append(response).append("\n\n");
			}
			toolResultsContent.append("Based on these results, please provide your analysis in the required JSON format.");
			toolResultsMessage.put("content", toolResultsContent.toString());
			messages.add(toolResultsMessage);
			logger.info("Added final user message with {} tool responses for vLLM/Gemma compatibility", pendingToolResponses.size());
		}
		
		requestBody.set("messages", messages);

		// Convert tools to OpenAI format if present
		if (llmRequest.tools() != null && !llmRequest.tools().isEmpty()) {
			ArrayNode toolsArray = objectMapper.createArrayNode();
			for (BaseTool tool : llmRequest.tools().values()) {
				if (tool.declaration().isPresent()) {
					FunctionDeclaration fd = tool.declaration().get();
					ObjectNode toolObj = objectMapper.createObjectNode();
					toolObj.put("type", "function");
					ObjectNode function = objectMapper.createObjectNode();
					function.put("name", fd.name().orElse(tool.name()));
					function.put("description", fd.description().orElse(tool.description()));
					
					// Convert Schema to JSON Schema format
					if (fd.parameters().isPresent()) {
						try {
							String schemaJson = fd.parameters().get().toJson();
							JsonNode schemaNode = objectMapper.readTree(schemaJson);
							
							// Fix uppercase types (STRING -> string, OBJECT -> object, etc.)
							lowercaseSchemaTypes(schemaNode);
							
							function.set("parameters", schemaNode);
						} catch (Exception e) {
							logger.warn("Failed to convert schema to JSON for tool {}: {}", 
								fd.name().orElse(tool.name()), e.getMessage());
							// Fallback to empty object schema
							ObjectNode emptySchema = objectMapper.createObjectNode();
							emptySchema.put("type", "object");
							emptySchema.set("properties", objectMapper.createObjectNode());
							function.set("parameters", emptySchema);
						}
					} else {
						// No parameters - use empty object schema
						ObjectNode emptySchema = objectMapper.createObjectNode();
						emptySchema.put("type", "object");
						emptySchema.set("properties", objectMapper.createObjectNode());
						function.set("parameters", emptySchema);
					}
					
					toolObj.set("function", function);
					toolsArray.add(toolObj);
				}
			}
			
			if (toolsArray.size() > 0) {
				requestBody.set("tools", toolsArray);
				// Only use "required" on first turn (when there are no tool messages in history)
				// On subsequent turns use "auto" to allow the model to decide whether to call more tools or respond
				if (hasToolMessages) {
					requestBody.put("tool_choice", "auto");
					logger.info("Added {} tools to OpenAI request with tool_choice=auto (subsequent turn)", toolsArray.size());
				} else {
					requestBody.put("tool_choice", "required");
					logger.info("Added {} tools to OpenAI request with tool_choice=required (first turn)", toolsArray.size());
				}
			}
		}

		// Add generation config if present
		if (llmRequest.config().isPresent()) {
			var config = llmRequest.config().get();
			
			config.maxOutputTokens().ifPresent(max -> requestBody.put("max_tokens", max));
			config.temperature().ifPresent(temp -> requestBody.put("temperature", temp));
			config.topP().ifPresent(p -> requestBody.put("top_p", p));
			config.topK().ifPresent(k -> requestBody.put("top_k", k));
			config.stopSequences().ifPresent(stops -> {
				ArrayNode stopArray = objectMapper.createArrayNode();
				stops.forEach(stopArray::add);
				requestBody.set("stop", stopArray);
			});
		}

		return requestBody;
	}

	/**
	 * Parses OpenAI-compatible response into ADK LlmResponse.
	 * Handles both text responses and native tool_calls from OpenAI format.
	 * 
	 * IMPORTANT: Gemma 1B often generates duplicate tool calls. This method
	 * deduplicates tool calls based on function name + arguments to avoid
	 * calling the same tool multiple times with identical parameters.
	 */
	private LlmResponse parseOpenAiResponse(JsonNode jsonResponse) {
		try {
			JsonNode choices = jsonResponse.get("choices");
			if (choices == null || !choices.isArray() || choices.size() == 0) {
				throw new IOException("Invalid response format: no choices");
			}

			JsonNode firstChoice = choices.get(0);
			JsonNode message = firstChoice.get("message");
			
			// Check for native OpenAI tool_calls format first
			if (message.has("tool_calls") && message.get("tool_calls").isArray() 
					&& message.get("tool_calls").size() > 0) {
				
				List<Part> parts = new ArrayList<>();
				JsonNode toolCallsArray = message.get("tool_calls");
				
				// Track unique tool calls to deduplicate (Gemma often generates duplicates)
				java.util.Set<String> seenToolCalls = new java.util.HashSet<>();
				int duplicateCount = 0;
				
				for (JsonNode toolCallNode : toolCallsArray) {
					String toolCallId = toolCallNode.get("id").asText(); // Preserve vLLM's tool call ID
					String toolName = toolCallNode.get("function").get("name").asText();
					String argumentsJson = toolCallNode.get("function").get("arguments").asText();
					
					// Create a unique key for deduplication
					String dedupeKey = toolName + "|" + argumentsJson;
					if (seenToolCalls.contains(dedupeKey)) {
						duplicateCount++;
						logger.debug("Skipping duplicate tool call toolName={} args={}", toolName, argumentsJson);
						continue;
					}
					seenToolCalls.add(dedupeKey);
					
					try {
						Map<String, Object> args = objectMapper.readValue(argumentsJson, 
							objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
						
						logger.info("Preparing to call tool toolName={} args={}", toolName, args);
						// Use FunctionCall.builder() to preserve the tool call ID from vLLM
						parts.add(Part.builder()
							.functionCall(FunctionCall.builder()
								.id(toolCallId)
								.name(toolName)
								.args(args)
								.build())
							.build());
						
					} catch (Exception e) {
						logger.error("Failed to parse tool call arguments argumentsJson={}", argumentsJson, e);
					}
				}
				
				if (duplicateCount > 0) {
					logger.info("Deduplicated {} duplicate tool calls (kept {} unique)", 
						duplicateCount, parts.size());
				}
				
				if (!parts.isEmpty()) {
					return LlmResponse.builder()
						.content(Content.builder()
							.role("model")
							.parts(parts)
							.build())
						.build();
				}
			}
			
			// If no tool calls, process as text response
			JsonNode contentNode = message.get("content");
			if (contentNode == null || contentNode.isNull()) {
				// Empty content, might be error case
				logger.warn("Received null/empty content (no tool calls, no text)");
				return LlmResponse.builder()
					.content(Content.builder()
						.role("model")
						.parts(com.google.common.collect.ImmutableList.of(Part.fromText("")))
						.build())
					.build();
			}
			
			String contentText = contentNode.asText();
			if (contentText != null && !contentText.isEmpty()) {
				logger.info("Returned text response (no tool calls) length={}", contentText.length());
			}

			// Build ADK response using proper API
			Content responseContent = Content.builder()
					.role("model")
					.parts(com.google.common.collect.ImmutableList.of(Part.fromText(contentText)))
					.build();

			return LlmResponse.builder()
					.content(responseContent)
					.build();

		} catch (Exception e) {
			logger.error("Error parsing OpenAI response", e);
			throw new RuntimeException("Error parsing response", e);
		}
	}
	
	/**
	 * Recursively lowercase all "type" values in a JSON schema.
	 * ADK Schema.toJson() returns uppercase types (STRING, OBJECT, etc.) 
	 * but OpenAI expects lowercase (string, object, etc.)
	 */
	private void lowercaseSchemaTypes(JsonNode node) {
		if (node == null) {
			return;
		}
		
		if (node.isObject()) {
			ObjectNode objNode = (ObjectNode) node;
			// Check if this node has a "type" field
			if (objNode.has("type")) {
				JsonNode typeNode = objNode.get("type");
				if (typeNode.isTextual()) {
					String typeValue = typeNode.asText();
					objNode.put("type", typeValue.toLowerCase());
				}
			}
			// Recursively process all fields
			objNode.fields().forEachRemaining(entry -> {
				lowercaseSchemaTypes(entry.getValue());
			});
		} else if (node.isArray()) {
			node.forEach(this::lowercaseSchemaTypes);
		}
	}

	/**
	 * Truncates tool responses to avoid exceeding Gemma's context limit.
	 * Intelligently summarizes JSON responses by keeping key fields and truncating arrays.
	 */
	private String truncateToolResponse(String responseJson, String toolName) {
		if (responseJson == null || responseJson.length() <= MAX_TOOL_RESPONSE_LENGTH) {
			return responseJson;
		}
		
		try {
			JsonNode node = objectMapper.readTree(responseJson);
			
			// For events/pods responses, limit the number of items in arrays
			if (node.isObject()) {
				ObjectNode objNode = (ObjectNode) node;
				
				// Handle 'events' array - keep only first 5 events
				if (objNode.has("events") && objNode.get("events").isArray()) {
					ArrayNode events = (ArrayNode) objNode.get("events");
					if (events.size() > 5) {
						ArrayNode truncatedEvents = objectMapper.createArrayNode();
						for (int i = 0; i < 5; i++) {
							truncatedEvents.add(events.get(i));
						}
						objNode.set("events", truncatedEvents);
						objNode.put("eventCount", events.size());
						objNode.put("_truncated", true);
						objNode.put("_message", "Showing first 5 of " + events.size() + " events");
					}
				}
				
				// Handle 'pods' array - keep only first 10 pods
				if (objNode.has("pods") && objNode.get("pods").isArray()) {
					ArrayNode pods = (ArrayNode) objNode.get("pods");
					if (pods.size() > 10) {
						ArrayNode truncatedPods = objectMapper.createArrayNode();
						for (int i = 0; i < 10; i++) {
							truncatedPods.add(pods.get(i));
						}
						objNode.set("pods", truncatedPods);
						objNode.put("_truncated", true);
						objNode.put("_message", "Showing first 10 of " + pods.size() + " pods");
					}
				}
				
				// Handle 'logs' field - truncate long log strings
				if (objNode.has("logs") && objNode.get("logs").isTextual()) {
					String logs = objNode.get("logs").asText();
					if (logs.length() > 500) {
						objNode.put("logs", logs.substring(0, 500) + "... [truncated, " + logs.length() + " chars total]");
					}
				}
				
				String result = objectMapper.writeValueAsString(objNode);
				if (result.length() <= MAX_TOOL_RESPONSE_LENGTH) {
					logger.debug("Truncated {} response from {} to {} chars", toolName, responseJson.length(), result.length());
					return result;
				}
			}
			
			// If still too long after smart truncation, do simple truncation
			logger.debug("Simple truncation for {} response: {} -> {} chars", toolName, responseJson.length(), MAX_TOOL_RESPONSE_LENGTH);
			return responseJson.substring(0, MAX_TOOL_RESPONSE_LENGTH) + "... [truncated]";
			
		} catch (Exception e) {
			logger.warn("Failed to truncate tool response for {}, using simple truncation", toolName);
			return responseJson.substring(0, Math.min(responseJson.length(), MAX_TOOL_RESPONSE_LENGTH)) + "... [truncated]";
		}
	}

	@Override
	public BaseLlmConnection connect(LlmRequest llmRequest) {
		throw new UnsupportedOperationException("Live connections not supported for vLLM Gemma");
	}

	/** Builder for {@link VllmGemma}. */
	public static class Builder {
		private String modelName;
		private String apiBaseUrl;
		private String apiKey;

		private Builder() {}

		public Builder modelName(String modelName) {
			this.modelName = modelName;
			return this;
		}

		public Builder apiBaseUrl(String apiBaseUrl) {
			this.apiBaseUrl = apiBaseUrl;
			return this;
		}

		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		public VllmGemma build() {
			Objects.requireNonNull(modelName, "modelName must be set");
			Objects.requireNonNull(apiBaseUrl, "apiBaseUrl must be set");
			return new VllmGemma(modelName, apiBaseUrl, apiKey);
		}
	}
}
