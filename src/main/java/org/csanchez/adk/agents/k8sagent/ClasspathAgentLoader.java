package org.csanchez.adk.agents.k8sagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.web.AgentLoader;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.NoSuchElementException;

/**
 * Custom AgentLoader that loads the Kubernetes Agent from the classpath.
 * This works both in development (with target/classes) and in production
 * (with packaged JAR).
 * 
 * This loader is always used for this application, replacing the default CompiledAgentLoader.
 */
public class ClasspathAgentLoader implements AgentLoader {

	private static final Logger logger = LoggerFactory.getLogger(ClasspathAgentLoader.class);
	private static final String AGENT_NAME = "KubernetesAgent";
	private final BaseAgent agent;

	public ClasspathAgentLoader() {
		logger.info("Initializing ClasspathAgentLoader for Kubernetes Agent");
		// Initialize the agent once
		this.agent = KubernetesAgent.initAgent();
		logger.info("ClasspathAgentLoader initialized with 1 agent: {}", AGENT_NAME);
	}

	@Override
	@Nonnull
	public ImmutableList<String> listAgents() {
		return ImmutableList.of(AGENT_NAME);
	}

	@Override
	public BaseAgent loadAgent(String name) {
		if (!AGENT_NAME.equals(name)) {
			throw new NoSuchElementException("Agent not found: " + name);
		}
		return agent;
	}
}

