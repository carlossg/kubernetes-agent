#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

# Configuration
AGENT_IMAGE="ghcr.io/carlossg/kubernetes-agent:multi-model-test"
GEMMA_NAMESPACE="gemma-system"
GEMMA_SERVICE="gemma-server"
GEMMA_PORT="8000"
AGENT_PORT="8080"
VLLM_API_BASE="http://host.docker.internal:${GEMMA_PORT}"
TEST_PROMPT="Analyze a canary pod with database connection errors and high latency"
TEST_CONTEXT='{"namespace": "rollouts-test-system", "rolloutName": "canary-demo", "stableSelector": "role=stable", "canarySelector": "role=canary"}'

echo "=================================================="
echo "Multi-Model Analysis Test Script"
echo "=================================================="
echo ""

echo "--- Building Docker image for Kubernetes Agent ---"
docker build -t "${AGENT_IMAGE}" .

echo ""
echo "--- Starting port-forward to Gemma server in GKE ---"
# Find an available port for port-forwarding
PF_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')
echo "Using port ${PF_PORT} for port-forward"

kubectl port-forward -n "${GEMMA_NAMESPACE}" svc/"${GEMMA_SERVICE}" "${PF_PORT}":"${GEMMA_PORT}" &
PF_PID=$!
echo "Port-forward started with PID ${PF_PID}"

# Wait for port-forward to be ready
sleep 5

echo ""
echo "--- Running Kubernetes Agent with Multi-Model Configuration ---"
docker run --rm -d \
	--name k8s-agent-multi-model-test \
	-p "${AGENT_PORT}":"${AGENT_PORT}" \
	-e ENABLE_MULTI_MODEL="true" \
	-e MODELS_TO_USE="gemini-2.5-flash,gemma-3-1b-it" \
	-e GEMINI_MODEL="gemini-2.5-flash" \
	-e VLLM_MODEL="gemma-3-1b-it" \
	-e VLLM_API_BASE="http://host.docker.internal:${PF_PORT}" \
	-e VLLM_API_KEY="not-needed" \
	-e GOOGLE_API_KEY="${GOOGLE_API_KEY}" \
	-e VOTING_STRATEGY="weighted" \
	--add-host host.docker.internal:host-gateway \
	"${AGENT_IMAGE}"

# Wait for agent to start
echo "Waiting for agent to initialize..."
sleep 15

echo ""
echo "--- Checking agent health endpoint ---"
curl -s http://localhost:"${AGENT_PORT}"/a2a/health | jq .

echo ""
echo "--- Sending multi-model analysis request ---"
RESPONSE=$(curl -s -X POST http://localhost:"${AGENT_PORT}"/a2a/analyze \
	-H "Content-Type: application/json" \
	-d "{
		\"userId\": \"test-user\",
		\"prompt\": \"${TEST_PROMPT}\",
		\"context\": ${TEST_CONTEXT}
	}")

echo ""
echo "--- Agent Response ---"
echo "${RESPONSE}" | jq .

echo ""
echo "--- Validating Multi-Model Analysis ---"

# Check if response contains multi-model fields
MODEL_COUNT=$(echo "${RESPONSE}" | jq '.modelResults | length')
VOTING_RATIONALE=$(echo "${RESPONSE}" | jq -r '.votingRationale // ""')

if [ "${MODEL_COUNT}" -ge 2 ]; then
	echo "✅ Multi-model analysis detected: ${MODEL_COUNT} models"
else
	echo "❌ Expected multiple models, got: ${MODEL_COUNT}"
	exit 1
fi

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
echo "--- Cleaning up ---"
docker stop k8s-agent-multi-model-test || true
kill "${PF_PID}" 2>/dev/null || true

echo ""
echo "=================================================="
echo "✅ Multi-Model Analysis Test Summary"
echo "=================================================="
echo "1. Built Docker image: SUCCESS"
echo "2. Started Gemma server port-forward: SUCCESS"
echo "3. Started agent with multi-model config: SUCCESS"
echo "4. Agent queried multiple models: SUCCESS (${MODEL_COUNT} models)"
echo "5. Confidence-weighted voting: SUCCESS"
echo "6. Voting rationale generated: SUCCESS"
echo "7. Final decision matches weighted score: SUCCESS"
echo ""
echo "Multi-model parallel analysis is working correctly!"
echo "=================================================="
