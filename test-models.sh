#!/bin/bash
# Unified model comparison test. Run the same analyze request with one or more models (sequential).
# Usage:
#   ./test-models.sh                          # interactive: choose models
#   ./test-models.sh gemini gemma-1b          # test Gemini + Gemma 1B
#   ./test-models.sh -m gemini,gemma-1b,gemma-9b
#   ./test-models.sh --no-wait-vllm gemma-1b  # skip vLLM readiness wait
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/test-config.sh"

# Model map: alias -> "MODEL_NAME SERVICE PORT" (SERVICE=NONE for Gemini)
get_model_info() {
	case "$1" in
		gemini)     echo "gemini-2.5-flash NONE NONE" ;;
		gemma-1b)   echo "gemma-3-1b-it gemma-1b-server 8000" ;;
		gemma-9b)   echo "gemma-2-9b-it gemma-9b-server 8000" ;;
		*)          echo "" ;;
	esac
}

VALID_MODELS="gemini gemma-1b gemma-9b"
VLLM_WAIT_RETRIES=180
WAIT_VLLM=true

# Parse args
MODELS_STR=""
while [ $# -gt 0 ]; do
	case "$1" in
		-m|--models)
			MODELS_STR="$2"
			shift 2
			;;
		--no-wait-vllm)
			WAIT_VLLM=false
			shift
			;;
		-h|--help)
			echo "Usage: $0 [OPTIONS] [MODEL...]"
			echo "  Models: gemini, gemma-1b, gemma-9b"
			echo "  -m, --models LIST   Comma-separated model list"
			echo "  --no-wait-vllm      Skip waiting for vLLM /v1/models"
			echo "  -h, --help          This help"
			echo ""
			echo "Examples:"
			echo "  $0                    # interactive"
			echo "  $0 gemini gemma-1b"
			echo "  $0 -m gemini,gemma-1b,gemma-9b"
			exit 0
			;;
		-*)
			echo "Unknown option: $1" >&2
			exit 1
			;;
		*)
			if [ -z "$MODELS_STR" ]; then
				MODELS_STR="$1"
			else
				MODELS_STR="${MODELS_STR},${1}"
			fi
			shift
			;;
	esac
done

# Resolve model list: from -m/positional or interactive
if [ -z "$MODELS_STR" ]; then
	echo "Select models to test (comma-separated or space-separated):"
	echo "  gemini     - Gemini 2.5 Flash (cloud)"
	echo "  gemma-1b   - Gemma 3 1B (vLLM)"
	echo "  gemma-9b   - Gemma 2 9B (vLLM)"
	echo ""
	printf "Models [gemini, gemma-1b, gemma-9b]: "
	read -r MODELS_STR
	MODELS_STR="${MODELS_STR:-gemini, gemma-1b, gemma-9b}"
fi
# Normalize: replace commas with spaces, trim
MODELS_STR=$(echo "$MODELS_STR" | tr ',' ' ')
SELECTED_MODELS=()
for m in $MODELS_STR; do
	m=$(echo "$m" | tr -d ' \t')
	[ -z "$m" ] && continue
	info=$(get_model_info "$m")
	if [ -z "$info" ]; then
		echo "Unknown model: $m (valid: $VALID_MODELS)" >&2
		exit 1
	fi
	SELECTED_MODELS+=("$m")
done
if [ ${#SELECTED_MODELS[@]} -eq 0 ]; then
	echo "No models selected." >&2
	exit 1
fi

echo "=================================================="
echo "Model comparison test: ${SELECTED_MODELS[*]}"
echo "=================================================="
echo ""

test_model() {
	local ALIAS=$1
	local MODEL SERVICE PORT
	read -r MODEL SERVICE PORT <<< "$(get_model_info "$ALIAS")"

	echo ""
	echo "=========================================="
	echo "Testing model: $MODEL"
	echo "=========================================="

	local PF_PID=""
	if [ "$SERVICE" != "NONE" ]; then
		echo "--- Starting port-forward to $SERVICE ---"
		local PF_PORT
		PF_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')
		kubectl port-forward --context "$CONTEXT" -n "$GEMMA_NAMESPACE" svc/"$SERVICE" "${PF_PORT}:${PORT}" >/dev/null 2>&1 &
		PF_PID=$!
		echo "Port-forward to $SERVICE on port $PF_PORT (PID: $PF_PID)"
		sleep 5
		if [ "$WAIT_VLLM" = true ]; then
			echo "Waiting for vLLM server to be ready..."
			local vllm_count=0
			while [ $vllm_count -lt "$VLLM_WAIT_RETRIES" ]; do
				if curl -s -o /dev/null -w "%{http_code}" "http://localhost:${PF_PORT}/v1/models" | grep -q "200"; then
					echo " vLLM server ready."
					break
				fi
				sleep 5
				vllm_count=$((vllm_count+1))
				echo -n "."
			done
			if [ $vllm_count -eq "$VLLM_WAIT_RETRIES" ]; then
				echo " vLLM server did not become ready in time."
				kill "$PF_PID" 2>/dev/null || true
				return 1
			fi
			sleep 2
		fi
		export VLLM_API_BASE="http://localhost:${PF_PORT}"
		export VLLM_MODEL="$MODEL"
	else
		export VLLM_API_BASE=""
		export VLLM_MODEL=""
	fi

	echo "--- Running agent with $MODEL ---"
	export ENABLE_MULTI_MODEL="false"
	export MODELS_TO_USE="$MODEL"
	export GEMINI_MODEL="$MODEL"
	export VLLM_API_KEY="not-needed"
	export GOOGLE_API_KEY="${GOOGLE_API_KEY}"

	mvn -q spring-boot:run \
		-Dspring-boot.run.arguments="--server.port=${AGENT_PORT} --spring.web.resources.add-mappings=false" \
		-Dspring.main.banner-mode=off \
		-Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true" \
		> agent.log 2>&1 &
	local AGENT_PID=$!

	echo "Waiting for agent to start..."
	local count=0
	local started=false
	while [ $count -lt 30 ]; do
		if grep -q "Started KubernetesAgentApplication" agent.log 2>/dev/null; then
			started=true
			break
		fi
		if ! ps -p "$AGENT_PID" > /dev/null 2>&1; then
			echo "❌ Agent died!"
			tail -50 agent.log
			[ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null || true
			return 1
		fi
		sleep 2
		count=$((count+1))
		echo -n "."
	done
	echo ""

	if [ "$started" = false ]; then
		echo "❌ Agent failed to start"
		[ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null || true
		return 1
	fi
	sleep 3

	echo "--- Sending analysis request ---"
	local REQUEST_JSON RESPONSE
	REQUEST_JSON=$(jq -n \
		--arg userId "test-user" \
		--arg prompt "$TEST_PROMPT" \
		--argjson context "$TEST_CONTEXT" \
		'{userId: $userId, prompt: $prompt, context: $context}')
	RESPONSE=$(curl -s -X POST "http://localhost:${AGENT_PORT}/a2a/analyze" \
		-H "Content-Type: application/json" \
		-d "$REQUEST_JSON")

	echo "--- Result for $MODEL ---"
	echo "$RESPONSE" | jq .

	local PROMOTE CONFIDENCE TIME
	PROMOTE=$(echo "$RESPONSE" | jq -r '.promote')
	CONFIDENCE=$(echo "$RESPONSE" | jq -r '.confidence')
	TIME=$(echo "$RESPONSE" | jq -r '.executionTimeMs')
	echo ""
	echo "Summary: promote=$PROMOTE, confidence=$CONFIDENCE%, time=${TIME}ms"

	echo "$RESPONSE" > "result_${MODEL}.json"

	echo "--- Cleanup ---"
	kill "$AGENT_PID" 2>/dev/null || true
	[ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null || true
	sleep 5
}

for alias in "${SELECTED_MODELS[@]}"; do
	info=$(get_model_info "$alias")
	[ -z "$info" ] && continue
	test_model "$alias" || true
done

echo ""
echo "=================================================="
echo "📊 COMPARISON SUMMARY"
echo "=================================================="
for alias in "${SELECTED_MODELS[@]}"; do
	info=$(get_model_info "$alias")
	[ -z "$info" ] && continue
	MODEL=$(echo "$info" | awk '{print $1}')
	[ ! -f "result_${MODEL}.json" ] && continue
	echo ""
	echo "--- $MODEL ---"
	echo "Promote: $(jq -r '.promote' "result_${MODEL}.json")"
	echo "Confidence: $(jq -r '.confidence' "result_${MODEL}.json")%"
	echo "Time: $(jq -r '.executionTimeMs' "result_${MODEL}.json")ms"
	echo "Error: $(jq -r '.error // "none"' "result_${MODEL}.json")"
	echo "Analysis preview:"
	jq -r '.analysis' "result_${MODEL}.json" | head -3
done
echo ""
echo "=================================================="
echo "Detailed results saved to result_*.json files"
echo "=================================================="
