#!/bin/bash
set -e

# Test script for validating that Gemma actually uses Kubernetes tools
# This goes beyond simple Q&A and checks for "Function Calling" (or ReAct) behavior

echo "🛠️ Testing Gemma Tool Usage (Function Calling)"
echo "============================================="

# Check prerequisites
command -v kubectl >/dev/null 2>&1 || { echo "❌ kubectl is required but not installed."; exit 1; }
command -v java >/dev/null 2>&1 || { echo "❌ java is required but not installed."; exit 1; }

# Check if Gemma server is running
echo "📡 Checking Gemma server status..."
if ! kubectl get pods -n gemma-system -l app=gemma-server | grep -q Running; then
	echo "❌ Gemma server pod is not running in gemma-system namespace"
	exit 1
fi

# Start port-forward in background
echo "🔌 Setting up port-forward to Gemma server..."
kubectl port-forward -n gemma-system svc/gemma-server 8000:8000 >/dev/null 2>&1 &
PORT_FORWARD_PID=$!
PORT_FORWARD_LOG="port-forward.log"

# Cleanup function
cleanup() {
	echo ""
	echo "🧹 Cleaning up..."
	if [ ! -z "$PORT_FORWARD_PID" ]; then
		kill $PORT_FORWARD_PID 2>/dev/null || true
	fi
	if [ ! -z "$AGENT_PID" ]; then
		echo "Stopping agent (PID: $AGENT_PID)..."
		kill $AGENT_PID 2>/dev/null || true
	fi
	# rm -f agent.log
}
trap cleanup EXIT

# Wait for port-forward
echo "⏳ Waiting 5s for port-forward..."
sleep 5

# Start Agent locally in background
echo "🚀 Starting Kubernetes Agent locally..."
# We use spring-boot:run to run the agent
# We capture logs to agent.log to verify tool usage
export GEMINI_MODEL="gemma-3-1b-it"
export VLLM_API_BASE="http://localhost:8000"
export VLLM_API_KEY="not-needed"
# We need to set this to ensure the custom model is used
export MODELS_TO_USE="gemma-3-1b-it"

mvn -q spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=8082" \
    -Dspring.main.banner-mode=off \
    -Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true" \
    > agent.log 2>&1 &
AGENT_PID=$!

echo "⏳ Waiting for Agent to start (PID: $AGENT_PID)..."
# Loop to check for startup
max_retries=30
count=0
started=false
while [ $count -lt $max_retries ]; do
    if grep -q "Started KubernetesAgentApplication" agent.log; then
        started=true
        break
    fi
    if ! ps -p $AGENT_PID > /dev/null; then
        echo "❌ Agent process died!"
        cat agent.log
        exit 1
    fi
    sleep 2
    count=$((count+1))
    echo -n "."
done

if [ "$started" = false ]; then
    echo "❌ Agent failed to start in time."
    cat agent.log
    exit 1
fi

echo ""
echo "✅ Agent started. Testing tool usage..."

# Send a request that requires listing pods
# "List the pods in the default namespace"
# We use the A2A analyze endpoint
echo "📨 Sending request to Agent..."
curl -s -X POST http://localhost:8082/a2a/analyze \
    -H "Content-Type: application/json" \
    -d '{ "userId": "test-tool-user", "prompt": "List the pods in the default namespace", "context": { "namespace": "default" } }' > response.json

echo "📥 Received response."
cat response.json | jq . 2>/dev/null || cat response.json

# Verification
echo ""
echo "🔍 Verifying tool usage in logs..."

# We look for the log marker we saw in KubernetesAgent.java: ">>> TOOL CALL:"
# Or "Function Call:" if the ADK logs it differently.
# Based on my previous read of KubernetesAgent.java:
# logger.debug(">>> TOOL CALL: {}", functionCall.name());

if grep -q "TOOL CALL: listPods" agent.log; then
    echo "✅ SUCCESS: 'listPods' tool was called!"
elif grep -q "Function Call: listPods" agent.log; then
     echo "✅ SUCCESS: 'listPods' tool was called (ADK format)!"
else
    echo "❌ FAILURE: No evidence of 'listPods' tool call in logs."
    echo "----------------------------------------"
    echo "Last 50 lines of agent.log:"
    tail -n 50 agent.log
    echo "----------------------------------------"
    exit 1
fi

# Verify response content contains actual pod data (we know the agent pod itself might be there if in default? No, agent is in argo-rollouts usually)
# But we should at least see some analysis or mention of "No pods" or a list.
exit 0
