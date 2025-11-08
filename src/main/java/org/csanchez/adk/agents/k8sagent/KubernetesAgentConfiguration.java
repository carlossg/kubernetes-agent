package org.csanchez.adk.agents.k8sagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.google.adk.web.AgentLoader;

/**
 * Configuration for the Kubernetes Agent.
 * Ensures our ClasspathAgentLoader is used instead of the default CompiledAgentLoader.
 */
@Configuration
public class KubernetesAgentConfiguration {

	@Bean("agentLoader")
	@Primary
	public AgentLoader agentLoader() {
		return new ClasspathAgentLoader();
	}
}

