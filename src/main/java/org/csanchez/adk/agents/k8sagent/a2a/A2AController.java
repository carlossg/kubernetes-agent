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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * REST controller for Agent-to-Agent (A2A) communication
 */
@RestController
@RequestMapping("/a2a")
public class A2AController {

	private static final Logger logger = LoggerFactory.getLogger(A2AController.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final ExecutorService executorService = Executors.newCachedThreadPool();

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
	 * Supports both single-model and multi-model parallel analysis
	 */
	@PostMapping("/analyze")
	public ResponseEntity<A2AResponse> analyze(@RequestBody A2ARequest request) {
		logger.info("Received A2A analysis request from user: {}", request.getUserId());

		try {
			// Determine which models to use
			List<String> modelsToUse = determineModelsToUse(request);
			logger.info("Using {} model(s) for analysis: {}", modelsToUse.size(), modelsToUse);
			
			// Check if multi-model analysis is needed
			if (modelsToUse.size() > 1) {
				return analyzeWithMultipleModels(request, modelsToUse);
			} else {
				return analyzeWithSingleModel(request, modelsToUse.get(0));
			}

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
	 * Determine which models to use for this request
	 */
	private List<String> determineModelsToUse(A2ARequest request) {
		// Check if specific models requested
		if (request.getModelsToUse() != null && !request.getModelsToUse().isEmpty()) {
			return request.getModelsToUse();
		}
		
		// Use configured models from KubernetesAgent
		return KubernetesAgent.getModelsToUse();
	}
	
	/**
	 * Analyze with multiple models in parallel and aggregate results
	 */
	private ResponseEntity<A2AResponse> analyzeWithMultipleModels(A2ARequest request, List<String> models) {
		logger.info("Running parallel analysis with {} models", models.size());
		
		// Run analyses in parallel
		List<CompletableFuture<ModelAnalysisResult>> futures = models.stream()
				.map(modelName -> CompletableFuture.supplyAsync(
						() -> runSingleModelAnalysis(request, modelName),
						executorService))
				.collect(Collectors.toList());
		
		// Wait for all to complete
		CompletableFuture<Void> allOf = CompletableFuture.allOf(
				futures.toArray(new CompletableFuture[0]));
		
		try {
			allOf.join(); // Wait for all futures to complete
			
			// Collect results
			List<ModelAnalysisResult> results = futures.stream()
					.map(CompletableFuture::join)
					.collect(Collectors.toList());
			
			logger.info("All {} model analyses completed", results.size());
			
			// Aggregate results using weighted voting
			VotingAggregator.AggregatedResult aggregated = VotingAggregator.aggregate(results);
			
			// Build response
			A2AResponse response = new A2AResponse();
			response.setAnalysis(aggregated.getConsolidatedAnalysis());
			response.setRootCause(aggregated.getConsolidatedRootCause());
			response.setRemediation(aggregated.getConsolidatedRemediation());
			response.setPromote(aggregated.isPromote());
			response.setConfidence(aggregated.getAverageConfidence());
			response.setModelResults(results);
			response.setVotingRationale(aggregated.getVotingRationale());
			
			logger.info("Multi-model analysis complete: promote={}, promoteScore={}, rollbackScore={}",
					aggregated.isPromote(), aggregated.getPromoteScore(), aggregated.getRollbackScore());
			
			return ResponseEntity.ok(response);
			
		} catch (Exception e) {
			logger.error("Error during multi-model analysis", e);
			throw new RuntimeException("Multi-model analysis failed", e);
		}
	}
	
	/**
	 * Analyze with a single model (backward compatible)
	 */
	private ResponseEntity<A2AResponse> analyzeWithSingleModel(A2ARequest request, String modelName) {
		logger.info("Running single-model analysis with: {}", modelName);
		
		ModelAnalysisResult result = runSingleModelAnalysis(request, modelName);
		
		// Convert to A2AResponse
		A2AResponse response = new A2AResponse();
		response.setAnalysis(result.getAnalysis());
		response.setRootCause(result.getRootCause());
		response.setRemediation(result.getRemediation());
		response.setPromote(result.isPromote());
		response.setConfidence(result.getConfidence());
		
		// Include single model result in list for consistency
		response.setModelResults(List.of(result));
		
		logger.info("Single-model analysis complete: promote={}, confidence={}",
				result.isPromote(), result.getConfidence());
		
		return ResponseEntity.ok(response);
	}
	
	/**
	 * Run analysis with a single model
	 */
	private ModelAnalysisResult runSingleModelAnalysis(A2ARequest request, String modelName) {
		long startTime = System.currentTimeMillis();
		ModelAnalysisResult result = new ModelAnalysisResult();
		result.setModelName(modelName);
		
		try {
			logger.info("Starting analysis with model: {}", modelName);
			
			// Create dedicated agent with tools
			LlmAgent analysisAgent = LlmAgent.builder()
					.name("a2a-analysis-" + modelName)
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
					.tools((Object[]) KubernetesAgent.K8S_TOOLS.toArray(new BaseTool[0]))
					.build();

			// Create runner with analysis agent
			InMemoryRunner analysisRunner = new InMemoryRunner(analysisAgent);

			// Create a unique session for this specific analysis
			String sessionName = "a2a-analysis-" + System.currentTimeMillis();
			Session session = analysisRunner.sessionService()
					.createSession(sessionName, request.getUserId())
					.blockingGet();

			// Build prompt with context
			String prompt = buildPrompt(request);
			logger.debug("Built prompt for {}: {}", modelName, prompt);

			// Invoke agent with retry logic
			Content userMsg = Content.fromParts(Part.fromText(prompt));
			List<String> responses = RetryHelper.executeWithRetry(() -> {
				Flowable<Event> events = analysisRunner.runAsync(
						request.getUserId(),
						session.id(),
						userMsg);

				// Collect results and log tool executions
				List<String> eventResponses = new ArrayList<>();
				events.blockingForEach(event -> {
					KubernetesAgent.logToolExecution(event);
					String content = event.stringifyContent();
					if (content != null && !content.isEmpty()) {
						eventResponses.add(content);
					}
				});

				return eventResponses;
			}, "Model analysis: " + modelName);

			// Parse JSON response
			String fullResponse = String.join("\n", responses);
			A2AResponse parsedResponse = parseJsonResponse(fullResponse);
			
			// Populate result with null-safety
			result.setAnalysis(parsedResponse.getAnalysis() != null ? parsedResponse.getAnalysis() : "Analysis completed");
			result.setRootCause(parsedResponse.getRootCause() != null ? parsedResponse.getRootCause() : "Root cause analysis pending");
			result.setRemediation(parsedResponse.getRemediation() != null ? parsedResponse.getRemediation() : "Review required");
			result.setPromote(parsedResponse.isPromote());
			result.setConfidence(parsedResponse.getConfidence());
			result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
			
			logger.info("Model {} completed: promote={}, confidence={}, time={}ms",
					modelName, result.isPromote(), result.getConfidence(), result.getExecutionTimeMs());
			
		} catch (Exception e) {
			// Check if it's a service unavailability error
			if (RetryHelper.isServiceUnavailableError(e)) {
				logger.error("Error running analysis with model: {} - Service unavailable or unreachable: {}", 
					modelName, e.getMessage());
			} else {
				logger.error("Error running analysis with model: {}", modelName, e);
			}
			result.setError("Analysis failed: " + e.getMessage());
			result.setAnalysis("Analysis encountered an error: " + e.getMessage());
			result.setRootCause("Technical failure during analysis");
			result.setRemediation("Please check logs and retry");
			result.setPromote(true); // Default to promote on error
			result.setConfidence(0);
			result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
		}
		
		return result;
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
