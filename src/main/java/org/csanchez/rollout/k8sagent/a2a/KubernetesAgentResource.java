package org.csanchez.rollout.k8sagent.a2a;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.text.MessageFormat;
import java.util.Map;

import org.csanchez.rollout.k8sagent.agents.KubernetesAgent;
import org.csanchez.rollout.k8sagent.model.KubernetesAgentRequest;
import org.csanchez.rollout.k8sagent.model.KubernetesAgentResponse;
import org.csanchez.rollout.k8sagent.service.AgentResponseParser;

/**
 * REST API controller for Kubernetes Agent.
 * Provides HTTP endpoints for health checks and canary analysis.
 */
@Path("/a2a")
public class KubernetesAgentResource {

    @Inject
    KubernetesAgent kubernetesAgent;
    
    @Inject
    AgentResponseParser responseParser;
     
    /**
     * Main analyze endpoint
     */
    @POST
    @Path("/analyze")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyze(KubernetesAgentRequest request) {
        Log.info(MessageFormat.format("Received analysis request from user: {0}", request.userId()));
        
        try {
            // Build prompt with context
            String prompt = buildPrompt(request);
            Log.debug(MessageFormat.format("Built prompt: {0}", prompt));
            
            // Execute analysis using the KubernetesAgent
            String analysisResult = kubernetesAgent.chat(prompt);
            
            // Parse response
            KubernetesAgentResponse response = responseParser.parse(analysisResult);
            Log.info("Analysis completed successfully");
            return Response.ok(response).build();
            
        } catch (Exception e) {
            Log.error(MessageFormat.format("Error processing request from user: {0}", request.userId()), e);
            Log.error(MessageFormat.format("Request details - Prompt: {0}", request.prompt()));
            Log.error(MessageFormat.format("Request details - Context: {0}", request.context()));
            
            KubernetesAgentResponse errorResponse = KubernetesAgentResponse.empty()
                .withAnalysis(MessageFormat.format("Error: {0}", e.getMessage()))
                .withRootCause("Analysis failed")
                .withRemediation("Unable to provide remediation")
                .withPromote(true) // Default to promote on error
                .withConfidence(0);
            
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                .entity(errorResponse)
                .build();
        }
    }
    
    /**
     * Build prompt from request
     */
    private String buildPrompt(KubernetesAgentRequest request) {
        Map<String, Object> context = request.context();
        
        StringBuilder prompt = new StringBuilder();
        prompt.append(request.prompt()).append("\n\n");
        
        if (context != null) {
            prompt.append("Context:\n");
            context.forEach((key, value) -> {
                if (value != null) {
                    prompt.append("- ").append(key).append(": ").append(value).append("\n");
                }
            });
        }
        
        prompt.append("\nPlease analyze the canary deployment and provide:\n");
        prompt.append("1. Root cause of any issues\n");
        prompt.append("2. Whether to promote the canary (true/false)\n");
        prompt.append("3. Confidence level (0-100)\n");
        prompt.append("4. Remediation steps if needed\n");
        
        return prompt.toString();
    }
}