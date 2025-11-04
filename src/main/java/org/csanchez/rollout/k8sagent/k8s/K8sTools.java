package org.csanchez.rollout.k8sagent.k8s;

import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Kubernetes tools for LangChain4j
 */
@ApplicationScoped
public class K8sTools {
    
    @Inject
    KubernetesClient k8sClient;
    
    /**
     * Debug a Kubernetes pod
     */
    @Tool("Debug a Kubernetes pod to get detailed information about its status and conditions")
    public Map<String, Object> debugPod(
            String namespace,
            String podName
    ) {
        Log.info("=== Executing Tool: debugPod ===");
        
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
            debugInfo.put("labels", pod.getMetadata().getLabels());
            
            List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
            if (owners != null && !owners.isEmpty()) {
                List<Map<String, String>> ownerInfo = owners.stream()
                    .map(o -> Map.of("kind", o.getKind(), "name", o.getName()))
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
    
    /**
     * Get Kubernetes events
     */
    @Tool("Get Kubernetes events for a namespace or specific pod")
    public Map<String, Object> getEvents(
            String namespace,
            String podName,
            Integer limit
    ) {
        Log.info("=== Executing Tool: getEvents ===");
        
        if (namespace == null) {
            return Map.of("error", "namespace is required");
        }
        
        int eventLimit = (limit != null && limit > 0) ? limit : 50;
        Log.info(MessageFormat.format("Getting events for namespace: {0}, pod: {1}, limit: {2}", namespace, podName, eventLimit));
        
        try {
            List<Event> events = k8sClient.v1().events()
                .inNamespace(namespace)
                .list()
                .getItems();
            
            if (podName != null && !podName.isEmpty()) {
                events = events.stream()
                    .filter(e -> e.getInvolvedObject() != null && 
                                podName.equals(e.getInvolvedObject().getName()))
                    .collect(Collectors.toList());
            }
            
            events.sort((e1, e2) -> {
                String t1 = e1.getLastTimestamp() != null ? e1.getLastTimestamp() : 
                            e1.getMetadata().getCreationTimestamp();
                String t2 = e2.getLastTimestamp() != null ? e2.getLastTimestamp() : 
                            e2.getMetadata().getCreationTimestamp();
                return t2.compareTo(t1);
            });
            
            events = events.stream().limit(eventLimit).collect(Collectors.toList());
            
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
    
    /**
     * Get pod logs
     */
    @Tool("Get logs from a Kubernetes pod")
    public Map<String, Object> getLogs(
            String namespace,
            String podName,
            String containerName,
            Boolean previous,
            Integer tailLines
    ) {
        Log.info("=== Executing Tool: getLogs ===");
        
        if (namespace == null || podName == null) {
            return Map.of("error", "namespace and podName are required");
        }
        
        boolean getPrevious = previous != null && previous;
        int lines = (tailLines != null && tailLines > 0) ? tailLines : 100;
        Log.info(MessageFormat.format("Getting logs for pod: {0}/{1}, container: {2}, previous: {3}, lines: {4}",
                namespace, podName, containerName, getPrevious, lines));
        
        
        try {
            var podResource = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName);
            
            String logs;
            if (containerName != null && !containerName.isEmpty()) {
                logs = podResource.inContainer(containerName).tailingLines(lines).getLog(getPrevious);
            } else {
                logs = podResource.tailingLines(lines).getLog(getPrevious);
            }
            
            if (logs == null) {
                logs = "(no logs available)";
            }
            Log.info(MessageFormat.format("Retrieved {0} characters of logs", logs.length()));
            
            
            return Map.of(
                "namespace", namespace,
                "podName", podName,
                "container", containerName != null ? containerName : "default",
                "previous", getPrevious,
                "logs", logs
            );
            
        } catch (Exception e) {
            Log.error("Error getting logs", e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * Get pod metrics
     */
    @Tool("Get resource metrics for a Kubernetes pod")
    public Map<String, Object> getMetrics(
            String namespace,
            String podName
    ) {
        Log.info("=== Executing Tool: getMetrics ===");
        
        if (namespace == null || podName == null) {
            return Map.of("error", "namespace and podName are required");
        }
        Log.info(MessageFormat.format("Getting metrics for pod: {0}/{1}", namespace, podName));
        
        
        try {
            PodMetrics metrics = k8sClient.top().pods()
                .inNamespace(namespace)
                .withName(podName)
                .metric();
            
            if (metrics == null) {
                return Map.of("error", "Metrics not available (metrics-server might not be installed)");
            }
            
            List<Map<String, Object>> containerMetrics = metrics.getContainers().stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", c.getName());
                    m.put("cpu", c.getUsage().get("cpu").toString());
                    m.put("memory", c.getUsage().get("memory").toString());
                    return m;
                })
                .collect(Collectors.toList());
            Log.info(MessageFormat.format("Retrieved metrics for {0} containers", containerMetrics.size()));
            
            
            return Map.of(
                "namespace", namespace,
                "podName", podName,
                "timestamp", metrics.getTimestamp(),
                "containers", containerMetrics
            );
            
        } catch (Exception e) {
            Log.error("Error getting metrics", e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * Inspect Kubernetes resources
     */
    @Tool("Inspect Kubernetes resources in a namespace")
    public Map<String, Object> inspectResources(
            String namespace,
            String resourceType,
            String resourceName
    ) {
        Log.info("=== Executing Tool: inspectResources ===");
        
        if (namespace == null) {
            return Map.of("error", "namespace is required");
        }
        Log.info(MessageFormat.format("Inspecting resources in namespace: {0}, type: {1}, name: {2}",
            namespace, resourceType, resourceName));
        
        
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("namespace", namespace);
            
            if (resourceType == null || "deployment".equalsIgnoreCase(resourceType)) {
                List<Deployment> deployments = k8sClient.apps().deployments()
                    .inNamespace(namespace)
                    .list()
                    .getItems();
                
                if (resourceName != null) {
                    deployments = deployments.stream()
                        .filter(d -> resourceName.equals(d.getMetadata().getName()))
                        .collect(Collectors.toList());
                }
                
                List<Map<String, Object>> deploymentInfo = deployments.stream()
                    .map(d -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", d.getMetadata().getName());
                        info.put("replicas", d.getStatus().getReplicas() != null ? d.getStatus().getReplicas() : 0);
                        info.put("availableReplicas", d.getStatus().getAvailableReplicas() != null ? d.getStatus().getAvailableReplicas() : 0);
                        info.put("readyReplicas", d.getStatus().getReadyReplicas() != null ? d.getStatus().getReadyReplicas() : 0);
                        return info;
                    })
                    .collect(Collectors.toList());
                
                result.put("deployments", deploymentInfo);
            }
            
            if (resourceType == null || "service".equalsIgnoreCase(resourceType)) {
                List<Service> services = k8sClient.services()
                    .inNamespace(namespace)
                    .list()
                    .getItems();
                
                if (resourceName != null) {
                    services = services.stream()
                        .filter(s -> resourceName.equals(s.getMetadata().getName()))
                        .collect(Collectors.toList());
                }
                
                List<Map<String, Object>> serviceInfo = services.stream()
                    .map(s -> Map.of(
                        "name", s.getMetadata().getName(),
                        "type", s.getSpec().getType(),
                        "clusterIP", s.getSpec().getClusterIP() != null ? s.getSpec().getClusterIP() : "",
                        "ports", s.getSpec().getPorts().stream()
                            .map(p -> p.getPort() + ":" + p.getTargetPort())
                            .collect(Collectors.toList())
                    ))
                    .collect(Collectors.toList());
                
                result.put("services", serviceInfo);
            }
            
            Log.info("Successfully inspected resources");
            return result;
            
        } catch (Exception e) {
            Log.error("Error inspecting resources", e);
            return Map.of("error", e.getMessage());
        }
    }
}

