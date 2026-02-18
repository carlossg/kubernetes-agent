package org.csanchez.adk.agents.k8sagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.models.LlmRegistry;
import org.csanchez.adk.agents.k8sagent.models.VllmGemma;
import org.csanchez.adk.agents.k8sagent.remediation.GitHubPRTool;
import org.csanchez.adk.agents.k8sagent.remediation.GitOperations;
import org.csanchez.adk.agents.k8sagent.tools.*;
import com.google.adk.sessions.Session;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.GoogleSearchTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import com.google.adk.events.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@SpringBootApplication
public class KubernetesAgent {

	private static final Logger logger = LoggerFactory.getLogger(KubernetesAgent.class);
	private static final String MODEL_NAME = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3-flash-preview");
	private static final String AGENT_NAME = "KubernetesAgent";
	private static final String USER_ID = "argo-rollouts";
	
	// Multi-model configuration
	private static final String MODELS_TO_USE = System.getenv().getOrDefault("MODELS_TO_USE", "");
	private static final boolean ENABLE_MULTI_MODEL = Boolean.parseBoolean(
			System.getenv().getOrDefault("ENABLE_MULTI_MODEL", "false"));
	private static final List<String> AVAILABLE_MODELS = new ArrayList<>();

	// Register custom vLLM Gemma model support
	static {
		registerVllmGemmaModels();
		initializeAvailableModels();
	}

	// K8S_TOOLS needed for A2A analysis agent (must be initialized before
	// ROOT_AGENT)
	public static final List<BaseTool> K8S_TOOLS = initTools();

	// ROOT_AGENT needed for ADK Web UI and A2A communication
	public static final BaseAgent ROOT_AGENT = initAgent();

	/**
	 * Register vLLM Gemma models in the LLM registry
	 */
	private static void registerVllmGemmaModels() {
		String vllmApiBase = System.getenv("VLLM_API_BASE");
		String vllmApiKey = System.getenv().getOrDefault("VLLM_API_KEY", "not-needed");

		if (vllmApiBase != null && !vllmApiBase.isEmpty()) {
			logger.info("Registering vLLM Gemma models with API base: {}", vllmApiBase);
			
			// Register pattern for gemma-* models to use vLLM
			LlmRegistry.registerLlm("gemma-.*", modelName -> 
				VllmGemma.builder()
					.modelName(modelName)
					.apiBaseUrl(vllmApiBase)
					.apiKey(vllmApiKey)
					.build()
			);
		} else {
			logger.info("VLLM_API_BASE not set, vLLM Gemma models not registered");
		}
	}
	
	/**
	 * Initialize the list of available models
	 */
	private static void initializeAvailableModels() {
		// Always include the default Gemini model
		AVAILABLE_MODELS.add(MODEL_NAME);
		
		// Add vLLM models if configured
		String vllmApiBase = System.getenv("VLLM_API_BASE");
		String vllmModel = System.getenv().getOrDefault("VLLM_MODEL", "gemma-3-1b-it");
		if (vllmApiBase != null && !vllmApiBase.isEmpty()) {
			AVAILABLE_MODELS.add(vllmModel);
		}
		
		logger.info("Available models: {}", AVAILABLE_MODELS);
		logger.info("Multi-model analysis enabled: {}", ENABLE_MULTI_MODEL);
	}
	
	/**
	 * Get list of models to use for analysis
	 * Returns models specified in MODELS_TO_USE env var, or all available models if not set
	 */
	public static List<String> getModelsToUse() {
		if (!MODELS_TO_USE.isEmpty()) {
			// Parse comma-separated list
			List<String> models = new ArrayList<>();
			for (String model : MODELS_TO_USE.split(",")) {
				String trimmed = model.trim();
				if (!trimmed.isEmpty()) {
					models.add(trimmed);
				}
			}
			logger.debug("Using configured models: {}", models);
			return models;
		}
		
		// Default: use all available models if multi-model is enabled
		if (ENABLE_MULTI_MODEL) {
			logger.debug("Using all available models: {}", AVAILABLE_MODELS);
			return new ArrayList<>(AVAILABLE_MODELS);
		}
		
		// Single-model mode: use only the default model
		logger.debug("Single-model mode: using {}", MODEL_NAME);
		return List.of(MODEL_NAME);
	}
	
	/**
	 * Check if multi-model analysis is enabled
	 */
	public static boolean isMultiModelEnabled() {
		return ENABLE_MULTI_MODEL;
	}

	public static void main(String[] args) {
		// Start Spring Boot application (includes REST API for A2A)
		SpringApplication.run(KubernetesAgent.class, args);

		// Optionally run in console mode if not in server mode
		if (args.length > 0 && "console".equals(args[0])) {
			runConsoleMode();
		}
	}

	private static void runConsoleMode() {
		InMemoryRunner runner = new InMemoryRunner(ROOT_AGENT);
		Session session = runner.sessionService()
				.createSession(AGENT_NAME, USER_ID)
				.blockingGet();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			logger.info("Exiting Kubernetes Agent. Goodbye!");
		}));

		try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
			logger.info("Kubernetes AI Agent started in console mode. Type 'quit' to exit.");
			while (true) {
				System.out.print("\nYou > ");
				String userInput = scanner.nextLine();
				if ("quit".equalsIgnoreCase(userInput)) {
					break;
				}
				Content userMsg = Content.fromParts(Part.fromText(userInput));
				Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg);
				System.out.print("\nAgent > ");
				events.blockingForEach(event -> {
					logToolExecution(event);
					System.out.println(event.stringifyContent());
				});
			}
		}
	}

	// Track tool call start times for timing measurements
	private static final java.util.Map<String, Long> toolCallStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
	
	/**
	 * Log tool execution details from events
	 * Shared helper method used by both console mode and A2A controller
	 * 
	 * @param event The event to log
	 * @param modelName Optional model name for MDC context (can be null for console mode)
	 */
	public static void logToolExecution(Event event, String modelName) {
		// Set MDC context if model name provided
		String previousModel = null;
		if (modelName != null) {
			previousModel = MDC.get("model");
			MDC.put("model", modelName);
		}
		
		try {
			logger.debug("Event received eventType={}", event.getClass().getSimpleName());

			// Log tool calls if present
			if (event.content() != null && event.content().isPresent()) {
				Content content = event.content().get();
				if (content.parts() != null) {
					for (var part : content.parts().get()) {
						if (part.functionCall() != null && part.functionCall().isPresent()) {
							var functionCall = part.functionCall().get();
							if (functionCall.name() != null && functionCall.name().isPresent()) {
								String toolName = functionCall.name().get();
								long startTime = System.currentTimeMillis();
								
								// Store start time for this tool call
								String toolKey = toolName + "_" + System.identityHashCode(functionCall);
								toolCallStartTimes.put(toolKey, startTime);
								
								logger.debug("TOOL_CALL toolName={} args={} startTime={}", toolName, functionCall.args(), startTime);
							}
						}
						if (part.functionResponse() != null && part.functionResponse().isPresent()) {
							var functionResponse = part.functionResponse().get();
							if (functionResponse.name() != null && functionResponse.name().isPresent()) {
								String toolName = functionResponse.name().get();
								long endTime = System.currentTimeMillis();
								
								// Try to find matching start time
								// Since we can't match by object identity, find the most recent call for this tool
								Long startTime = toolCallStartTimes.entrySet().stream()
										.filter(e -> e.getKey().startsWith(toolName + "_"))
										.map(java.util.Map.Entry::getValue)
										.max(Long::compareTo)
										.orElse(null);
								
								if (startTime != null) {
									long executionTimeMs = endTime - startTime;
									logger.debug("TOOL_RESPONSE toolName={} executionTimeMs={} response={}", 
											toolName, executionTimeMs, functionResponse.response());
									
									// Clean up old entries for this tool
									toolCallStartTimes.entrySet().removeIf(e -> 
											e.getKey().startsWith(toolName + "_") && e.getValue().equals(startTime));
								} else {
									logger.debug("TOOL_RESPONSE toolName={} response={}", toolName, functionResponse.response());
								}
							}
						}
					}
				}
			}
		} finally {
			// Restore previous MDC context
			if (modelName != null) {
				if (previousModel != null) {
					MDC.put("model", previousModel);
				} else {
					MDC.remove("model");
				}
			}
		}
	}
	
	/**
	 * Log tool execution details from events (backward compatible, no model context)
	 */
	public static void logToolExecution(Event event) {
		logToolExecution(event, null);
	}

	/**
	 * Initialize Kubernetes tools (separate from agent for reuse in A2A)
	 */
	public static List<BaseTool> initTools() {
		try {
			// Kubernetes Tools - use FunctionTool.create() like cloud-run
			List<BaseTool> allTools = new ArrayList<>();
			allTools.add(FunctionTool.create(K8sTools.class, "listPods"));
			allTools.add(FunctionTool.create(K8sTools.class, "debugPod"));
			allTools.add(FunctionTool.create(K8sTools.class, "getEvents"));
			allTools.add(FunctionTool.create(K8sTools.class, "getLogs"));
			allTools.add(FunctionTool.create(K8sTools.class, "getMetrics"));
			allTools.add(FunctionTool.create(K8sTools.class, "inspectResources"));

			logger.info("Loaded {} Kubernetes tools", allTools.size());

			// GitHub remediation tool
			try {
				GitHubPRTool prTool = new GitHubPRTool(new GitOperations());
				allTools.add(prTool);
				logger.info("GitHub PR tool initialized");
			} catch (Exception e) {
				logger.warn("GitHub PR tool not available (GITHUB_TOKEN not set): {}", e.getMessage());
			}

			// Google Search for known issues
			LlmAgent searchAgent = LlmAgent.builder()
					.model(MODEL_NAME)
					// .model("gemma-3-1b-it")
					.name("search_agent")
					.description("Search Google for known issues")
					.instruction("You're a specialist in searching for known Kubernetes and software issues")
					.tools(new GoogleSearchTool())
					.outputKey("search_result")
					.build();

			allTools.add(AgentTool.create(searchAgent, false));

			logger.info("Total tools available: {}", allTools.size());
			logger.debug("Tools: {}", allTools);

			return allTools;
		} catch (Exception e) {
			logger.error("Failed to initialize tools", e);
			return new ArrayList<>();
		}
	}

	public static BaseAgent initAgent() {
		try {
			logger.info("Initializing Kubernetes AI Agent with model: {}", MODEL_NAME);

			// Get tools
			List<BaseTool> allTools = K8S_TOOLS;

			// Build the main agent
			BaseAgent agent = LlmAgent.builder()
					.model(MODEL_NAME)
					.name(AGENT_NAME)
					.description("Autonomous Kubernetes debugging and remediation agent")
					.instruction(
							"""
											You are an expert Kubernetes SRE and developer with deep knowledge of:
											- Container orchestration and Kubernetes internals
											- Common application failure patterns
											- Log analysis and root cause identification
											- Code remediation and bug fixing

											Your workflow:
											1. Analyze the problem description and identify the failing pod/service
											2. Gather comprehensive diagnostic data:
												 - List pods by label selector (use list_pods with labelSelector like 'role=stable' or 'role=canary')
												 - Pod status and conditions (use debug_kubernetes_pod)
												 - Recent events (use get_kubernetes_events)
												 - Container logs (use get_pod_logs, include previous=true if crashed)
												 - Resource metrics (use get_pod_metrics)
												 - Related resources like services, deployments (use inspect_kubernetes_resources)
											3. Identify root cause using AI analysis and pattern matching
											4. Search for known issues if applicable (use search_agent)
											5. If a code fix is needed, determine:
												 - Which repository to clone
												 - Which files need changes
												 - Specific code modifications (diffs)

												 IMPORTANT: You provide the WHAT (files to change, code diffs),
												 the tool handles the HOW (git clone, branch, commit, push, PR creation)
												 using standard libraries. Do NOT generate git commands.
											6. Return a comprehensive report with:
												 - Root cause
												 - Remediation steps taken
												 - PR link (if created)
												 - Recommendations for prevention

										Always gather data systematically before making conclusions.
										Be thorough but concise in your analysis.
									""")
					.tools((Object[]) allTools.toArray(new BaseTool[0]))
					.outputKey("k8s_agent_result")
					.build();

			logger.info("Kubernetes AI Agent initialized successfully");
			return agent;

		} catch (Exception e) {
			logger.error("Failed to initialize agent", e);
			throw new RuntimeException("Agent initialization failed", e);
		}
	}
}
