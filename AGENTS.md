# Testing Kubernetes Agent Live in Cluster

This guide explains how to verify the `kubernetes-agent` is working correctly when deployed to a Kubernetes cluster.

## Prerequisites

- `kubectl` configured with the correct context.
- `argo rollouts` plugin installed (`kubectl argo rollouts`).
- Agent deployed in the `argo-rollouts` namespace.
- `canary-demo` rollout deployed in the `default` namespace.

## Monitoring Agent Logs

To see the agent's internal analysis process, watch the logs in the `argo-rollouts` namespace:

```bash
kubectl logs -n argo-rollouts -l app=kubernetes-agent -f
```

Look for:
- `Starting analysis with model: ...`
- `Executing runAsync with session: ...`
- `Model ... completed: promote=true, confidence=...`

## Triggering a New Analysis

Trigger an analysis by updating the image of the `canary-demo` rollout. This will start a new Canary revision and invoke the agent for metric analysis.

You can alternate between these images:

```bash
# Set to red
kubectl argo rollouts set image canary-demo canary-demo=argoproj/rollouts-demo:red -n default

# Set to green
kubectl argo rollouts set image canary-demo canary-demo=argoproj/rollouts-demo:green -n default

# Set to blue
kubectl argo rollouts set image canary-demo canary-demo=argoproj/rollouts-demo:blue -n default

# Set to yellow
kubectl argo rollouts set image canary-demo canary-demo=argoproj/rollouts-demo:yellow -n default
```

## Verifying Rollout Status

Monitor the progress of the rollout and the status of the `AnalysisRun`:

```bash
kubectl argo rollouts get rollout canary-demo -n default
```

A successful analysis should show `✔ Successful` next to the `AnalysisRun`.

## Troubleshooting

### Session Not Found Errors
If logs show `java.lang.IllegalArgumentException: Session not found`, ensure you are running a version of the agent that includes the fix for unique session names. You can verify the image digest:

```bash
kubectl get pod -n argo-rollouts -l app=kubernetes-agent -o jsonpath='{.items[0].status.containerStatuses[0].imageID}'
```

### Connection Refused (Gemma)
If logs show `Connection refused` for `gemma-3-1b-it`, check if the `gemma-server` is running and reachable:

```bash
kubectl get pods -n gemma-system
kubectl get svc -n gemma-system
```

Verify that the `VLLM_API_BASE` environment variable in the agent deployment matches the internal service URL.
