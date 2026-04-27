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

/**
 * Generic OpenAI-compatible LLM client.
 *
 * <p>Wraps any OpenAI-compatible {@code /v1/chat/completions} endpoint — works
 * with hosted services that speak the OpenAI ChatCompletions wire format
 * (LiteLLM proxy, OpenRouter, vLLM, Anthropic-compatible gateways) without the
 * Gemma-specific message-rewriting found in {@link VllmGemma}.
 *
 * <p>Key differences from {@link VllmGemma}:
 * <ul>
 *   <li>Preserves the {@code tool} message role and assistant {@code tool_calls}
 *       arrays — required for models with native tool calling (Claude, GPT-4,
 *       Llama 3.1+).</li>
 *   <li>Does not truncate tool responses — use the model's full context window.</li>
 *   <li>Does not deduplicate tool calls in responses.</li>
 *   <li>Does not collapse consecutive same-role messages.</li>
 * </ul>
 *
 * <p>Use this class for Claude (via Bedrock, Anthropic-compatible LiteLLM proxy,
 * etc.), GPT, and any other model with native OpenAI-style tool calling. Use
 * {@link VllmGemma} for vLLM-hosted Gemma which requires user-role rewriting.
 */
public class OpenAiCompatible extends BaseLlm {

	private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatible.class);
	private static final ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new Jdk8Module());
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final String apiBaseUrl;
	private final OkHttpClient httpClient;
	private final String apiKey;

	/**
	 * @param modelName  Model identifier sent in the request body (e.g. {@code claude-haiku-4-5}).
	 * @param apiBaseUrl Base URL of the OpenAI-compatible endpoint, WITHOUT a trailing
	 *                   {@code /v1} (e.g. {@code http://litellm-proxy.svc:5567}). The
	 *                   {@code /v1/chat/completions} suffix is appended automatically.
	 * @param apiKey     Optional bearer token. {@code null} or {@code "not-needed"} disables
	 *                   the {@code Authorization} header.
	 */
	public OpenAiCompatible(String modelName, String apiBaseUrl, String apiKey) {
		super(modelName);
		this.apiBaseUrl = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl cannot be null");
		this.apiKey = apiKey;
		this.httpClient = new OkHttpClient.Builder()
				.connectTimeout(30, TimeUnit.SECONDS)
				.readTimeout(120, TimeUnit.SECONDS)
				.writeTimeout(30, TimeUnit.SECONDS)
				.build();
		logger.info("Initialized OpenAiCompatible with model: {}, apiBaseUrl: {}", modelName, apiBaseUrl);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
		MDC.put("model", model());
		try {
			ObjectNode requestBody = buildOpenAiRequest(llmRequest, stream);
			logger.debug("Sending request to OpenAI-compatible endpoint: {}", requestBody.toPrettyString());

			Request.Builder requestBuilder = new Request.Builder()
					.url(apiBaseUrl + "/v1/chat/completions")
					.post(RequestBody.create(requestBody.toString(), JSON));

			if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("not-needed")) {
				requestBuilder.header("Authorization", "Bearer " + apiKey);
			}

			if (stream) {
				logger.warn("Streaming not yet implemented for OpenAiCompatible, using non-streaming mode");
			}
			return generateNonStreaming(requestBuilder.build());

		} catch (Exception e) {
			logger.error("Error generating content from OpenAI-compatible endpoint", e);
			return Flowable.error(e);
		} finally {
			MDC.remove("model");
		}
	}

	private Flowable<LlmResponse> generateNonStreaming(Request request) {
		return Flowable.create(emitter -> {
			MDC.put("model", model());
			try {
				Response response = httpClient.newCall(request).execute();
				if (!response.isSuccessful()) {
					String errorBody = response.body() != null ? response.body().string() : "No error details";
					logger.error("OpenAI-compatible API error code={} body={}", response.code(), errorBody);
					emitter.onError(new IOException(
							"OpenAI-compatible API error: " + response.code() + " - " + errorBody));
					return;
				}

				String responseBody = response.body().string();
				logger.debug("Received response: {}", responseBody);

				JsonNode jsonResponse = objectMapper.readTree(responseBody);
				LlmResponse llmResponse = parseOpenAiResponse(jsonResponse);

				emitter.onNext(llmResponse);
				emitter.onComplete();

			} catch (Exception e) {
				logger.error("Error processing OpenAI-compatible response", e);
				emitter.onError(e);
			} finally {
				MDC.remove("model");
			}
		}, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
	}

	/**
	 * Converts ADK {@link LlmRequest} to a standard OpenAI ChatCompletions request body.
	 * Preserves tool/assistant roles and {@code tool_calls} arrays — no Gemma-style rewriting.
	 */
	private ObjectNode buildOpenAiRequest(LlmRequest llmRequest, boolean stream) {
		ObjectNode requestBody = objectMapper.createObjectNode();
		requestBody.put("model", llmRequest.model().orElse(model()));
		requestBody.put("stream", stream);

		ArrayNode messages = objectMapper.createArrayNode();
		List<Content> contents = llmRequest.contents();

		boolean hasToolMessages = contents.stream()
				.filter(c -> c.parts().isPresent())
				.flatMap(c -> c.parts().get().stream())
				.anyMatch(p -> p.functionResponse().isPresent());

		List<String> systemInstructions = llmRequest.getSystemInstructions();
		if (systemInstructions != null && !systemInstructions.isEmpty()) {
			ObjectNode systemMsg = objectMapper.createObjectNode();
			systemMsg.put("role", "system");
			systemMsg.put("content", String.join("\n", systemInstructions));
			messages.add(systemMsg);
		}

		for (Content content : contents) {
			String role = content.role().orElse("user");
			if (role.equals("model")) {
				role = "assistant";
			}

			StringBuilder messageContent = new StringBuilder();
			ArrayNode toolCalls = null;
			List<Part> functionResponseParts = new ArrayList<>();

			if (content.parts().isPresent()) {
				for (Part part : content.parts().get()) {
					if (part.text().isPresent()) {
						messageContent.append(part.text().get());
					}
					if (part.functionCall().isPresent()) {
						var functionCall = part.functionCall().get();
						if (toolCalls == null) {
							toolCalls = objectMapper.createArrayNode();
						}
						String functionName = functionCall.name().orElse("unknown");
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
					if (part.functionResponse().isPresent()) {
						functionResponseParts.add(part);
					}
				}
			}

			// Tool responses: emit one OpenAI "tool" role message per response, preserving
			// the originating tool_call_id so the model can correlate.
			if (!functionResponseParts.isEmpty()) {
				for (Part part : functionResponseParts) {
					var functionResponse = part.functionResponse().get();
					String responseName = functionResponse.name().orElse("unknown");
					String toolCallId = functionResponse.id().orElse("call_" + responseName);
					String responseJson;
					try {
						responseJson = objectMapper.writeValueAsString(functionResponse.response());
					} catch (Exception e) {
						logger.warn("Failed to serialize function response for {}: {}", responseName, e.getMessage());
						responseJson = "{}";
					}
					ObjectNode toolMessage = objectMapper.createObjectNode();
					toolMessage.put("role", "tool");
					toolMessage.put("tool_call_id", toolCallId);
					toolMessage.put("name", responseName);
					toolMessage.put("content", responseJson);
					messages.add(toolMessage);
				}
				continue;
			}

			// Regular user/assistant message.
			if (messageContent.length() > 0 || toolCalls != null) {
				ObjectNode message = objectMapper.createObjectNode();
				message.put("role", role);
				if (messageContent.length() > 0) {
					message.put("content", messageContent.toString());
				} else {
					// Assistant turns that are pure tool_calls have no content; OpenAI accepts null.
					message.putNull("content");
				}
				if (role.equals("assistant") && toolCalls != null && toolCalls.size() > 0) {
					message.set("tool_calls", toolCalls);
				}
				messages.add(message);
			}
		}

		requestBody.set("messages", messages);

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

					if (fd.parameters().isPresent()) {
						try {
							String schemaJson = fd.parameters().get().toJson();
							JsonNode schemaNode = objectMapper.readTree(schemaJson);
							lowercaseSchemaTypes(schemaNode);
							function.set("parameters", schemaNode);
						} catch (Exception e) {
							logger.warn("Failed to convert schema to JSON for tool {}: {}",
									fd.name().orElse(tool.name()), e.getMessage());
							ObjectNode emptySchema = objectMapper.createObjectNode();
							emptySchema.put("type", "object");
							emptySchema.set("properties", objectMapper.createObjectNode());
							function.set("parameters", emptySchema);
						}
					} else {
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
				if (hasToolMessages) {
					requestBody.put("tool_choice", "auto");
				} else {
					requestBody.put("tool_choice", "required");
				}
			}
		}

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
	 * Parses an OpenAI ChatCompletions response into an ADK {@link LlmResponse}.
	 * Handles both {@code tool_calls} and plain text content. No deduplication —
	 * native-tool-calling models (Claude, GPT) do not emit duplicates.
	 */
	private LlmResponse parseOpenAiResponse(JsonNode jsonResponse) {
		try {
			JsonNode choices = jsonResponse.get("choices");
			if (choices == null || !choices.isArray() || choices.isEmpty()) {
				throw new IOException("Invalid response format: no choices");
			}

			JsonNode message = choices.get(0).get("message");

			if (message.has("tool_calls") && message.get("tool_calls").isArray()
					&& message.get("tool_calls").size() > 0) {
				List<Part> parts = new ArrayList<>();
				for (JsonNode toolCallNode : message.get("tool_calls")) {
					String toolCallId = toolCallNode.get("id").asText();
					String toolName = toolCallNode.get("function").get("name").asText();
					String argumentsJson = toolCallNode.get("function").get("arguments").asText();
					try {
						Map<String, Object> args = objectMapper.readValue(argumentsJson,
								objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
						logger.info("Preparing to call tool toolName={} args={}", toolName, args);
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
				if (!parts.isEmpty()) {
					return LlmResponse.builder()
							.content(Content.builder()
									.role("model")
									.parts(parts)
									.build())
							.build();
				}
			}

			JsonNode contentNode = message.get("content");
			String contentText = (contentNode == null || contentNode.isNull()) ? "" : contentNode.asText();
			if (!contentText.isEmpty()) {
				logger.info("Returned text response (no tool calls) length={}", contentText.length());
			}

			Content responseContent = Content.builder()
					.role("model")
					.parts(com.google.common.collect.ImmutableList.of(Part.fromText(contentText)))
					.build();

			return LlmResponse.builder()
					.content(responseContent)
					.build();

		} catch (Exception e) {
			logger.error("Error parsing OpenAI-compatible response", e);
			throw new RuntimeException("Error parsing response", e);
		}
	}

	/**
	 * ADK's {@code Schema.toJson()} emits uppercase JSON-Schema types ({@code STRING},
	 * {@code OBJECT}); OpenAI expects lowercase. Recursively lowercases every {@code type}
	 * value in place.
	 */
	private void lowercaseSchemaTypes(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			ObjectNode objNode = (ObjectNode) node;
			if (objNode.has("type")) {
				JsonNode typeNode = objNode.get("type");
				if (typeNode.isTextual()) {
					objNode.put("type", typeNode.asText().toLowerCase());
				}
			}
			objNode.fields().forEachRemaining(entry -> lowercaseSchemaTypes(entry.getValue()));
		} else if (node.isArray()) {
			node.forEach(this::lowercaseSchemaTypes);
		}
	}

	@Override
	public BaseLlmConnection connect(LlmRequest llmRequest) {
		throw new UnsupportedOperationException("Live connections not supported for OpenAiCompatible");
	}

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

		public OpenAiCompatible build() {
			Objects.requireNonNull(modelName, "modelName must be set");
			Objects.requireNonNull(apiBaseUrl, "apiBaseUrl must be set");
			return new OpenAiCompatible(modelName, apiBaseUrl, apiKey);
		}
	}
}
