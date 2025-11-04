package org.csanchez.rollout.k8sagent.model;

import java.util.Map;

/**
 * Request class for Kubernetes Agent analysis
 */
public record KubernetesAgentRequest(
    String userId,
    String prompt,
    Map<String, Object> context
) {}


