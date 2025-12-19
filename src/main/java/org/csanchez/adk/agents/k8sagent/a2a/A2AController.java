package org.csanchez.adk.agents.k8sagent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Schema;
import org.csanchez.adk.agents.k8sagent.KubernetesAgent;
import org.csanchez.adk.agents.k8sagent.utils.RetryHelper;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Agent-to-Agent (A2A) communication
 */
@RestController
@RequestMapping("/a2a")
public class A2AController {

	private static final Logger logger = LoggerFactory.getLogger(A2AController.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();

	private final InMemoryRunner runner;

	private static Schema createResponseSchema() {
		try {
			// Build schema using JSON representation
			return Schema.fromJson("""
					{
						"type": "object",
						"properties": {
							"analysis": {
								"type": "string",
								"description": "Detailed analysis of the canary deployment"
							},
							"rootCause": {
								"type": "string",
								"description": "Identified root cause of any issues"
							},
							"remediation": {
								"type": "string",
								"description": "Suggested remediation steps"
							},
							"prLink": {
								"type": "string",
								"description": "GitHub PR link if a fix was created (optional)",
								"nullable": true
							},
							"promote": {
								"type": "boolean",
								"description": "Whether to promote the canary deployment (true) or abort (false)"
							},
							"confidence": {
								"type": "integer",
								"description": "Confidence level in the analysis (0-100)"
							}
						},
						"required": ["analysis", "rootCause", "remediation", "promote", "confidence"]
					}
					""");
		} catch (Exception e) {
			logger.error("Failed to create response schema", e);
			return Schema.builder().build();
		}
	}

	/**
	 * Constructor with dependency injection for testability
	 */
	@Autowired
	public A2AController(InMemoryRunner runner) {
		this.runner = runner;
	}

	/**
	 * Health check endpoint
	 */
	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health() {
		return ResponseEntity.ok(Map.of(
				"status", "healthy",
				"agent", "KubernetesAgent",
				"version", "1.0.0"));
	}

	/**
	 * Main A2A analyze endpoint
	 * Called by rollouts-plugin-metric-ai to analyze canary issues
	 */
	@PostMapping("/analyze")
	public ResponseEntity<A2AResponse> analyze(@RequestBody A2ARequest request) {
		logger.info("Received A2A analysis request from user: {}", request.getUserId());

		try {
			// Get model name from the environment variable (same as root agent)
			String modelName = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash");

			// Create dedicated agent with tools (cannot use outputSchema with tools)
			LlmAgent analysisAgent = LlmAgent.builder()
					.name("a2a-analysis")
					.model(modelName)
					.instruction(
							"""
									You are a Kubernetes SRE analyzing canary deployments. Use your Kubernetes tools to fetch logs and events.

									CRITICAL: You MUST respond with valid JSON in this exact format:
									{
										"analysis": "detailed analysis text",
										"rootCause": "identified root cause",
										"remediation": "suggested remediation steps",
										"prLink": "github PR link or null",
										"promote": true or false,
										"confidence": 0-100
									}

									Use tools to gather real data, then provide your analysis in the JSON format above.
									""")
					.tools((Object[]) KubernetesAgent.K8S_TOOLS.toArray(new BaseTool[0])) // Use the same tools as the
																							// root agent
					.build();

			// Create runner with analysis agent
			InMemoryRunner analysisRunner = new InMemoryRunner(analysisAgent);

			// Create session for this analysis
			Session session = analysisRunner.sessionService()
					.createSession("a2a-analysis", request.getUserId())
					.blockingGet();

			// Build prompt with context
			String prompt = buildPrompt(request);
			logger.debug("Built prompt: {}", prompt);

			// Invoke agent with retry logic for 429 errors
			Content userMsg = Content.fromParts(Part.fromText(prompt));
			List<String> responses = RetryHelper.executeWithRetry(() -> {
				Flowable<Event> events = analysisRunner.runAsync(
						request.getUserId(),
						session.id(),
						userMsg);

				// Collect results and log tool executions
				List<String> eventResponses = new ArrayList<>();
				events.blockingForEach(event -> {
					// Use shared logging helper
					KubernetesAgent.logToolExecution(event);

					String content = event.stringifyContent();
					if (content != null && !content.isEmpty()) {
						eventResponses.add(content);
						logger.debug("Received event content: {}", content);
					}
				});

				return eventResponses;
			}, "Gemini API analysis");

			// Parse JSON response
			String fullResponse = String.join("\n", responses);
			A2AResponse response = parseJsonResponse(fullResponse);

			logger.info("A2A analysis completed successfully");
			logger.debug("Analysis result: promote={}, confidence={}",
					response.isPromote(), response.getConfidence());

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			logger.error("Error processing A2A request from user: {}", request.getUserId(), e);
			logger.error("Request details - Prompt: {}", request.getPrompt());
			logger.error("Request details - Context: {}", request.getContext());

			A2AResponse errorResponse = new A2AResponse();
			errorResponse.setAnalysis("Error: " + e.getMessage());
			errorResponse.setRootCause("Analysis failed");
			errorResponse.setRemediation("Unable to provide remediation");
			errorResponse.setPromote(true); // Default to promote on error
			errorResponse.setConfidence(0);

			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(errorResponse);
		}
	}

	/**
	 * Build prompt from A2A request
	 */
	private String buildPrompt(A2ARequest request) {
		Map<String, Object> context = request.getContext();

		StringBuilder prompt = new StringBuilder();
		prompt.append(request.getPrompt()).append("\n\n");

		if (context != null) {
			prompt.append("Context:\n");
			context.forEach((key, value) -> {
				if (value != null) {
					prompt.append("- ").append(key).append(": ").append(value).append("\n");
				}
			});
		}

		prompt.append("\nYou have access to Kubernetes tools. Use them to gather information:\n");
		prompt.append("1. Use get_pod_logs to fetch pod logs for analysis\n");
		prompt.append("2. Use get_kubernetes_events to see recent events\n");
		prompt.append("3. Use debug_kubernetes_pod to check pod status\n");
		prompt.append("4. Compare stable vs canary pod behavior\n");
		
		// Add extra prompt if provided in context
		if (context != null && context.containsKey("extraPrompt")) {
			String extraPrompt = (String) context.get("extraPrompt");
			if (extraPrompt != null && !extraPrompt.isEmpty()) {
				prompt.append("\nAdditional context: ").append(extraPrompt).append("\n");
			}
		}
		
		prompt.append("\nProvide a structured response with:\n");
		prompt.append("- analysis: Detailed analysis text\n");
		prompt.append("- rootCause: Identified root cause\n");
		prompt.append("- remediation: Suggested remediation steps\n");
		prompt.append("- prLink: GitHub PR link if applicable (can be null)\n");
		prompt.append("- promote: true to promote canary, false to abort\n");
		prompt.append("- confidence: Confidence level 0-100\n");

		return prompt.toString();
	}

	/**
	 * Parse JSON response from the agent into A2AResponse
	 * Handles markdown code blocks and extracts clean JSON
	 */
	private A2AResponse parseJsonResponse(String jsonResponse) {
		try {
			// Clean up the response - remove markdown code blocks if present
			String cleanJson = jsonResponse.trim();

			// Remove tool execution logs (Function Call/Response) that might be mixed in
			// These appear as "Function Call: ..." or "Function Response: ..." lines
			if (cleanJson.contains("Function Call:") || cleanJson.contains("Function Response:")) {
				// Split by lines and filter out tool execution logs
				String[] lines = cleanJson.split("\\n");
				StringBuilder filtered = new StringBuilder();
				boolean inJson = false;
				
				for (String line : lines) {
					// Skip tool execution log lines
					if (line.trim().startsWith("Function Call:") || 
						line.trim().startsWith("Function Response:") ||
						line.trim().isEmpty()) {
						continue;
					}
					
					// Detect start of JSON
					if (line.trim().startsWith("{") || line.trim().startsWith("```")) {
						inJson = true;
					}
					
					if (inJson) {
						filtered.append(line).append("\n");
					}
				}
				
				cleanJson = filtered.toString().trim();
			}

			// Remove markdown code block delimiters (```json ... ``` or ``` ... ```)
			if (cleanJson.startsWith("```")) {
				// Find the first newline after ```json or ```
				int startIndex = cleanJson.indexOf('\n');
				if (startIndex != -1) {
					cleanJson = cleanJson.substring(startIndex + 1);
				}

				// Remove closing ```
				int endIndex = cleanJson.lastIndexOf("```");
				if (endIndex != -1) {
					cleanJson = cleanJson.substring(0, endIndex);
				}
			}

			cleanJson = cleanJson.trim();

			logger.debug("Cleaned JSON for parsing: {}", cleanJson);

			// Parse the JSON response directly
			A2AResponse response = objectMapper.readValue(cleanJson, A2AResponse.class);

			// Ensure prLink is not empty string or "null" string (set to null)
			if (response.getPrLink() != null) {
				String prLink = response.getPrLink().trim();
				// Remove any explanatory text in parentheses
				int parenIndex = prLink.indexOf('(');
				if (parenIndex != -1) {
					prLink = prLink.substring(0, parenIndex).trim();
				}
				if (prLink.isEmpty() || prLink.equals("null") || prLink.endsWith("/null")) {
					response.setPrLink(null);
				} else {
					response.setPrLink(prLink);
				}
			}

			return response;
		} catch (Exception e) {
			logger.error("Failed to parse JSON response: {}", jsonResponse, e);

			// Fallback response
			A2AResponse fallback = new A2AResponse();
			fallback.setAnalysis(jsonResponse);
			fallback.setRootCause("Unable to parse structured response");
			fallback.setRemediation("Manual review required");
			fallback.setPromote(true); // Safe default
			fallback.setConfidence(0);

			return fallback;
		}
	}
}
