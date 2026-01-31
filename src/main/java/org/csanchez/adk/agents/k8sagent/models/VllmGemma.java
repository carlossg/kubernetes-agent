package org.csanchez.adk.agents.k8sagent.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

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
		}
	}

	private Flowable<LlmResponse> generateNonStreaming(Request request) {
		return Flowable.create(emitter -> {
			try {
				Response response = httpClient.newCall(request).execute();
				
				if (!response.isSuccessful()) {
					String errorBody = response.body() != null ? response.body().string() : "No error details";
					logger.error("vLLM API error: {} - {}", response.code(), errorBody);
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
			}
		}, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
	}

	/**
	 * Converts ADK LlmRequest to OpenAI-compatible JSON format with native function calling.
	 */
	private ObjectNode buildOpenAiRequest(LlmRequest llmRequest, boolean stream) {
		ObjectNode requestBody = objectMapper.createObjectNode();
		requestBody.put("model", llmRequest.model().orElse(model()));
		requestBody.put("stream", stream);

		// Convert ADK Contents to OpenAI messages format
		ArrayNode messages = objectMapper.createArrayNode();
		List<Content> contents = llmRequest.contents();
		
		for (Content content : contents) {
			ObjectNode message = objectMapper.createObjectNode();
			
			// Map role
			String role = content.role().orElse("user");
			if (role.equals("model")) {
				role = "assistant";
			}
			message.put("role", role);

			// Extract text and tool calls from parts
			StringBuilder messageContent = new StringBuilder();
			ArrayNode toolCalls = null;
			
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
						ObjectNode toolCall = objectMapper.createObjectNode();
						toolCall.put("id", "call_" + functionName);
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
					// Handle function response parts
					if (part.functionResponse().isPresent()) {
						var functionResponse = part.functionResponse().get();
						// Function responses are added as separate messages with role "tool"
						String responseName = functionResponse.name().orElse("unknown");
						ObjectNode toolMessage = objectMapper.createObjectNode();
						toolMessage.put("role", "tool");
						toolMessage.put("tool_call_id", "call_" + responseName);
						try {
							toolMessage.put("content", objectMapper.writeValueAsString(functionResponse.response()));
						} catch (Exception e) {
							logger.warn("Failed to serialize function response", e);
							toolMessage.put("content", "{}");
						}
						messages.add(toolMessage);
					}
				}
			}
			
			if (messageContent.length() > 0) {
				message.put("content", messageContent.toString());
			} else if (toolCalls != null) {
				// If only tool calls, content should be empty string (not null for OpenAI compatibility)
				message.put("content", "");
			}
			
			if (toolCalls != null && toolCalls.size() > 0) {
				message.set("tool_calls", toolCalls);
			}
			
			messages.add(message);
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
				// Use "required" for Gemma to ensure tool calling (auto doesn't always work reliably)
				requestBody.put("tool_choice", "required");
				logger.info("Added {} tools to OpenAI request with tool_choice=required", toolsArray.size());
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
				
				for (JsonNode toolCallNode : toolCallsArray) {
					String toolName = toolCallNode.get("function").get("name").asText();
					String argumentsJson = toolCallNode.get("function").get("arguments").asText();
					
					try {
						Map<String, Object> args = objectMapper.readValue(argumentsJson, 
							objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
						
						logger.info("Parsed tool call from vLLM native format: {} with args: {}", toolName, args);
						parts.add(Part.fromFunctionCall(toolName, args));
						
					} catch (Exception e) {
						logger.error("Failed to parse tool call arguments: {}", argumentsJson, e);
					}
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
				logger.warn("Received null content from vLLM");
				return LlmResponse.builder()
					.content(Content.builder()
						.role("model")
						.parts(com.google.common.collect.ImmutableList.of(Part.fromText("")))
						.build())
					.build();
			}
			
			String contentText = contentNode.asText();

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
