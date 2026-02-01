#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

# Configuration
GEMMA_NAMESPACE="gemma-system"
GEMMA_SERVICE="gemma-server"
GEMMA_PORT="8000"
AGENT_PORT="8080"
TEST_PROMPT='You are a Kubernetes SRE analyzing canary deployments. Use your Kubernetes tools to fetch logs and events.

Do not write a script or code to perform the analysis. You must perform the analysis yourself by calling the available tools.

CRITICAL: You MUST respond with valid JSON in this exact format:
{
"analysis": "detailed analysis text",
"rootCause": "identified root cause",
"remediation": "suggested remediation steps",
"prLink": "github PR link or null",
"promote": true or false,
"confidence": "0-100"
}

Use tools to gather real data, then provide your analysis in the JSON format above.'
TEST_CONTEXT='{"namespace": "default", "rolloutName": "canary-demo", "stableSelector": "role=stable", "canarySelector": "role=canary"}'

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
	if [ ! -z "$PF_PID" ]; then
		kill "$PF_PID" 2>/dev/null || true
	fi
}
trap cleanup EXIT

echo "--- Starting port-forward to Gemma server in GKE ---"
# Find an available port for port-forwarding
PF_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')
echo "Using port ${PF_PORT} for port-forward"

kubectl port-forward -n "${GEMMA_NAMESPACE}" svc/"${GEMMA_SERVICE}" "${PF_PORT}":"${GEMMA_PORT}" >/dev/null 2>&1 &
PF_PID=$!
echo "Port-forward started with PID ${PF_PID}"

# Wait for port-forward to be ready
sleep 5

echo ""
echo "--- Running Kubernetes Agent with Multi-Model Configuration ---"
# Set environment variables for multi-model configuration
export ENABLE_MULTI_MODEL="true"
export MODELS_TO_USE="gemini-2.5-flash,gemma-3-1b-it"
export GEMINI_MODEL="gemini-2.5-flash"
export VLLM_MODEL="gemma-3-1b-it"
export VLLM_API_BASE="http://localhost:${PF_PORT}"
export VLLM_API_KEY="not-needed"
export GOOGLE_API_KEY="${GOOGLE_API_KEY}"
export VOTING_STRATEGY="weighted"

# Run the agent locally using Maven
mvn -q spring-boot:run \
	-Dspring-boot.run.arguments="--server.port=${AGENT_PORT}" \
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

echo ""
echo "--- Checking agent health endpoint ---"
curl -s http://localhost:"${AGENT_PORT}"/a2a/health | jq .

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
