# Gemma Model Server Deployment

Deploy Google Gemma models locally in your Kubernetes cluster using vLLM for OpenAI-compatible inference. Multiple model sizes are available as separate deployments.

## Prerequisites

1. **Hugging Face Account & Token**
   - Create account: https://huggingface.co
   - Accept Gemma license(s): e.g. https://huggingface.co/google/gemma-3-1b-it, https://huggingface.co/google/gemma-2-9b-it
   - Create Read token: https://huggingface.co/settings/tokens

2. **GKE Autopilot Cluster with GPU support** (L4 recommended for 1B and 9B)

## Quick Start

```bash
# 1. Create namespace and Hugging Face secret
kubectl create namespace gemma-system --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic huggingface-secret \
  --from-literal=token=YOUR_HF_TOKEN \
  -n gemma-system

# 2. Create PVCs for model cache (each deployment uses its own PVC)
kubectl apply -f deployment/gemma/pvcs.yaml

# 3. Deploy Gemma 1B and/or 9B (each uses one L4 GPU)
kubectl apply -f deployment/gemma/gemma-1b-deployment.yaml
kubectl apply -f deployment/gemma/gemma-9b-deployment.yaml

# 4. Watch logs (first run: 5–10 min to download and load model)
kubectl logs -f -n gemma-system -l app=gemma-1b-server
kubectl logs -f -n gemma-system -l app=gemma-9b-server
```

## What Gets Created

| Resource | Description |
|----------|-------------|
| **Namespace** | `gemma-system` |
| **PVCs** | `gemma-1b-model-cache` (50Gi), `gemma-9b-model-cache` (60Gi), optional `gemma-27b-model-cache` (150Gi) |
| **Deployments** | `gemma-1b-server` (Gemma 3 1B), `gemma-9b-server` (Gemma 2 9B); optional `gemma-27b-server` (Gemma 3 27B, requires A100 or quantization) |
| **Services** | `gemma-1b-server`, `gemma-9b-server` (ClusterIP:8000) |
| **GPU** | Autopilot provisions L4 (or T4/A100) per deployment |

## Architecture

```
┌─────────────────────┐
│ kubernetes-agent    │
│  (AI Analysis)      │
└──────────┬──────────┘
           │
           ├──────────────────┬──────────────────┐
           ▼                  ▼                  ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ gemma-1b-server  │ │ gemma-9b-server  │ │ (optional)       │
│ Gemma 3 1B       │ │ Gemma 2 9B       │ │ gemma-27b-server │
│ vLLM, 1× L4      │ │ vLLM, 1× L4      │ │ 27B / A100       │
└──────────────────┘ └──────────────────┘ └──────────────────┘
```

## GKE Autopilot GPU Support

This deployment is optimized for **GKE Autopilot** with automatic GPU provisioning:

- ✅ GPU nodes are provisioned automatically when the pod is created
- ✅ Drivers are pre-installed (no manual setup)
- ✅ Supported GPUs: T4, L4, A100 (40GB/80GB), H100
- ✅ First GPU node takes 5-10 minutes to provision

See: https://cloud.google.com/kubernetes-engine/docs/how-to/autopilot-gpus

## Configuration

### Model Selection

Deployments are split by model size; each has its own manifest and PVC:

| Deployment | Model | File | GPU | Notes |
|------------|-------|------|-----|-------|
| **gemma-1b-server** | google/gemma-3-1b-it | gemma-1b-deployment.yaml | 1× L4 | ✅ Default for small footprint |
| **gemma-9b-server** | google/gemma-2-9b-it | gemma-9b-deployment.yaml | 1× L4 | max_model_len 1024, gpu_memory_utilization 0.95 |
| **gemma-27b-server** | google/gemma-3-27b-it | gemma-27b-deployment.yaml | 1× L4 (quantized) or A100 | 8-bit quantization; may OOM on L4 |

The agent uses one vLLM endpoint at a time via `VLLM_API_BASE` and `VLLM_MODEL`. For comparison tests, use `test-three-models.sh` (runs Gemini, then 1B, then 9B sequentially).

### Supported Models

| Model | Parameters | GPU Memory | Autopilot GPU | Deployment |
|-------|-----------|------------|---------------|------------|
| gemma-3-1b-it | 1B | ~4GB | T4, L4 | gemma-1b-server |
| gemma-2-9b-it | 9B | ~18–20GB | L4 (max_model_len 1024) | gemma-9b-server |
| gemma-3-27b-it | 27B | ~50GB+ | A100 or L4 (quantized, tight) | gemma-27b-server |
| gemma-1.1-2b-it | 2B | ~6GB | T4, L4 | Add a custom deployment if needed |

### GPU Memory Tuning

```yaml
- name: GPU_MEMORY_UTILIZATION
  value: "0.9"  # Use 90% of GPU memory (reduce if OOM)
```

### Context Length

```yaml
- name: MAX_MODEL_LEN
  value: "4096"  # Maximum context length in tokens (T4 GPU limit)
```

**Note**: T4 GPU has 16GB memory. With Gemma 1.1-2b-it:
- **4096 tokens**: Fits comfortably with headroom
- **8192 tokens**: May cause OOM on T4 (use L4/A100 instead)

## Testing

### Wait for Pod to be Ready

```bash
# Check status (wait for 1/1 READY)
kubectl get pods -n gemma-system -w

# 1B: ~2–3 min to load; 9B: ~5–10 min (model + compile)
kubectl logs -f -n gemma-system -l app=gemma-1b-server
kubectl logs -f -n gemma-system -l app=gemma-9b-server
```

When the vLLM server is ready, `GET /v1/models` returns 200. The **test-models.sh** script in the repo root waits for this before running the agent (unless you pass `--no-wait-vllm`).

### Multi-Model Comparison Test

From the `kubernetes-agent` directory:

```bash
./test-models.sh                          # interactive: choose models
./test-models.sh gemini gemma-1b          # Gemini + Gemma 1B
./test-models.sh -m gemini,gemma-1b,gemma-9b
```

The script port-forwards to the chosen Gemma service(s), waits for each vLLM server to be ready, runs the agent once per model, and prints a comparison.

### Test with curl

**Step 1: Port-forward** (use the service for the model you deployed):
```bash
kubectl port-forward -n gemma-system svc/gemma-1b-server 8000:8000
# or: svc/gemma-9b-server
```

**Step 2: Test in another terminal**

**List models:**
```bash
curl http://localhost:8000/v1/models
```

**Simple chat (1B):**
```bash
curl http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-3-1b-it",
    "messages": [{"role": "user", "content": "Say hello in one sentence"}],
    "max_tokens": 50
  }' | jq
```

**Simple chat (9B):** use `"model": "gemma-2-9b-it"` in the same request.

**Full example:**
```bash
curl http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-3-1b-it",
    "messages": [
      {"role": "system", "content": "You are a helpful Kubernetes expert"},
      {"role": "user", "content": "Explain what a Pod is"}
    ],
    "max_tokens": 200,
    "temperature": 0.7
  }' | jq '.choices[0].message.content'
```

### Test with Web UI

vLLM provides an OpenAPI/Swagger interface:

```bash
# Port-forward (if not already running)
kubectl port-forward -n gemma-system svc/gemma-1b-server 8000:8000

# Open in browser
open http://localhost:8000/docs
```

The web interface lets you:
- Browse all API endpoints
- Test requests interactively  
- View request/response schemas
- Try different parameters

### Test from Inside Cluster

From another pod in the cluster (no port-forward needed):

```bash
kubectl run -it --rm test-gemma --image=curlimages/curl --restart=Never -- \
  curl -s http://gemma-1b-server.gemma-system.svc.cluster.local:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma-3-1b-it","messages":[{"role":"user","content":"Hi"}],"max_tokens":20}'
```

### Example Output

```json
{
  "id": "cmpl-xxxxx",
  "object": "chat.completion",
  "created": 1705500000,
  "model": "gemma-1.1-2b-it",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you today?"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 9,
    "total_tokens": 19
  }
}
```

## Model Caching

The deployment uses a **PersistentVolumeClaim (PVC)** to cache the downloaded model:

- **First startup**: Downloads model from Hugging Face (~5-10 minutes)
- **Subsequent restarts**: Uses cached model from PVC (< 1 minute)
- **Storage**: Per-deployment PVCs (see table above)

To check PVC status:
```bash
kubectl get pvc -n gemma-system
kubectl describe pvc gemma-1b-model-cache -n gemma-system
```

To clear cache and re-download:
```bash
kubectl delete pvc gemma-1b-model-cache -n gemma-system
kubectl apply -f deployment/gemma/gemma-1b-deployment.yaml
```

## Tool Calling Support

The Gemma deployment is configured with native OpenAI-compatible function calling support:

- ✅ **Auto Tool Choice**: Enabled via `--enable-auto-tool-choice` flag
- ✅ **Tool Call Parser**: Set to `pythonic` for Gemma 3 models
- ✅ **OpenAI-Compatible API**: Supports `tools` parameter and `tool_choice` field

This enables the Gemma model to:
- Accept function/tool definitions via the OpenAI API `tools` parameter
- Automatically decide when to call tools based on the `tool_choice` field (`auto`, `required`, `none`)
- Return tool calls in the standard OpenAI format via `tool_calls` in the response

**Example Request:**
```json
{
  "model": "gemma-3-1b-it",
  "messages": [{"role": "user", "content": "Get pod logs"}],
  "tools": [{
    "type": "function",
    "function": {
      "name": "get_pod_logs",
      "description": "Get logs from a Kubernetes pod",
      "parameters": {
        "type": "object",
        "properties": {
          "namespace": {"type": "string"},
          "podName": {"type": "string"}
        }
      }
    }
  }],
  "tool_choice": "auto"
}
```

## Integration with Kubernetes Agent

To configure the kubernetes-agent to use local Gemma:

```yaml
env:
  - name: GEMINI_MODEL
    value: "gemma-3-1b-it"
  - name: VLLM_API_BASE
    value: "http://gemma-1b-server.gemma-system.svc.cluster.local:8000"
  - name: VLLM_API_KEY
    value: "not-needed"  # Not required for local deployment
```

**Note**: The kubernetes-agent's `VllmGemma` implementation uses OpenAI-compatible function calling, which works seamlessly with this configuration.

## Monitoring

```bash
# Watch logs
kubectl logs -f -n gemma-system deployment/gemma-1b-server

# Check GPU usage (if node is provisioned)
kubectl exec -it -n gemma-system deployment/gemma-1b-server -- nvidia-smi

# Get pod status
kubectl get pods -n gemma-system -o wide
```

## Troubleshooting

### 401 Unauthorized / Gated Repo Error

**Error**: `Access to model google/gemma-1.1-2b-it is restricted`

**Cause**: Gemma is a gated model on Hugging Face

**Solution**:
1. Accept the license: https://huggingface.co/google/gemma-1.1-2b-it
2. Create HF token (Read permission): https://huggingface.co/settings/tokens
3. Create/update secret:
```bash
kubectl create secret generic huggingface-secret \
  --from-literal=token=YOUR_HF_TOKEN \
  -n gemma-system --dry-run=client -o yaml | kubectl apply -f -
```
4. Restart deployment:
```bash
kubectl rollout restart deployment/gemma-1b-server -n gemma-system
```

### PVC Pending / Not Bound

**Cause**: Autopilot is provisioning storage

**Check**:
```bash
kubectl get pvc -n gemma-system
kubectl describe pvc gemma-1b-model-cache -n gemma-system
```

**Wait**: Usually binds within 30-60 seconds

### Pod Stuck in Pending

**Cause**: Autopilot is provisioning a GPU node (first time takes 5-10 minutes)

```bash
# Check events
kubectl describe pod -n gemma-system -l app=gemma-1b-server

# Watch for node creation
kubectl get nodes -w
```

### Out of Memory (OOMKilled)

**Solutions**:
1. **Use Gemma 1.1**: `gemma-1.1-2b-it` instead of `gemma-2-2b-it` (better T4 support)
2. Reduce GPU memory utilization: `0.7` instead of `0.9`
3. Reduce context length: `2048` instead of `4096`
4. Upgrade GPU: Use L4 or A100 for larger models/contexts

### Slow Startup

**Cause**: First run downloads the model from Hugging Face (5-10 minutes)

```bash
# Watch logs to see download progress
kubectl logs -f -n gemma-system deployment/gemma-1b-server
```

## Resource Costs

### Autopilot Pricing (approximate)

**T4 GPU workload** (gemma-1.1-2b-it):
- CPU: 2 vCPU @ ~$0.04/hour
- Memory: 8GB @ ~$0.01/hour  
- GPU: 1 T4 @ ~$0.35/hour
- **Total**: ~$0.40/hour (~$9.60/day or ~$288/month if running 24/7)

**Cost optimization**:
- Autopilot only charges while the pod is running
- Scale to zero when not needed
- Use smaller models when possible

## Cleanup

```bash
# Delete Gemma deployment
kubectl delete -k kubernetes-agent/deployment/gemma/

# Or delete namespace
kubectl delete namespace gemma-system
```

## GPU Compatibility Notes

### T4 GPU (Compute Capability 7.5)
- ✅ **Works**: Gemma 1.1 models with FlashInfer backend
- ❌ **Doesn't work**: Gemma 2 models (require FA2, compute 8.0+)
- **Max context**: 4096 tokens (16GB VRAM limit)
- **Cost**: ~$0.35/hour

### L4 GPU (Compute Capability 8.9)
- ✅ **Works**: All Gemma models with FlashAttention 2
- **Max context**: 8192+ tokens (24GB VRAM)
- **Performance**: 2-3x faster than T4
- **Cost**: ~$0.70/hour

### A100/H100 GPUs
- ✅ **Best performance**: All models, full features
- **Max context**: 16K+ tokens
- **Cost**: $2.95-$4.89/hour

## References

- [vLLM Documentation](https://docs.vllm.ai/)
- [Gemma 1.1 Models](https://huggingface.co/google/gemma-1.1-2b-it)
- [Gemma 2 Models](https://huggingface.co/google/gemma-2-2b-it)
- [GKE Autopilot GPUs](https://cloud.google.com/kubernetes-engine/docs/how-to/autopilot-gpus)
