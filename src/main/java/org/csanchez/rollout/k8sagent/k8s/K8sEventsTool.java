package org.csanchez.rollout.k8sagent.k8s;

import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tool for retrieving Kubernetes events
 */
@ApplicationScoped
public class K8sEventsTool {
	
	@Inject
	KubernetesClient k8sClient;
	
	@Tool("Get Kubernetes events for a namespace or specific pod")
	public Map<String, Object> getEvents(String namespace, String podName, Integer limit) {
		Log.info("=== Executing Tool: getEvents ===");
		
		int eventLimit = (limit != null && limit > 0) ? limit : 50;
		
		if (namespace == null) {
			return Map.of("error", "namespace is required");
		}
		Log.info(MessageFormat.format("Getting events for namespace: {0}, pod: {1}", namespace, podName));
		
		
		try {
			List<Event> events = k8sClient.v1().events()
				.inNamespace(namespace)
				.list()
				.getItems();
			
			// Filter by pod name if provided
			if (podName != null && !podName.isEmpty()) {
				events = events.stream()
					.filter(e -> {
						if (e.getInvolvedObject() != null) {
							return podName.equals(e.getInvolvedObject().getName());
						}
						return false;
					})
					.collect(Collectors.toList());
			}
			
			// Sort by last timestamp (most recent first)
			events.sort((e1, e2) -> {
				String t1 = e1.getLastTimestamp() != null ? e1.getLastTimestamp() : e1.getMetadata().getCreationTimestamp();
				String t2 = e2.getLastTimestamp() != null ? e2.getLastTimestamp() : e2.getMetadata().getCreationTimestamp();
				return t2.compareTo(t1);
			});
			
			// Limit results
			events = events.stream().limit(limit).collect(Collectors.toList());
			
			// Convert to simpler format
			List<Map<String, Object>> eventList = events.stream()
				.map(e -> Map.of(
					"type", e.getType() != null ? e.getType() : "Normal",
					"reason", e.getReason() != null ? e.getReason() : "",
					"message", e.getMessage() != null ? e.getMessage() : "",
					"count", e.getCount() != null ? e.getCount() : 1,
					"firstTimestamp", e.getFirstTimestamp() != null ? e.getFirstTimestamp() : "",
					"lastTimestamp", e.getLastTimestamp() != null ? e.getLastTimestamp() : "",
					"involvedObject", e.getInvolvedObject() != null ? Map.of(
						"kind", e.getInvolvedObject().getKind(),
						"name", e.getInvolvedObject().getName()
					) : Map.of()
				))
				.collect(Collectors.toList());
			Log.info(MessageFormat.format("Retrieved {0} events", eventList.size()));
			
			
			return Map.of(
				"namespace", namespace,
				"eventCount", eventList.size(),
				"events", eventList
			);
			
		} catch (Exception e) {
			Log.error("Error getting events", e);
			return Map.of("error", e.getMessage());
		}
	}
}


