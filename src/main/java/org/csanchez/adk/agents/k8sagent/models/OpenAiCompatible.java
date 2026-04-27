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
import java.util.UUID;
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
 *   <li>Does not send {@code top_k} (non-standard for OpenAI ChatCompletions).</li>
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

	// Shared across all OpenAiCompatible instances. OkHttp's connection pool, dispatcher,
	// and idle thread pool are all per-client; using one instance reuses connections
	// across calls and avoids socket/thread leaks if multiple instances are constructed.
	// callTimeout caps the total wall-clock duration of a call as a hard upper bound
	// over connect+write+read; without it, a slowly-trickling response could hold a
	// connection for far longer than readTimeout (which is per-socket-read).
	private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
			.callTimeout(180, TimeUnit.SECONDS)
			.connectTimeout(30, TimeUnit.SECONDS)
			.readTimeout(120, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build();

	private final String apiBaseUrl;
	private final String apiKey;

	/**
	 * @param modelName  Model identifier sent in the request body (e.g. {@code claude-haiku-4-5}).
	 * @param apiBaseUrl Base URL of the OpenAI-compatible endpoint, WITHOUT a trailing
	 *                   {@code /v1} (e.g. {@code http://litellm-proxy.svc:5567}). The
	 *                   {@code /v1/chat/completions} suffix is appended automatically.
	 *                   Whitespace is trimmed and a single trailing {@code /} is removed.
	 * @param apiKey     Optional bearer token. {@code null} or {@code "not-needed"} disables
	 *                   the {@code Authorization} header.
	 */
	public OpenAiCompatible(String modelName, String apiBaseUrl, String apiKey) {
		super(modelName);
		Objects.requireNonNull(apiBaseUrl, "apiBaseUrl cannot be null");
		String trimmed = apiBaseUrl.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("apiBaseUrl cannot be blank");
		}
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		if (trimmed.endsWith("/v1")) {
			logger.warn("apiBaseUrl ends with '/v1' — the suffix '/v1/chat/completions' is appended"
					+ " automatically; check OPENAI_COMPATIBLE_API_BASE if requests 404");
		}
		this.apiBaseUrl = trimmed;
		this.apiKey = apiKey;
		if ("not-needed".equals(apiKey)) {
			logger.warn("apiKey == 'not-needed' sentinel; Authorization header will NOT be sent."
					+ " If your endpoint requires auth, set OPENAI_COMPATIBLE_API_KEY to a real bearer token.");
		}
		logger.info("Initialized OpenAiCompatible with model: {}, apiBaseUrl: {}", modelName, this.apiBaseUrl);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
		String previousMdc = MDC.get("model");
		MDC.put("model", model());
		try {
			// Streaming SSE parsing is not implemented — never request stream from upstream
			// regardless of the caller's flag. Forwarding stream=true would yield SSE bytes
			// that the JSON parser can't handle.
			if (stream) {
				logger.warn("Streaming not yet implemented for OpenAiCompatible, using non-streaming mode");
			}
			ObjectNode requestBody = buildOpenAiRequest(llmRequest, false);
			logger.debug("Sending request to OpenAI-compatible endpoint: {}", requestBody.toPrettyString());

			Request.Builder requestBuilder = new Request.Builder()
					.url(apiBaseUrl + "/v1/chat/completions")
					.post(RequestBody.create(requestBody.toString(), JSON));

			if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("not-needed")) {
				requestBuilder.header("Authorization", "Bearer " + apiKey);
			}

			return generateNonStreaming(requestBuilder.build());

		} catch (Exception e) {
			logger.error("Error generating content from OpenAI-compatible endpoint", e);
			return Flowable.error(e);
		} finally {
			restoreMdc(previousMdc);
		}
	}

	private Flowable<LlmResponse> generateNonStreaming(Request request) {
		return Flowable.create(emitter -> {
			Call call = HTTP_CLIENT.newCall(request);
			emitter.setCancellable(call::cancel);

			String previousMdc = MDC.get("model");
			MDC.put("model", model());
			try (Response response = call.execute()) {
				if (emitter.isCancelled()) {
					return;
				}
				ResponseBody body = response.body();
				if (!response.isSuccessful()) {
					String errorBody = body != null ? body.string() : "No error details";
					logger.error("OpenAI-compatible API error code={} body={}", response.code(), errorBody);
					emitter.onError(new IOException(
							"OpenAI-compatible API error: " + response.code() + " - " + errorBody));
					return;
				}
				if (body == null) {
					emitter.onError(new IOException("OpenAI-compatible API error: empty response body"));
					return;
				}

				String responseBody = body.string();
				logger.debug("Received response: {}", responseBody);

				JsonNode jsonResponse = objectMapper.readTree(responseBody);
				LlmResponse llmResponse = parseOpenAiResponse(jsonResponse);

				if (!emitter.isCancelled()) {
					emitter.onNext(llmResponse);
					emitter.onComplete();
				}

			} catch (Exception e) {
				logger.error("Error processing OpenAI-compatible response", e);
				if (!emitter.isCancelled()) {
					emitter.onError(e);
				}
			} finally {
				restoreMdc(previousMdc);
			}
		}, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
	}

	private static void restoreMdc(String previousValue) {
		if (previousValue == null) {
			MDC.remove("model");
		} else {
			MDC.put("model", previousValue);
		}
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
						String toolCallId = functionCall.id().orElseGet(OpenAiCompatible::generateToolCallId);
						ObjectNode toolCall = objectMapper.createObjectNode();
						toolCall.put("id", toolCallId);
						toolCall.put("type", "function");
						ObjectNode function = objectMapper.createObjectNode();
						function.put("name", functionName);
						String argsJson = "{}";
						if (functionCall.args().isPresent()) {
							try {
								argsJson = objectMapper.writeValueAsString(functionCall.args().get());
							} catch (Exception e) {
								logger.warn("Failed to serialize function args", e);
							}
						}
						function.put("arguments", argsJson);
						toolCall.set("function", function);
						toolCalls.add(toolCall);
					}
					if (part.functionResponse().isPresent()) {
						functionResponseParts.add(part);
					}
				}
			}

			// Tool responses: emit one OpenAI "tool" role message per response, preserving
			// the originating tool_call_id so the model can correlate. The "name" field on
			// tool messages is legacy (from the deprecated "function" role) and ignored by
			// modern endpoints, so it is omitted.
			if (!functionResponseParts.isEmpty()) {
				for (Part part : functionResponseParts) {
					var functionResponse = part.functionResponse().get();
					String responseName = functionResponse.name().orElse("unknown");
					String toolCallId = functionResponse.id().orElseGet(() -> {
						String fallback = generateToolCallId();
						logger.warn("functionResponse for '{}' has no id; generated {} as fallback."
								+ " Tool-call correlation may be wrong.", responseName, fallback);
						return fallback;
					});
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
				if (tool.declaration().isEmpty()) {
					logger.warn("Tool {} has no declaration; skipping (model will not see it)", tool.name());
					continue;
				}
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
						function.set("parameters", emptyObjectSchema());
					}
				} else {
					function.set("parameters", emptyObjectSchema());
				}

				toolObj.set("function", function);
				toolsArray.add(toolObj);
			}

			if (toolsArray.size() > 0) {
				requestBody.set("tools", toolsArray);
				// "auto" lets native-tool-calling models (Claude, GPT-4) decide whether the
				// query needs a tool. Forcing "required" first-turn — as VllmGemma does to
				// nudge Gemma into using tools — is unnecessary here and prevents direct
				// answers to simple queries.
				requestBody.put("tool_choice", "auto");
			}
		}

		if (llmRequest.config().isPresent()) {
			var config = llmRequest.config().get();
			config.maxOutputTokens().ifPresent(max -> requestBody.put("max_tokens", max));
			config.temperature().ifPresent(temp -> requestBody.put("temperature", temp));
			config.topP().ifPresent(p -> requestBody.put("top_p", p));
			// top_k is intentionally not forwarded — OpenAI rejects unknown params and many
			// LiteLLM-OpenAI-compat shims drop or 400 on it. Anthropic prefers top_p anyway.
			config.stopSequences().ifPresent(stops -> {
				ArrayNode stopArray = objectMapper.createArrayNode();
				// Anthropic via LiteLLM rejects whitespace-only stop sequences with
				// "stop_sequences: each stop sequence must contain non-whitespace".
				stops.stream()
						.filter(s -> s != null && !s.isBlank())
						.forEach(stopArray::add);
				if (!stopArray.isEmpty()) {
					requestBody.set("stop", stopArray);
				}
			});
		}

		return requestBody;
	}

	private ObjectNode emptyObjectSchema() {
		ObjectNode emptySchema = objectMapper.createObjectNode();
		emptySchema.put("type", "object");
		emptySchema.set("properties", objectMapper.createObjectNode());
		return emptySchema;
	}

	/**
	 * Generates a tool-call ID compatible with both OpenAI ({@code call_<24-hex>}) and
	 * Anthropic-via-LiteLLM (which maps it to {@code tool_use_id} unchanged).
	 */
	private static String generateToolCallId() {
		return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
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
			if (message == null || !message.isObject()) {
				throw new IOException("Invalid response format: choice missing message");
			}

			JsonNode toolCallsNode = message.get("tool_calls");
			boolean hasToolCalls = toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty();

			if (hasToolCalls) {
				List<Part> parts = new ArrayList<>();
				int parseFailures = 0;
				for (JsonNode toolCallNode : toolCallsNode) {
					JsonNode idNode = toolCallNode.get("id");
					String toolCallId = (idNode != null && !idNode.isNull() && !idNode.asText().isEmpty())
							? idNode.asText()
							: generateToolCallId();

					JsonNode functionNode = toolCallNode.get("function");
					if (functionNode == null || !functionNode.isObject()) {
						logger.warn("tool_call missing 'function' object, skipping: {}", toolCallNode);
						parseFailures++;
						continue;
					}
					JsonNode nameNode = functionNode.get("name");
					if (nameNode == null || nameNode.isNull() || nameNode.asText().isEmpty()) {
						logger.warn("tool_call.function missing 'name', skipping: {}", toolCallNode);
						parseFailures++;
						continue;
					}
					String toolName = nameNode.asText();

					// Spec says arguments is a JSON string, but some proxies (vLLM with certain
					// tool parsers, older LiteLLM Anthropic shims) emit it as a parsed object.
					JsonNode argsNode = functionNode.get("arguments");
					String argumentsJson;
					if (argsNode == null || argsNode.isNull()) {
						argumentsJson = "";
					} else if (argsNode.isTextual()) {
						argumentsJson = argsNode.asText();
					} else {
						argumentsJson = argsNode.toString();
					}

					try {
						Map<String, Object> args = (argumentsJson == null || argumentsJson.isBlank())
								? Map.of()
								: objectMapper.readValue(argumentsJson,
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
						parseFailures++;
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
				if (parseFailures > 0) {
					// All tool calls failed to parse — surface as error rather than masquerade
					// as an empty text response, which would silently break the agent loop.
					throw new IOException("All " + parseFailures + " tool_calls failed to parse");
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
	 *
	 * <p>Children are snapshotted before recursion so future edits that add or remove
	 * sibling keys cannot trigger {@code ConcurrentModificationException}.
	 */
	private void lowercaseSchemaTypes(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			ObjectNode objNode = (ObjectNode) node;
			JsonNode typeNode = objNode.get("type");
			if (typeNode != null && typeNode.isTextual()) {
				objNode.put("type", typeNode.asText().toLowerCase());
			}
			List<JsonNode> children = new ArrayList<>();
			objNode.fields().forEachRemaining(entry -> children.add(entry.getValue()));
			children.forEach(this::lowercaseSchemaTypes);
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
