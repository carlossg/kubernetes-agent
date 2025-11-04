package org.csanchez.rollout.k8sagent.k8s;

import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool for debugging Kubernetes pods
 */
@ApplicationScoped
public class K8sDebugTool {
	
	@Inject
	KubernetesClient k8sClient;
	
	@Tool("Debug a Kubernetes pod to get detailed information about its status and conditions")
	public Map<String, Object> debugPod(String namespace, String podName) {
		Log.info("=== Executing Tool: debug_kubernetes_pod ===");
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		Log.info(MessageFormat.format("Debugging pod: {0}/{1}", namespace, podName));
		
		
		try {
			Pod pod = k8sClient.pods()
				.inNamespace(namespace)
				.withName(podName)
				.get();
			
			if (pod == null) {
				return Map.of("error", MessageFormat.format("Pod not found: {0}/{1}", namespace, podName));
			}
			
			PodStatus status = pod.getStatus();
			
			// Build comprehensive debug info
			Map<String, Object> debugInfo = new HashMap<>();
			debugInfo.put("podName", podName);
			debugInfo.put("namespace", namespace);
			debugInfo.put("phase", status.getPhase());
			debugInfo.put("reason", status.getReason());
			debugInfo.put("message", status.getMessage());
			debugInfo.put("hostIP", status.getHostIP());
			debugInfo.put("podIP", status.getPodIP());
			debugInfo.put("startTime", status.getStartTime());
			
			// Pod conditions
			List<Map<String, Object>> conditions = status.getConditions().stream()
				.map(c -> {
					Map<String, Object> condition = new HashMap<>();
					condition.put("type", c.getType());
					condition.put("status", c.getStatus());
					condition.put("reason", c.getReason() != null ? c.getReason() : "");
					condition.put("message", c.getMessage() != null ? c.getMessage() : "");
					condition.put("lastTransitionTime", c.getLastTransitionTime() != null ? c.getLastTransitionTime() : "");
					return condition;
				})
				.collect(Collectors.toList());
			debugInfo.put("conditions", conditions);
			
			// Container statuses
			List<Map<String, Object>> containerStatuses = new ArrayList<>();
			if (status.getContainerStatuses() != null) {
				for (ContainerStatus cs : status.getContainerStatuses()) {
					Map<String, Object> containerInfo = new HashMap<>();
					containerInfo.put("name", cs.getName());
					containerInfo.put("ready", cs.getReady());
					containerInfo.put("restartCount", cs.getRestartCount());
					containerInfo.put("image", cs.getImage());
					
					// Container state
					ContainerState state = cs.getState();
					if (state.getRunning() != null) {
						containerInfo.put("state", "Running");
						containerInfo.put("startedAt", state.getRunning().getStartedAt());
					} else if (state.getWaiting() != null) {
						containerInfo.put("state", "Waiting");
						containerInfo.put("reason", state.getWaiting().getReason());
						containerInfo.put("message", state.getWaiting().getMessage());
					} else if (state.getTerminated() != null) {
						containerInfo.put("state", "Terminated");
						containerInfo.put("reason", state.getTerminated().getReason());
						containerInfo.put("message", state.getTerminated().getMessage());
						containerInfo.put("exitCode", state.getTerminated().getExitCode());
					}
					
					// Last terminated state (for crash loop detection)
					if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
						ContainerStateTerminated last = cs.getLastState().getTerminated();
						containerInfo.put("lastTerminated", Map.of(
							"reason", last.getReason() != null ? last.getReason() : "",
							"exitCode", last.getExitCode(),
							"message", last.getMessage() != null ? last.getMessage() : ""
						));
					}
					
					containerStatuses.add(containerInfo);
				}
			}
			debugInfo.put("containerStatuses", containerStatuses);
			
			// Labels
			debugInfo.put("labels", pod.getMetadata().getLabels());
			
			// Owner references (Deployment, StatefulSet, etc.)
			List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
			if (owners != null && !owners.isEmpty()) {
				List<Map<String, String>> ownerInfo = owners.stream()
					.map(o -> Map.of(
						"kind", o.getKind(),
						"name", o.getName()
					))
					.collect(Collectors.toList());
				debugInfo.put("owners", ownerInfo);
			}
			Log.info(MessageFormat.format("Successfully retrieved debug info for pod: {0}/{1}", namespace, podName));
			return debugInfo;
			
			
		} catch (Exception e) {
			Log.error("Error debugging pod", e);
			return Map.of("error", e.getMessage());
		}
	}
}


