package org.csanchez.rollout.k8sagent.agents;

import org.csanchez.rollout.k8sagent.k8s.K8sTools;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.ToolBox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Agent interface for canary analysis
 */
@RegisterAiService
@ApplicationScoped
public interface CanaryAnalysisAgent {
    @SystemMessage("""
        You are an expert Kubernetes SRE specializing in canary deployment analysis.
        Analyze the provided metrics and logs to determine if a canary deployment is healthy.
        Be thorough but concise in your analysis.
    """)
    @ToolBox(K8sTools.class)
    String analyzeCanary();
}