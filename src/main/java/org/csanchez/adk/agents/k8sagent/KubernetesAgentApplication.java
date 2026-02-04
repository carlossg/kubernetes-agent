package org.csanchez.adk.agents.k8sagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Main Spring Boot application entry point for the Kubernetes Agent.
 * Only scans our agent packages - doesn't include com.google.adk.web
 * to prevent the ADK browser UI from intercepting our REST endpoints.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
	"org.csanchez.adk.agents.k8sagent"
})
public class KubernetesAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(KubernetesAgentApplication.class, args);
	}
}

