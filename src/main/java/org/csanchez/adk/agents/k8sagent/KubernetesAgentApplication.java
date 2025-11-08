package org.csanchez.adk.agents.k8sagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Main Spring Boot application entry point for the Kubernetes Agent.
 * Scans both the ADK packages and our agent packages.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
	"org.csanchez.adk.agents.k8sagent",
	"com.google.adk.web"
})
public class KubernetesAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(KubernetesAgentApplication.class, args);
	}
}

