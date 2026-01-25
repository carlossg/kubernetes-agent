# Kubernetes AI Agent

An autonomous AI agent for Kubernetes debugging and remediation, powered by Google's Agent Development Kit (ADK) and Gemini AI.

## Overview

The Kubernetes Agent is an intelligent system that:
- **Debugs** Kubernetes pods automatically
- **Analyzes** logs, events, and metrics
- **Identifies** root causes of issues
- **Creates** GitHub pull requests with fixes
- **Integrates** with Argo Rollouts for canary analysis

## Features

### Kubernetes Debugging Tools
- **Pod Debugging**: Analyze pod status, conditions, and container states
- **Events**: Retrieve and correlate cluster events
- **Logs**: Fetch and analyze container logs (including previous crashes)
- **Metrics**: Check resource usage and limits
- **Resources**: Inspect related deployments, services, and configmaps

### Remediation Capabilities
- **Git Operations**: Clone, branch, commit, push (using JGit library)
- **GitHub PRs**: Automatically create pull requests with:
	- Root cause analysis
	- Code fixes
	- Testing recommendations
	- Links to Kubernetes resources

### A2A Communication
- **REST API**: Expose analysis capabilities via HTTP
- **Integration**: Works with `rollouts-plugin-metric-ai` for canary analysis

## Architecture

```
Argo Rollouts Analysis
	↓
rollouts-plugin-metric-ai
	↓ (A2A HTTP)
Kubernetes Agent (ADK)
	├── K8s Tools (Fabric8 client)
	├── Git Operations (JGit)
	├── GitHub PR (GitHub API)
	└── AI Analysis (Gemini)
```

## Prerequisites

- Java 17+
- Maven 3.8+
- Kubernetes cluster
- Google API Key (Gemini)
- GitHub Personal Access Token

## Local Development

### 1. Build the project

```bash
cd kubernetes-agent
mvn clean package
```

### 2. Set environment variables

```bash
export GOOGLE_API_KEY="your-google-api-key"
export GITHUB_TOKEN="your-github-token"
```

### 3. Run locally (console mode)

```bash
java -jar target/kubernetes-agent-1.0.0.jar console
```

### 4. Run as server

```bash
java -jar target/kubernetes-agent-1.0.0.jar
# Server starts on port 8080
# Health check: http://localhost:8080/a2a/health
```

## Deployment to Kubernetes

### 1. Build Docker image

```bash
docker build -t csanchez/kubernetes-agent:latest .
docker push csanchez/kubernetes-agent:latest
```

### 2. Create secrets

```bash
# Copy template
cp deployment/secret.yaml.template deployment/secret.yaml

# Edit secret.yaml and add your keys
# Then apply:
kubectl apply -f deployment/secret.yaml
```

### 3. Deploy agent

```bash
# Update image in deployment/deployment.yaml if needed
kubectl apply -k deployment/
```

### 4. Verify deployment

```bash
# Check pods
kubectl get pods -n argo-rollouts | grep kubernetes-agent

# Check logs
kubectl logs -f deployment/kubernetes-agent -n argo-rollouts

# Test health endpoint
kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080
curl http://localhost:8080/a2a/health
```

### 5. Run tests

The `test-agent.sh` script supports both Kubernetes and local modes:

```bash
# Test agent running in Kubernetes (default)
./test-agent.sh k8s

# Test agent running locally on localhost:8080
./test-agent.sh local

# Use custom local URL
LOCAL_URL=http://localhost:9090 ./test-agent.sh local

# Use custom Kubernetes context
CONTEXT=my-k8s-context ./test-agent.sh k8s
```

The test script will:
1. ✅ Check health endpoint
2. ✅ Send a sample analysis request
3. ✅ Verify no errors in logs (K8s mode only)

## Usage

### Direct Console Mode

```bash
$ java -jar kubernetes-agent.jar console

You > Debug pod my-app-canary in namespace production

Agent > Analyzing pod my-app-canary in namespace production...
[Agent gathers debug info, logs, events...]

Root Cause: Container crashloop due to OOMKilled - memory limit too low

Recommendation:
1. Increase memory limit from 256Mi to 512Mi
2. Add resource requests to prevent overcommitment
3. Review memory usage patterns in logs
```

### A2A Integration

The agent exposes a REST API for other systems to use:

**Endpoint**: `POST /a2a/analyze`

**Request**:
```json
{
	"userId": "argo-rollouts",
	"prompt": "Analyze canary deployment issue. Namespace: rollouts-test-system, Pod: canary-demo-xyz",
	"context": {
		"namespace": "rollouts-test-system",
		"podName": "canary-demo-xyz",
		"stableLogs": "...",
		"canaryLogs": "..."
	}
}
```

**Response**:
```json
{
	"analysis": "Detailed analysis text...",
	"rootCause": "Identified root cause",
	"remediation": "Suggested fixes",
	"prLink": "https://github.com/owner/repo/pull/123",
	"promote": false,
	"confidence": 85
}
```

## Integration with Argo Rollouts

### 1. Configure Analysis Template

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
	name: canary-analysis-with-agent
spec:
	metrics:
		- name: ai-analysis
			provider:
				plugin:
					ai-metric:
						# Use agent mode
						analysisMode: agent
						namespace: "{{args.namespace}}"
						podName: "{{args.canary-pod}}"
						# Fallback to default mode
						stablePodLabel: app=rollouts-demo,revision=stable
						canaryPodLabel: app=rollouts-demo,revision=canary
						model: gemini-2.0-flash-exp
```

### 2. The plugin will automatically:
1. Check if agent is healthy
2. Send analysis request with logs
3. Receive intelligent analysis
4. Get PR link if fix was created
5. Decide to promote or abort canary

## Configuration

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `GOOGLE_API_KEY` | Conditional | Google Gemini API key (required when using Gemini models) |
| `GITHUB_TOKEN` | Yes | GitHub personal access token |
| `GIT_USERNAME` | No | Git commit username (default: "kubernetes-agent") |
| `GIT_EMAIL` | No | Git commit email (default: "agent@example.com") |
| `GEMINI_MODEL` | Yes | Model to use (e.g., "gemini-2.5-flash" or "gemma-3-1b-it") |
| `VLLM_API_BASE` | Conditional | vLLM server base URL (required when using gemma-* models) |
| `VLLM_API_KEY` | No | vLLM API key (default: "not-needed") |
| `K8S_AGENT_URL` | No | Agent URL for plugin (default: http://kubernetes-agent.argo-rollouts.svc.cluster.local:8080) |

### Model Configuration

The agent supports two modes:

#### 1. Google Gemini API (Cloud)
```yaml
env:
  - name: GOOGLE_API_KEY
    value: "your-gemini-api-key"
  - name: GEMINI_MODEL
    value: "gemini-2.5-flash"
```

#### 2. Local vLLM Gemma (Self-hosted)
```yaml
env:
  - name: GEMINI_MODEL
    value: "gemma-3-1b-it"  # Any model name starting with "gemma-"
  - name: VLLM_API_BASE
    value: "http://gemma-server.gemma-system.svc.cluster.local:8000"
  - name: VLLM_API_KEY
    value: "not-needed"  # Optional
```

> **Note**: When `GEMINI_MODEL` starts with `gemma-`, the agent automatically uses the vLLM endpoint specified in `VLLM_API_BASE`. The Gemma model must be deployed separately (see [deployment/gemma/README.md](deployment/gemma/README.md)).

### Resource Limits

Recommended settings for production:

```yaml
resources:
	requests:
		memory: "512Mi"
		cpu: "250m"
	limits:
		memory: "2Gi"
		cpu: "1000m"
```

## Troubleshooting

### Agent not starting

```bash
# Check logs
kubectl logs deployment/kubernetes-agent -n argo-rollouts

# Common issues:
# 1. Missing API keys - check secrets
# 2. Invalid service account - check RBAC
# 3. Out of memory - increase limits
```

### Health check failing

```bash
# Test endpoint directly
kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080
curl http://localhost:8080/a2a/health

# Should return:
# {"status":"healthy","agent":"KubernetesAgent","version":"1.0.0"}
```

### PR creation failing

```bash
# Check GitHub token permissions:
# - repo (full control)
# - workflow (if modifying GitHub Actions)

# Check logs for git errors:
kubectl logs deployment/kubernetes-agent -n argo-rollouts | grep -i "git\|github"
```

## Security Considerations

1. **RBAC**: Agent only has read access to K8s resources (no write)
2. **Secrets**: Store API keys in Kubernetes secrets
3. **Network**: Use NetworkPolicies to restrict egress
4. **Git**: Use fine-grained personal access tokens
5. **Review**: Always review PRs before merging

## Development

### Project Structure

```
kubernetes-agent/
├── src/main/java/com/google/adk/samples/agents/k8sagent/
│   ├── KubernetesAgent.java          # Main agent
│   ├── tools/                        # K8s debugging tools
│   ├── remediation/                  # Git and GitHub operations
│   └── a2a/                          # A2A REST controllers
├── deployment/                       # Kubernetes manifests
├── pom.xml                           # Maven config
└── Dockerfile                        # Container image
```

### Running Tests

```bash
mvn test
```

### Building Multi-arch Images

```bash
docker buildx build --platform linux/amd64,linux/arm64 \
	-t csanchez/kubernetes-agent:latest \
	--push .
```

## Roadmap

- [ ] Multi-cluster support
- [ ] Historical analysis (learn from past incidents)
- [ ] Cost optimization recommendations
- [ ] Security vulnerability detection
- [ ] Self-healing capabilities
- [ ] Slack/PagerDuty notifications
- [ ] Advanced code analysis before fixes

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

For issues or questions:
- **GitHub Issues**: [https://github.com/carlossg/kubernetes-agent/issues](https://github.com/carlossg/kubernetes-agent/issues)
- **Documentation**: See the `docs/` directory and inline README files

## Development Documentation

Additional development documentation is available in the `docs/development/` directory:
- Maven plugin integration and logging
- Debug mode configuration
- Testing strategies
- Rate limiting and fork modes

## Multi-Model Parallel Analysis

The agent supports analyzing canary deployments with multiple LLMs running in parallel, using confidence-weighted voting for the final decision.

### Configuration

Enable multi-model analysis:

```yaml
- name: ENABLE_MULTI_MODEL
  value: "true"
- name: MODELS_TO_USE
  value: "gemini-2.5-flash,gemma-3-1b-it"  # Comma-separated list (optional, defaults to all available)
- name: VOTING_STRATEGY
  value: "weighted"  # Confidence-weighted voting
```

### How It Works

1. **Parallel Execution**: Each model analyzes independently and simultaneously
2. **Confidence-Weighted Voting**: 
   - `promote_score = Σ(confidence/100) for PROMOTE votes`
   - `rollback_score = Σ(confidence/100) for ROLLBACK votes`
   - Decision: PROMOTE if `promote_score > rollback_score`
3. **Consolidated Reporting**: All model results preserved in response
4. **GitHub Issue Creation**: On rollback, creates issue with:
   - Voting breakdown (promote vs rollback scores)
   - Individual model recommendations with confidence
   - Detailed analyses from each model
   - Timestamp and rollout metadata

### Benefits

- **Higher Reliability**: Multiple perspectives reduce false positives/negatives
- **Confidence Validation**: Cross-validation between models
- **Model Diversity**: Different models may catch different types of issues
- **Full Observability**: Complete transparency into each model's reasoning
- **Fast**: Parallel execution means latency ≈ slowest model (~3-5s for 2 models)

### Example Multi-Model Response

```json
{
  "analysis": "Multi-model analysis consensus:\n\n--- gemini-2.5-flash ---\nDatabase connection timeout detected...\n\n--- gemma-3-1b-it ---\nHigh error rate in canary logs...",
  "rootCause": "gemini-2.5-flash: Database connection timeout; gemma-3-1b-it: High error rate",
  "remediation": "- Increase database connection timeout\n- Add retry logic with exponential backoff",
  "promote": false,
  "confidence": 78,
  "modelResults": [
    {
      "modelName": "gemini-2.5-flash",
      "promote": false,
      "confidence": 85,
      "executionTimeMs": 3245
    },
    {
      "modelName": "gemma-3-1b-it",
      "promote": false,
      "confidence": 72,
      "executionTimeMs": 2891
    }
  ],
  "votingRationale": "Confidence-weighted voting: Promote=0.00, Rollback=1.57. Final decision: ROLLBACK.\n\nIndividual model votes:\n- gemini-2.5-flash: ROLLBACK (confidence: 85%)\n- gemma-3-1b-it: ROLLBACK (confidence: 72%)\n"
}
```

### Testing Multi-Model Locally

Use the provided test script:

```bash
./test-multi-model.sh
```

This script:
1. Builds the Docker image
2. Starts port-forward to GKE Gemma server
3. Runs agent with multi-model configuration
4. Sends test query with both models
5. Verifies parallel execution and weighted voting work correctly


