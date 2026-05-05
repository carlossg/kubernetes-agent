#!/bin/bash
# Start port-forwards for all Gemma 4 variants on local ports 8001..8004.
# Run in foreground; ctrl-c to stop. Pair with run_eval.py in another shell.
set -euo pipefail

NS="${GEMMA_NAMESPACE:-gemma-system}"
CTX="${KUBECONTEXT:-}"
KUBECTL=("kubectl")
[ -n "$CTX" ] && KUBECTL+=("--context" "$CTX")

declare -a PIDS=()
forward() {
	local svc="$1" port="$2"
	"${KUBECTL[@]}" port-forward -n "$NS" "svc/${svc}" "${port}:8000" >/dev/null 2>&1 &
	PIDS+=($!)
	echo "  -> ${svc} on localhost:${port} (pid ${PIDS[-1]})"
}

cleanup() {
	echo
	echo "Stopping port-forwards..."
	for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

echo "Starting port-forwards in namespace $NS:"
forward gemma-4-26b-q3-server 8001
forward gemma-4-26b-server    8002
forward gemma-4-26b-q5-server 8003
forward gemma-4-26b-q6-server 8004

echo "All port-forwards running. Ctrl-C to stop."
wait
