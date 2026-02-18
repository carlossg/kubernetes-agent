# Test Scripts

Overview of test scripts and when to use them.

## Scripts

| Script | Purpose |
|--------|--------|
| **test-agent.sh** | Smoke test: health check + one analyze request. No model choice. Use to verify the agent is up (K8s or local). |
| **test-models.sh** | **Unified model comparison.** Run the same analyze request with one or more models (sequential). Accepts parameters or asks interactively. |
| **test-multi-model.sh** | Multi-model **parallel** mode: one request with `ENABLE_MULTI_MODEL=true` (weighted voting). Uses Gemini + two Gemma backends. |

## test-models.sh (unified comparison)

One script replaces the previous per-flavor scripts. You can:

- **Interactive**: run with no arguments and choose models from a menu.
- **Parameters**: pass model names as arguments or via `-m`/`--models`.

**Models:** `gemini`, `gemma-1b`, `gemma-9b`

**Examples:**

```bash
./test-models.sh                          # interactive: prompt for models
./test-models.sh gemini gemma-1b           # test Gemini + Gemma 1B
./test-models.sh -m gemini,gemma-1b,gemma-9b
./test-models.sh --no-wait-vllm gemma-1b   # skip vLLM readiness wait (faster if server is up)
./test-models.sh --help
```

Results are written to `result_<model>.json` and a short comparison is printed at the end.

**Shared config:** Prompt and context are in `test-config.sh`, which is sourced by `test-models.sh` and `test-multi-model.sh`. Override cluster with `TEST_CONTEXT_K8S` if needed.

## test-agent.sh (smoke test)

```bash
./test-agent.sh k8s     # agent in Kubernetes (default)
./test-agent.sh local   # agent on localhost:8080
```

Uses `CONTEXT` for K8s mode. No Gemma or model selection.

## test-multi-model.sh (parallel voting)

Single request, multiple models in parallel with confidence-weighted voting. Requires Gemini + two Gemma servers (1B and 27B in script; adjust if using 9B). Shares prompt/context from `test-config.sh`.

## Prerequisites

- **test-agent.sh**: Agent running (in cluster or local).
- **test-models.sh**: `GOOGLE_API_KEY`, Gemma deployments in `gemma-system` for any `gemma-*` model, `CONTEXT` (or `TEST_CONTEXT_K8S`) for port-forward.
- **test-multi-model.sh**: Same as test-models.sh; two Gemma port-forwards.
