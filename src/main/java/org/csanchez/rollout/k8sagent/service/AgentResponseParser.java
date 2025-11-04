package org.csanchez.rollout.k8sagent.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.csanchez.rollout.k8sagent.model.KubernetesAgentResponse;

/**
 * Service responsible for parsing agent responses into structured format.
 * Extracts key information like root cause, remediation, PR links, and promotion decisions.
 */
@ApplicationScoped
public class AgentResponseParser {
    
    /**
     * Parse the agent's response into a structured KubernetesAgentResponse
     */
    public KubernetesAgentResponse parse(String fullResponse) {
        // Extract root cause (look for common patterns)
        String rootCause = extractSection(fullResponse, "root cause");
        if (rootCause == null) {
            rootCause = "See analysis";
        }
        
        // Extract remediation
        String remediation = extractSection(fullResponse, "remediation");
        if (remediation == null) {
            remediation = "See analysis";
        }
        
        // Try to extract promotion decision
        boolean promote = !fullResponse.toLowerCase().contains("do not promote") &&
            !fullResponse.toLowerCase().contains("abort") &&
            !fullResponse.toLowerCase().contains("rollback");
        
        // Set confidence (would be extracted from agent response in production)
        int confidence = promote ? 80 : 50;
        
        // Extract PR link if available
        String prLink = extractPRLink(fullResponse);
        
        return new KubernetesAgentResponse(
            fullResponse,
            rootCause,
            remediation,
            prLink,
            promote,
            confidence
        );
    }
    
    /**
     * Extract a section from the response by looking for headers
     */
    private String extractSection(String text, String sectionName) {
        String lowerText = text.toLowerCase();
        String lowerSection = sectionName.toLowerCase();
        
        int start = lowerText.indexOf(lowerSection);
        if (start == -1) {
            return null;
        }
        
        // Find the end (next section or end of text)
        int end = text.length();
        for (String marker : java.util.List.of("\n## ", "\n# ", "\n\n## ")) {
            int markerPos = text.indexOf(marker, start + sectionName.length());
            if (markerPos != -1 && markerPos < end) {
                end = markerPos;
            }
        }
        
        return text.substring(start, end).trim();
    }
    
    /**
     * Extract PR link from the response if available
     */
    private String extractPRLink(String text) {
        // Common patterns for PR links
        for (String pattern : java.util.List.of("github.com/.+/pull/\\d+", "PR: https?://[^\\s]+")) {
            Pattern regex = Pattern.compile(pattern);
            Matcher matcher = regex.matcher(text);
            if (matcher.find()) {
                return matcher.group(0);
            }
        }
        return null;
    }
}