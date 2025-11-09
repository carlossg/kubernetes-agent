package org.csanchez.adk.agents.k8sagent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
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
	
	// JSON Schema for A2AResponse - ensures structured output
	// Using string format since direct Type enum access may not be available
	private static final Schema A2A_RESPONSE_SCHEMA = createResponseSchema();
	
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
			"version", "1.0.0"
		));
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
			
			// Create dedicated agent with structured output schema
			LlmAgent analysisAgent = LlmAgent.builder()
				.name("a2a-analysis")
				.model(modelName)
				.instruction("You are a Kubernetes SRE analyzing canary deployments. Provide structured analysis.")
				.outputSchema(A2A_RESPONSE_SCHEMA)
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
					userMsg
				);
				
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
	 * With structured output, this is a simple JSON parse
	 */
	private A2AResponse parseJsonResponse(String jsonResponse) {
		try {
			// Parse the JSON response directly
			A2AResponse response = objectMapper.readValue(jsonResponse, A2AResponse.class);
			
			// Ensure prLink is not empty string (set to null)
			if (response.getPrLink() != null && response.getPrLink().trim().isEmpty()) {
				response.setPrLink(null);
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


