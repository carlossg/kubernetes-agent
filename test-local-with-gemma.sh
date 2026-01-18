#!/bin/bash
set -e

# Test script for running kubernetes-agent locally with vLLM Gemma via port-forward
# This script validates the integration between the agent and the Gemma deployment in GKE

echo "🚀 Testing Kubernetes Agent with local vLLM Gemma"
echo "=================================================="
echo ""

# Check prerequisites
command -v kubectl >/dev/null 2>&1 || { echo "❌ kubectl is required but not installed."; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "❌ docker is required but not installed."; exit 1; }

# Check if Gemma server is running
echo "📡 Checking Gemma server status..."
if ! kubectl get pods -n gemma-system -l app=gemma-server | grep -q Running; then
	echo "❌ Gemma server pod is not running in gemma-system namespace"
	echo "   Deploy Gemma first: kubectl apply -k deployment/gemma/"
	exit 1
fi

GEMMA_POD=$(kubectl get pods -n gemma-system -l app=gemma-server -o jsonpath='{.items[0].metadata.name}')
echo "✅ Found Gemma pod: $GEMMA_POD"
echo ""

# Start port-forward in background
echo "🔌 Setting up port-forward to Gemma server..."
kubectl port-forward -n gemma-system svc/gemma-server 8000:8000 >/dev/null 2>&1 &
PORT_FORWARD_PID=$!

# Cleanup function
cleanup() {
	echo ""
	echo "🧹 Cleaning up..."
	if [ ! -z "$PORT_FORWARD_PID" ]; then
		kill $PORT_FORWARD_PID 2>/dev/null || true
	fi
	if [ ! -z "$DOCKER_CONTAINER" ]; then
		docker stop $DOCKER_CONTAINER 2>/dev/null || true
	fi
}
trap cleanup EXIT

# Wait for port-forward to be ready
echo "⏳ Waiting for port-forward to be ready..."
sleep 3

# Test Gemma endpoint
echo "🔍 Testing Gemma endpoint..."
if ! curl -s http://localhost:8000/health | grep -q "ok"; then
	echo "❌ Gemma health check failed"
	exit 1
fi
echo "✅ Gemma endpoint is healthy"
echo ""

# Build Docker image
echo "🔨 Building Docker image..."
make docker-build || { echo "❌ Docker build failed"; exit 1; }
echo "✅ Docker image built successfully"
echo ""

# Get GitHub token
if [ -z "$GITHUB_TOKEN" ]; then
	echo "⚠️  GITHUB_TOKEN not set, reading from kubectl secret..."
	GITHUB_TOKEN=$(kubectl get secret kubernetes-agent -n argo-rollouts -o jsonpath='{.data.github_token}' 2>/dev/null | base64 -d)
	if [ -z "$GITHUB_TOKEN" ]; then
		echo "❌ Could not find GITHUB_TOKEN in secret or environment"
		exit 1
	fi
fi

# Run agent in Docker with host network to access port-forward
echo "🐳 Starting agent container with Gemma configuration..."
DOCKER_CONTAINER=$(docker run -d \
	--network host \
	-e GEMINI_MODEL="gemma-3-1b-it" \
	-e VLLM_API_BASE="http://localhost:8000" \
	-e VLLM_API_KEY="not-needed" \
	-e GITHUB_TOKEN="$GITHUB_TOKEN" \
	-e GIT_USERNAME="kubernetes-agent" \
	-e GIT_EMAIL="kubernetes-agent@csanchez.org" \
	ghcr.io/carlossg/kubernetes-agent:latest)

echo "✅ Container started: $DOCKER_CONTAINER"
echo ""

# Wait for agent to start
echo "⏳ Waiting for agent to initialize..."
sleep 10

# Show logs
echo "📋 Agent logs:"
echo "----------------------------------------"
docker logs $DOCKER_CONTAINER 2>&1 | tail -30
echo "----------------------------------------"
echo ""

# Test agent health
echo "🔍 Testing agent health endpoint..."
if curl -s http://localhost:8080/health 2>/dev/null | grep -q "UP\|ok\|healthy"; then
	echo "✅ Agent is healthy!"
else
	echo "⚠️  Agent health check did not return expected response"
	echo "   This might be normal if health endpoint is not implemented"
fi
echo ""

# Test A2A endpoint
echo "🔍 Testing A2A analyze endpoint..."
TEST_REQUEST='{"userId":"test-user","prompt":"Analyze this test pod","context":{"namespace":"default","podName":"test-pod"}}'
RESPONSE=$(curl -s -X POST http://localhost:8080/a2a/analyze \
	-H "Content-Type: application/json" \
	-d "$TEST_REQUEST" 2>/dev/null || echo '{"error":"connection failed"}')

if echo "$RESPONSE" | grep -q "analysis\|error"; then
	echo "✅ A2A endpoint responded"
	echo "   Response: $(echo $RESPONSE | cut -c1-100)..."
else
	echo "❌ A2A endpoint did not respond as expected"
	echo "   Response: $RESPONSE"
fi
echo ""

# Check if vLLM Gemma was used
echo "🔍 Checking if vLLM was used..."
docker logs $DOCKER_CONTAINER 2>&1 | grep -i "vllm\|gemma" | tail -5 || true
echo ""

echo "✨ Test completed!"
echo ""
echo "💡 Tips:"
echo "   - View live logs: docker logs -f $DOCKER_CONTAINER"
echo "   - Test manually: curl -X POST http://localhost:8080/a2a/analyze -H 'Content-Type: application/json' -d '{...}'"
echo "   - Access agent UI: http://localhost:8080"
echo ""
echo "Press Ctrl+C to stop and cleanup..."

# Keep running
wait $PORT_FORWARD_PID
