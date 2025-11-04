package org.csanchez.rollout.k8sagent.k8s;

import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool for retrieving Kubernetes metrics
 */
@ApplicationScoped
public class K8sMetricsTool {
	
	@Inject
	KubernetesClient k8sClient;
	
	@Tool("Get resource metrics for a Kubernetes pod")
	public Map<String, Object> getMetrics(String namespace, String podName) {
		Log.info("=== Executing Tool: getMetrics ===");
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		Log.info(MessageFormat.format("Getting metrics for pod: {0}/{1}", namespace, podName));
		
		
		try {
			Pod pod = k8sClient.pods()
				.inNamespace(namespace)
				.withName(podName)
				.get();
			
			if (pod == null) {
				return Map.of("error", MessageFormat.format("Pod not found: {0}/{1}", namespace, podName));
			}
			
			Map<String, Object> metricsInfo = new HashMap<>();
			metricsInfo.put("podName", podName);
			metricsInfo.put("namespace", namespace);
			
			// Get resource requests and limits from containers
			if (pod.getSpec().getContainers() != null) {
				pod.getSpec().getContainers().forEach(container -> {
					ResourceRequirements resources = container.getResources();
					
					if (resources != null) {
						Map<String, Object> containerResources = new HashMap<>();
						containerResources.put("containerName", container.getName());
						
						// Requests
						if (resources.getRequests() != null) {
							Map<String, String> requests = new HashMap<>();
							resources.getRequests().forEach((key, value) -> 
								requests.put(key, value.toString())
							);
							containerResources.put("requests", requests);
						}
						
						// Limits
						if (resources.getLimits() != null) {
							Map<String, String> limits = new HashMap<>();
							resources.getLimits().forEach((key, value) -> 
								limits.put(key, value.toString())
							);
							containerResources.put("limits", limits);
						}
						
						metricsInfo.put(container.getName(), containerResources);
					}
				});
			}
			
			// Try to get actual metrics from metrics-server if available
			// Note: This requires metrics-server to be installed in the cluster
			try {
				// This would require the metrics API, which is an optional extension
				// For now, we'll just note that metrics-server is needed
				metricsInfo.put("note", "Install metrics-server for real-time CPU/Memory usage");
			} catch (Exception e) {
				Log.debug(MessageFormat.format("Metrics server not available: {0}", e.getMessage()));
			}
			
			Log.info(MessageFormat.format("Retrieved metrics for pod: {0}/{1}", namespace, podName));
			return metricsInfo;
			
		} catch (Exception e) {
			Log.error("Error getting pod metrics", e);
			return Map.of("error", e.getMessage());
		}
	}
}


