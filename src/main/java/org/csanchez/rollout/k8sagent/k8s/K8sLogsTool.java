package org.csanchez.rollout.k8sagent.k8s;

import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.MessageFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool for retrieving Kubernetes pod logs
 */
@ApplicationScoped
public class K8sLogsTool {
	
	@Inject
	KubernetesClient k8sClient;
	
	private static final int DEFAULT_TAIL_LINES = 100;
	
	@Tool("Get logs from a Kubernetes pod")
	public Map<String, Object> getLogs(String namespace, String podName, String containerName, Boolean previous, Integer tailLines) {
		Log.info("=== Executing Tool: getLogs ===");
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		
		boolean getPrevious = previous != null && previous;
		int lines = (tailLines != null && tailLines > 0) ? tailLines : DEFAULT_TAIL_LINES;
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		Log.info(MessageFormat.format("Getting logs for pod: {0}/{1}, previous: {2}", namespace, podName, previous));
		
		
		try {
			Pod pod = k8sClient.pods()
				.inNamespace(namespace)
				.withName(podName)
				.get();
			
			if (pod == null) {
				return Map.of("error", "Pod not found: " + namespace + "/" + podName);
			}
			
			// If a specific container is requested, get logs only for that container
			if (containerName != null && !containerName.isEmpty()) {
				try {
					String logs = k8sClient.pods()
						.inNamespace(namespace)
						.withName(podName)
						.inContainer(containerName)
						.tailingLines(lines)
						.getLog(getPrevious);
					
					if (logs == null) {
						logs = "(no logs available)";
					}
					Log.info(MessageFormat.format("Retrieved {0} characters of logs", logs.length()));
					
					
					return Map.of(
						"namespace", namespace,
						"podName", podName,
						"container", containerName,
						"previous", getPrevious,
						"logs", logs
					);
				} catch (Exception e) {
					Log.error("Error getting logs", e);
					return Map.of("error", e.getMessage());
				}
			}
			
			// Otherwise, get logs from all containers
			List<Map<String, Object>> containerLogs = new ArrayList<>();
			List<ContainerStatus> containerStatuses = pod.getStatus().getContainerStatuses();
			if (containerStatuses != null) {
				for (ContainerStatus cs : containerStatuses) {
					String csName = cs.getName();
					
					try {
						String logs = k8sClient.pods()
							.inNamespace(namespace)
							.withName(podName)
							.inContainer(csName)
							.tailingLines(lines)
							.getLog(getPrevious);
						
						Map<String, Object> containerLog = new HashMap<>();
						containerLog.put("containerName", csName);
						containerLog.put("restartCount", cs.getRestartCount());
						containerLog.put("logs", logs != null ? logs : "No logs available");
						
						// Note about previous logs if container has restarted
						if (getPrevious && cs.getRestartCount() > 0) {
							containerLog.put("note", "Container has restarted " + cs.getRestartCount() + " times. Use kubectl logs --previous for terminated container logs.");
						}
						
						// Analyze logs for common error patterns
						Map<String, Object> analysis = analyzeLogs(logs);
						containerLog.put("analysis", analysis);
						
						containerLogs.add(containerLog);
						
					} catch (Exception e) {
						Log.warn(MessageFormat.format("Error getting logs for container {0}: {1}", containerName, e.getMessage()));
						containerLogs.add(Map.of(
							"containerName", containerName,
							"error", e.getMessage()
						));
					}
				}
			}
			Log.info(MessageFormat.format("Retrieved logs from {0} containers", containerLogs.size()));
			
			
			return Map.of(
				"namespace", namespace,
				"podName", podName,
				"containers", containerLogs
			);
			
		} catch (Exception e) {
			Log.error("Error getting pod logs", e);
			return Map.of("error", e.getMessage());
		}
	}
	
	/**
	 * Analyze logs for common error patterns
	 */
	private Map<String, Object> analyzeLogs(String logs) {
		if (logs == null || logs.isEmpty()) {
			return Map.of("hasErrors", false);
		}
		
		String lowerLogs = logs.toLowerCase();
		boolean hasErrors = lowerLogs.contains("error") || 
			lowerLogs.contains("exception") ||
			lowerLogs.contains("fatal") ||
			lowerLogs.contains("panic");
		
		boolean hasWarnings = lowerLogs.contains("warn");
		
		// Count error lines
		long errorCount = logs.lines()
			.filter(line -> {
				String lower = line.toLowerCase();
				return lower.contains("error") || lower.contains("exception");
			})
			.count();
		
		// Detect OOMKilled
		boolean oomDetected = lowerLogs.contains("out of memory") ||
			lowerLogs.contains("oomkilled");
		
		// Detect connection issues
		boolean connectionIssues = lowerLogs.contains("connection refused") ||
			lowerLogs.contains("connection timeout") ||
			lowerLogs.contains("unable to connect");
		
		Map<String, Object> analysis = new HashMap<>();
		analysis.put("hasErrors", hasErrors);
		analysis.put("hasWarnings", hasWarnings);
		analysis.put("errorCount", errorCount);
		analysis.put("oomDetected", oomDetected);
		analysis.put("connectionIssues", connectionIssues);
		
		return analysis;
	}
}


