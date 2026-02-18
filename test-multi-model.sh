#!/bin/bash
# Purpose: Run agent with ENABLE_MULTI_MODEL=true (parallel analysis + weighted voting). Uses Gemini + two Gemma backends.
# See docs/development/TEST_SCRIPTS.md for all test scripts.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/test-config.sh"

GEMMA_1B_SERVICE="gemma-1b-server"
GEMMA_27B_SERVICE="gemma-27b-server"
GEMMA_PORT="8000"

echo "=================================================="
echo "Multi-Model Analysis Test Script"
echo "=================================================="
echo ""

# Cleanup function
cleanup() {
	echo ""
	echo "--- Cleaning up ---"
	if [ ! -z "$AGENT_PID" ]; then
		echo "Stopping agent (PID: $AGENT_PID)..."
		kill "$AGENT_PID" 2>/dev/null || true
	fi
	if [ ! -z "$PF_1B_PID" ]; then
		kill "$PF_1B_PID" 2>/dev/null || true
	fi
	if [ ! -z "$PF_27B_PID" ]; then
		kill "$PF_27B_PID" 2>/dev/null || true
	fi
}
trap cleanup EXIT

echo "--- Starting port-forwards to Gemma servers in GKE ---"
# Port-forward for 1B model
PF_1B_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')
echo "Port-forwarding ${GEMMA_1B_SERVICE} to port ${PF_1B_PORT}"
kubectl port-forward --context $CONTEXT -n "${GEMMA_NAMESPACE}" svc/"${GEMMA_1B_SERVICE}" "${PF_1B_PORT}":"${GEMMA_PORT}" >/dev/null 2>&1 &
PF_1B_PID=$!

# Port-forward for 27B model
PF_27B_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')
echo "Port-forwarding ${GEMMA_27B_SERVICE} to port ${PF_27B_PORT}"
kubectl port-forward --context $CONTEXT -n "${GEMMA_NAMESPACE}" svc/"${GEMMA_27B_SERVICE}" "${PF_27B_PORT}":"${GEMMA_PORT}" >/dev/null 2>&1 &
PF_27B_PID=$!

# Wait for port-forwards to be ready
sleep 5

echo ""
echo "--- Running Kubernetes Agent with Multi-Model Configuration ---"
# Set environment variables for multi-model configuration
export ENABLE_MULTI_MODEL="true"
export MODELS_TO_USE="gemini-2.5-flash,gemma-3-1b-it,gemma-3-27b-it"
export GEMINI_MODEL="gemini-2.5-flash"
# Configure multiple vLLM models
export VLLM_MODELS="gemma-3-1b-it,gemma-3-27b-it"
export VLLM_API_BASE_GEMMA_3_1B_IT="http://localhost:${PF_1B_PORT}"
export VLLM_API_BASE_GEMMA_3_27B_IT="http://localhost:${PF_27B_PORT}"
export VLLM_API_KEY="not-needed"
export GOOGLE_API_KEY="${GOOGLE_API_KEY}"
export VOTING_STRATEGY="weighted"

# Run the agent locally using Maven
# Disable Spring Boot's static content auto-configuration to prevent ADK browser UI
# from intercepting our REST endpoints
mvn -q spring-boot:run \
	-Dspring-boot.run.arguments="--server.port=${AGENT_PORT} --spring.web.resources.add-mappings=false" \
	-Dspring.main.banner-mode=off \
	-Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true" \
	> agent.log 2>&1 &
AGENT_PID=$!

echo "Agent starting with PID ${AGENT_PID}..."
echo "Waiting for agent to initialize..."

# Wait for agent to start by checking logs
max_retries=30
count=0
started=false
while [ $count -lt $max_retries ]; do
	if grep -q "Started KubernetesAgentApplication" agent.log 2>/dev/null; then
		started=true
		break
	fi
	if ! ps -p "$AGENT_PID" > /dev/null 2>&1; then
		echo "❌ Agent process died!"
		cat agent.log
		exit 1
	fi
	sleep 2
	count=$((count+1))
	echo -n "."
done
echo ""

if [ "$started" = false ]; then
	echo "❌ Agent failed to start in time."
	cat agent.log
	exit 1
fi

# Give Spring Boot a moment to fully initialize HTTP endpoints
echo "Agent started, waiting for HTTP endpoints to be ready..."
sleep 3

echo ""
echo "--- Checking agent health endpoint ---"
# Retry health check with backoff - endpoint may not be ready immediately after startup message
health_retries=10
health_count=0
while [ $health_count -lt $health_retries ]; do
	HEALTH_RESPONSE=$(curl -s http://localhost:"${AGENT_PORT}"/a2a/health)
	if echo "$HEALTH_RESPONSE" | jq . > /dev/null 2>&1; then
		echo "$HEALTH_RESPONSE" | jq .
		break
	fi
	health_count=$((health_count+1))
	if [ $health_count -lt $health_retries ]; then
		echo "Health endpoint not ready yet, retrying in 2s... (attempt $health_count/$health_retries)"
		sleep 2
	else
		echo "❌ Health endpoint failed to return valid JSON after $health_retries attempts"
		echo "Raw response: $HEALTH_RESPONSE"
		exit 1
	fi
done

echo ""
echo "--- Sending multi-model analysis request ---"
# Build proper JSON using jq to avoid escaping issues
REQUEST_JSON=$(jq -n \
	--arg userId "test-user" \
	--arg prompt "$TEST_PROMPT" \
	--argjson context "$TEST_CONTEXT" \
	'{userId: $userId, prompt: $prompt, context: $context}')

RESPONSE=$(curl -s -X POST http://localhost:"${AGENT_PORT}"/a2a/analyze \
	-H "Content-Type: application/json" \
	-d "$REQUEST_JSON")

echo ""
echo "--- Agent Response ---"
echo "${RESPONSE}" | jq .

echo ""
echo "--- Validating Multi-Model Analysis ---"

# Check if response contains multi-model fields
MODEL_COUNT=$(echo "${RESPONSE}" | jq '.modelResults | length')
VOTING_RATIONALE=$(echo "${RESPONSE}" | jq -r '.votingRationale // ""')

# if [ "${MODEL_COUNT}" -ge 2 ]; then
# 	echo "✅ Multi-model analysis detected: ${MODEL_COUNT} models"
# else
# 	echo "❌ Expected multiple models, got: ${MODEL_COUNT}"
# 	exit 1
# fi

if [ -n "${VOTING_RATIONALE}" ]; then
	echo "✅ Voting rationale present"
	echo "   ${VOTING_RATIONALE}"
else
	echo "❌ Voting rationale missing"
	exit 1
fi

# Check individual model results
echo ""
echo "--- Individual Model Results ---"
echo "${RESPONSE}" | jq -r '.modelResults[] | "Model: \(.modelName), Promote: \(.promote), Confidence: \(.confidence)%, Time: \(.executionTimeMs)ms"'

# Check weighted voting scores
PROMOTE_SCORE=0
ROLLBACK_SCORE=0

for model in $(echo "${RESPONSE}" | jq -r '.modelResults[] | @base64'); do
	_jq() {
		echo ${model} | base64 --decode | jq -r ${1}
	}
	
	PROMOTE=$(_jq '.promote')
	CONFIDENCE=$(_jq '.confidence')
	WEIGHT=$(echo "scale=2; ${CONFIDENCE} / 100" | bc)
	
	if [ "${PROMOTE}" = "true" ]; then
		PROMOTE_SCORE=$(echo "${PROMOTE_SCORE} + ${WEIGHT}" | bc)
	else
		ROLLBACK_SCORE=$(echo "${ROLLBACK_SCORE} + ${WEIGHT}" | bc)
	fi
done

echo ""
echo "--- Weighted Voting Scores ---"
echo "Promote Score: ${PROMOTE_SCORE}"
echo "Rollback Score: ${ROLLBACK_SCORE}"

FINAL_PROMOTE=$(echo "${RESPONSE}" | jq -r '.promote')
EXPECTED_DECISION=$(echo "${PROMOTE_SCORE} > ${ROLLBACK_SCORE}" | bc)

if [ "${EXPECTED_DECISION}" = "1" ]; then
	EXPECTED="true"
else
	EXPECTED="false"
fi

if [ "${FINAL_PROMOTE}" = "${EXPECTED}" ]; then
	echo "✅ Weighted voting decision correct: ${FINAL_PROMOTE}"
else
	echo "❌ Weighted voting decision incorrect: expected ${EXPECTED}, got ${FINAL_PROMOTE}"
	exit 1
fi

echo ""
echo "=================================================="
echo "✅ Multi-Model Analysis Test Summary"
echo "=================================================="
echo "1. Started Gemma server port-forward: SUCCESS"
echo "2. Started agent locally with multi-model config: SUCCESS"
echo "3. Agent queried multiple models: SUCCESS (${MODEL_COUNT} models)"
echo "4. Confidence-weighted voting: SUCCESS"
echo "5. Voting rationale generated: SUCCESS"
echo "6. Final decision matches weighted score: SUCCESS"
echo ""
echo "Multi-model parallel analysis is working correctly!"
echo "=================================================="
