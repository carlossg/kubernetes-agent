# Gemma Model Server Deployment

Deploy Google's Gemma 2B model locally in your Kubernetes cluster using vLLM for OpenAI-compatible inference.

## Prerequisites

1. **Hugging Face Account & Token**
   - Create account: https://huggingface.co
   - Accept Gemma license: https://huggingface.co/google/gemma-1.1-2b-it
   - Create Read token: https://huggingface.co/settings/tokens

2. **GKE Autopilot Cluster with GPU support**

## Quick Start

```bash
# 1. Create Hugging Face secret with your token
kubectl create secret generic huggingface-secret \
  --from-literal=token=YOUR_HF_TOKEN \
  -n gemma-system

# 2. Deploy Gemma (includes PVC for model caching)
kubectl apply -k kubernetes-agent/deployment/gemma/

# 3. Watch logs (first time takes 5-10 min to download model)
kubectl logs -f -n gemma-system -l app=gemma-server

# 4. After first download, model is cached in PVC (faster restarts)
```

## What Gets Created

- ✅ **Namespace**: `gemma-system`
- ✅ **PVC**: `gemma-model-cache` (20GB) - stores downloaded model
- ✅ **Deployment**: `gemma-server` - vLLM with Gemma 2B
- ✅ **Service**: `gemma-server` (ClusterIP:8000)
- ✅ **GPU Node**: Autopilot provisions T4 GPU automatically
## Architecture

```
┌─────────────────────┐
│ kubernetes-agent    │
│  (AI Analysis)      │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  gemma-server       │
│  (vLLM + Gemma 2B)  │
│  GPU: T4/L4/A100    │
│  (Autopilot)        │
└─────────────────────┘
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

Default: `google/gemma-1.1-2b-it` (2B parameters, ~6GB GPU memory)

**Why Gemma 1.1 instead of Gemma 2?**
- **T4 GPU Compatibility**: Gemma 2 requires FlashAttention 2 (compute capability 8.0+)
- **T4 has compute capability 7.5**: Only supports FlashInfer/XFORMERS backends
- **Gemma 1.1 works perfectly on T4**: Uses FlashInfer backend automatically
- **For L4/A100/H100**: You can use Gemma 2 models for better performance

To use a different model, edit `deployment.yaml`:

```yaml
env:
  - name: MODEL_NAME
    value: "google/gemma-2-2b-it"  # Requires L4/A100/H100 GPU
```

### Supported Models

| Model | Parameters | GPU Memory | Autopilot GPU | Notes |
|-------|-----------|------------|---------------|-------|
| gemma-1.1-2b-it | 2B | ~6GB | T4, L4, A100 | ✅ **Recommended for T4** |
| gemma-2-2b-it | 2B | ~6GB | L4, A100, H100 | Requires compute 8.0+ |
| gemma-2-9b-it | 9B | ~20GB | A100 (80GB) | Requires compute 8.0+ |
| gemma-2-27b-it | 27B | ~50GB | A100 (80GB), H100 | Requires compute 8.0+ |

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

# Takes 3-5 minutes for model to load
# Watch logs to see progress
kubectl logs -f -n gemma-system -l app=gemma-server
```

### Test with curl

**Step 1: Port-forward in one terminal:**
```bash
kubectl port-forward -n gemma-system svc/gemma-server 8000:8000
```

**Step 2: Test in another terminal:**

**Health check:**
```bash
curl http://localhost:8000/health
# Returns: {"status":"ok"}
```

**List models:**
```bash
curl http://localhost:8000/v1/models
```

**Simple chat:**
```bash
curl http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-1.1-2b-it",
    "messages": [{"role": "user", "content": "Say hello in one sentence"}],
    "max_tokens": 50
  }' | jq
```

**Full example:**
```bash
curl http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-1.1-2b-it",
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
kubectl port-forward -n gemma-system svc/gemma-server 8000:8000

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
  curl -s http://gemma-server.gemma-system.svc.cluster.local:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma-1.1-2b-it","messages":[{"role":"user","content":"Hi"}],"max_tokens":20}'
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
- **Storage**: 20GB PVC (sufficient for Gemma 2B)

To check PVC status:
```bash
kubectl get pvc -n gemma-system
kubectl describe pvc gemma-model-cache -n gemma-system
```

To clear cache and re-download:
```bash
kubectl delete pvc gemma-model-cache -n gemma-system
kubectl apply -k kubernetes-agent/deployment/gemma/
```

## Integration with Kubernetes Agent

To configure the kubernetes-agent to use local Gemma:

```yaml
env:
  - name: GEMINI_MODEL
    value: "gemma-1.1-2b-it"
  - name: GEMINI_API_BASE
    value: "http://gemma-server.gemma-system.svc.cluster.local:8000/v1"
  - name: GOOGLE_API_KEY
    value: "local-not-needed"  # Dummy value for local deployment
```

**Note**: Current ADK implementation may require additional configuration for custom endpoints.

## Monitoring

```bash
# Watch logs
kubectl logs -f -n gemma-system deployment/gemma-server

# Check GPU usage (if node is provisioned)
kubectl exec -it -n gemma-system deployment/gemma-server -- nvidia-smi

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
kubectl rollout restart deployment/gemma-server -n gemma-system
```

### PVC Pending / Not Bound

**Cause**: Autopilot is provisioning storage

**Check**:
```bash
kubectl get pvc -n gemma-system
kubectl describe pvc gemma-model-cache -n gemma-system
```

**Wait**: Usually binds within 30-60 seconds

### Pod Stuck in Pending

**Cause**: Autopilot is provisioning a GPU node (first time takes 5-10 minutes)

```bash
# Check events
kubectl describe pod -n gemma-system -l app=gemma-server

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
kubectl logs -f -n gemma-system deployment/gemma-server
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
