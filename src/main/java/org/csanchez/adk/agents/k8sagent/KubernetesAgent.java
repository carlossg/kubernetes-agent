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
	private static final String MODEL_NAME = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash");
	private static final String AGENT_NAME = "KubernetesAgent";
	private static final String USER_ID = "argo-rollouts";

	// Register custom vLLM Gemma model support
	static {
		registerVllmGemmaModels();
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

	public static void main(String[] args) {
		// Start Spring Boot application (includes REST API for A2A)
		SpringApplication.run(KubernetesAgent.class, args);

		// Optionally run in console mode if not in server mode
		if (args.length > 0 && "console".equals(args[0])) {
			runConsoleMode();
		}
	}

	@Bean
	public InMemoryRunner getRunner() {
		return new InMemoryRunner(ROOT_AGENT);
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

	/**
	 * Log tool execution details from events
	 * Shared helper method used by both console mode and A2A controller
	 */
	public static void logToolExecution(Event event) {
		logger.debug("=== Event received: type={} ===", event.getClass().getSimpleName());

		// Log tool calls if present
		if (event.content() != null && event.content().isPresent()) {
			Content content = event.content().get();
			if (content.parts() != null) {
				for (var part : content.parts().get()) {
					if (part.functionCall() != null && part.functionCall().isPresent()) {
						var functionCall = part.functionCall().get();
						logger.debug(">>> TOOL CALL: {}", functionCall.name());
						logger.debug(">>> TOOL ARGS: {}", functionCall.args());
					}
					if (part.functionResponse() != null && part.functionResponse().isPresent()) {
						var functionResponse = part.functionResponse().get();
						logger.debug("<<< TOOL RESULT: {}", functionResponse.name());
						logger.debug("<<< TOOL RESPONSE: {}", functionResponse.response());
					}
				}
			}
		}
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
												 - Then call create_github_pr with this information

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
