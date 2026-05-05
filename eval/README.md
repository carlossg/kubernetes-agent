# Gemma 4 quantization eval (k8s debugging)

Compare the Gemma-4-26B-A4B quantization variants (Q3_K_M, Q4_K_M, Q5_K_M, Q6_K)
on the canary-rollout debugging task using Vertex AI Eval.

Two complementary harnesses live here, each isolating a different failure
mode that quantization can introduce:

| Harness            | Dataset                | What it measures                              |
|--------------------|------------------------|-----------------------------------------------|
| `run_eval.py`      | `dataset.jsonl`        | Reasoning + structured-JSON output quality    |
| `run_tool_eval.py` | `tool_dataset.jsonl` + `tools_schema.json` | Function-calling correctness (tool name, args) |

Both use static, pre-resolved prompts so results are reproducible and don't
depend on cluster state or tool reliability.

## What's measured

### `run_eval.py` (reasoning / final JSON)

- **promote_accuracy** (computed): exact match of the model's `promote` boolean
  vs the reference. The single most important signal — a wrong promote decision
  is what the rollout actually consumes.
- **json_validity** (computed): fraction of responses parseable as the expected
  JSON object.
- **avg_latency_s** (computed): mean wall time per request.
- **k8s_debug_quality** (Vertex AI judge, 1–5): custom rubric scoring promote
  decision, root-cause specificity, remediation actionability, and JSON schema.
- **groundedness / instruction_following / verbosity** (Vertex AI judge):
  built-in pointwise metrics from `MetricPromptTemplateExamples`.

### `run_tool_eval.py` (function calling)

The agent uses native function calling (`--enable-auto-tool-choice` in vLLM)
and quantization tends to degrade tool-call accuracy more than narrative
reasoning. This harness passes the agent's actual tool schemas
(`tools_schema.json`, mirroring `K8sTools`) and inspects the model's
`tool_calls` against a reference trajectory.

- **tool_call_emitted** (computed): produced any tool_call vs free text.
- **tool_count_match** (computed): emitted the right number of calls.
- **tool_name_match** (computed): per-position function name equality.
- **tool_param_key_match** (computed): required argument keys present.
- **tool_param_kv_match** (computed): required key+value equality (the
  number that most directly predicts agent behavior).
- **tool_call_full_match** (computed): strict end-to-end match across
  count, names, keys, and values.
- **tool_selection_quality** (Vertex AI judge, 1–5): rubric that awards
  partial credit for valid alternative tool choices the strict computed
  metrics would mark as wrong.

## Prerequisites

```bash
# Python deps (use a venv; do not install system-wide)
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt

# GCP auth for Vertex AI
gcloud auth application-default login

# Variants deployed and reachable from kubectl
kubectl get svc -n gemma-system | grep gemma-4-26b
```

If you only deployed a subset of variants, pass `--variants` (see below).

## Run

In one shell, port-forward all four variants:

```bash
./port_forward.sh
```

In another shell, run the eval:

```bash
# Reasoning / final JSON
python run_eval.py --project YOUR_GCP_PROJECT --location us-central1

# Function calling
python run_tool_eval.py --project YOUR_GCP_PROJECT --location us-central1
```

To skip the Vertex AI judges and only collect responses + computed metrics
(useful while iterating on the dataset or prompt):

```bash
python run_eval.py --project YOUR_GCP_PROJECT --skip-vertex
python run_tool_eval.py --project YOUR_GCP_PROJECT --skip-vertex
```

To evaluate a subset (same flag on both harnesses):

```bash
python run_eval.py --project YOUR_GCP_PROJECT \
    --variants gemma-4-26b,gemma-4-26b-q5
```

## Outputs

Under `results/` (per harness, file names disambiguated):

- Reasoning harness: `<variant>-responses.jsonl`, `<variant>-summary.json`,
  `comparison.csv`.
- Tool-use harness: `<variant>-tool-responses.jsonl`,
  `<variant>-tool-summary.json`, `tool-comparison.csv`.

## Extending the datasets

### `dataset.jsonl` (reasoning)

Each row must contain:

- `scenario_id`: short slug, used in logs and per-row response IDs
- `prompt`: full prompt sent to the model (no chat history)
- `reference`: stringified JSON with at minimum `promote` and `rootCause`;
  the judge metrics see this as ground truth and rate the response against it

Keep prompts self-contained — the model should not need any tool calls or
external context to answer.

### `tool_dataset.jsonl` (function calling)

Each row must contain:

- `scenario_id`
- `prompt`: a user request that should trigger one or more tool calls
- `reference_tool_calls`: list of `{"name": "...", "arguments": {...}}`
  representing the expected tool trajectory. Multi-step scenarios list
  calls in order.

Tool schemas live in `tools_schema.json` and are loaded once per run, so
all rows see the same tool surface (mirroring how the agent registers
tools with `KubernetesAgent`). To add a tool, edit that file; reference
the new tool name from rows that should exercise it.
