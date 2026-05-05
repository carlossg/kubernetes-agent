# Gemma 4 quantization eval (k8s debugging)

Compare the Gemma-4-26B-A4B quantization variants (Q3_K_M, Q4_K_M, Q5_K_M, Q6_K)
on the canary-rollout debugging task using Vertex AI Eval.

The dataset is intentionally static: each prompt embeds pod state and logs,
so results are reproducible and isolate model quality from cluster state and
tool reliability. The agent's tool layer is therefore not on the hot path here;
the question is "which quantization gives the best reasoning + JSON output
on this fixed task."

## What's measured

- **promote_accuracy** (computed): exact match of the model's `promote` boolean
  vs the reference. The single most important signal — a wrong promote decision
  is what the rollout actually consumes.
- **json_validity** (computed): fraction of responses parseable as the expected
  JSON object.
- **avg_latency_s** (computed): mean wall time per request (per port-forwarded
  endpoint, so includes a fixed local-network overhead).
- **k8s_debug_quality** (Vertex AI judge, 1–5): custom rubric scoring promote
  decision, root-cause specificity, remediation actionability, and JSON schema.
- **groundedness / instruction_following / verbosity** (Vertex AI judge):
  built-in pointwise metrics from `MetricPromptTemplateExamples`.

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
python run_eval.py --project YOUR_GCP_PROJECT --location us-central1
```

To skip the Vertex AI judges and only collect responses + computed metrics
(useful while iterating on the dataset or prompt):

```bash
python run_eval.py --project YOUR_GCP_PROJECT --skip-vertex
```

To evaluate a subset:

```bash
python run_eval.py --project YOUR_GCP_PROJECT \
    --variants gemma-4-26b,gemma-4-26b-q5
```

## Outputs

Under `results/`:

- `<variant>-responses.jsonl` — prompt, response, latency, error per scenario.
- `<variant>-summary.json` — Vertex AI EvalTask summary metrics.
- `comparison.csv` — one row per variant with all metrics side-by-side.

## Extending the dataset

Append rows to `dataset.jsonl`. Each row must contain:

- `scenario_id`: short slug, used in logs and per-row response IDs
- `prompt`: full prompt sent to the model (no chat history)
- `reference`: stringified JSON with at minimum `promote` and `rootCause`;
  the judge metrics see this as ground truth and rate the response against it

Keep prompts self-contained — the model should not need any tool calls or
external context to answer.
