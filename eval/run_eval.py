"""Compare Gemma 4 quantization variants on a k8s-debugging dataset using Vertex AI Eval.

Inputs:
  - dataset.jsonl: rows of {scenario_id, prompt, reference}
  - port-forwarded vLLM endpoints, one per variant (see port_forward.sh)

Outputs (under --out, default ./results):
  - <variant>-responses.jsonl: prompt + raw model response + per-prompt latency
  - <variant>-summary.json:    Vertex AI EvalTask summary metrics
  - comparison.csv:            side-by-side metrics across variants

Usage:
  ./port_forward.sh &
  python run_eval.py --project YOUR_GCP_PROJECT --location us-central1
"""
from __future__ import annotations

import argparse
import json
import time
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
import vertexai
from openai import OpenAI
from vertexai.evaluation import (
    EvalTask,
    MetricPromptTemplateExamples,
    PointwiseMetric,
    PointwiseMetricPromptTemplate,
)


@dataclass(frozen=True)
class Variant:
    alias: str
    served_model_name: str
    base_url: str


VARIANTS: tuple[Variant, ...] = (
    Variant("gemma-4-26b-q3", "gemma-4-26b-a4b-it-q3", "http://localhost:8001/v1"),
    Variant("gemma-4-26b",    "gemma-4-26b-a4b-it",    "http://localhost:8002/v1"),
    Variant("gemma-4-26b-q5", "gemma-4-26b-a4b-it-q5", "http://localhost:8003/v1"),
    Variant("gemma-4-26b-q6", "gemma-4-26b-a4b-it-q6", "http://localhost:8004/v1"),
)


K8S_DEBUG_METRIC = PointwiseMetric(
    metric="k8s_debug_quality",
    metric_prompt_template=PointwiseMetricPromptTemplate(
        criteria={
            "promote_decision": (
                "The model's `promote` boolean matches the reference promote decision."
            ),
            "root_cause_specificity": (
                "The model's `rootCause` field identifies the specific failure mode "
                "described in the reference (e.g., names the exception/exit code/event), "
                "not a vague restatement."
            ),
            "remediation_actionable": (
                "The `remediation` field proposes a concrete action that would address "
                "the identified root cause. Generic advice scores low."
            ),
            "valid_json_schema": (
                "The full response is parseable JSON containing all required keys: "
                "analysis, rootCause, remediation, promote, confidence."
            ),
        },
        rating_rubric={
            "5": "All four criteria met; promote correct; root cause specific; remediation concrete.",
            "4": "Promote correct; minor weakness in either rootCause specificity or remediation.",
            "3": "Promote correct but rootCause vague OR remediation generic.",
            "2": "Promote wrong but reasoning shows partial understanding of the failure.",
            "1": "Promote wrong AND root cause incorrect, OR JSON malformed/missing fields.",
        },
        input_variables=["prompt", "reference"],
    ),
)


def load_dataset(path: Path) -> pd.DataFrame:
    rows = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
    df = pd.DataFrame(rows)
    missing = {"scenario_id", "prompt", "reference"} - set(df.columns)
    if missing:
        raise ValueError(f"dataset missing columns: {missing}")
    return df


def call_variant(variant: Variant, prompt: str, max_tokens: int = 1024) -> str:
    client = OpenAI(base_url=variant.base_url, api_key="not-needed", timeout=120)
    chat = client.chat.completions.create(
        model=variant.served_model_name,
        messages=[{"role": "user", "content": prompt}],
        max_tokens=max_tokens,
        temperature=0.0,
    )
    return chat.choices[0].message.content or ""


def collect_responses(variant: Variant, df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    responses, latencies, errors = [], [], []
    for _, row in out.iterrows():
        t0 = time.perf_counter()
        try:
            resp = call_variant(variant, row["prompt"])
            err = ""
        except Exception as e:
            resp = ""
            err = f"{type(e).__name__}: {e}"
        latencies.append(round(time.perf_counter() - t0, 3))
        responses.append(resp)
        errors.append(err)
        print(f"  [{variant.alias}] {row['scenario_id']}: {latencies[-1]}s"
              + (f" ERROR {err}" if err else ""))
    out["response"] = responses
    out["latency_s"] = latencies
    out["error"] = errors
    return out


def parse_promote(response: str) -> bool | None:
    if not response:
        return None
    try:
        return bool(json.loads(response).get("promote"))
    except (json.JSONDecodeError, AttributeError):
        return None


def parse_reference_promote(reference: str) -> bool:
    return bool(json.loads(reference)["promote"])


def computed_metrics(df: pd.DataFrame) -> dict[str, float]:
    valid = df[df["error"] == ""]
    if valid.empty:
        return {"promote_accuracy": 0.0, "json_validity": 0.0, "avg_latency_s": float("nan")}
    promote_correct = sum(
        parse_promote(r["response"]) == parse_reference_promote(r["reference"])
        for _, r in valid.iterrows()
    )
    json_valid = sum(parse_promote(r) is not None for r in valid["response"])
    return {
        "promote_accuracy": promote_correct / len(valid),
        "json_validity": json_valid / len(valid),
        "avg_latency_s": round(valid["latency_s"].mean(), 3),
    }


def evaluate_with_vertex(variant: Variant, df: pd.DataFrame) -> dict[str, float]:
    eval_df = df[df["error"] == ""][["prompt", "response", "reference"]].reset_index(drop=True)
    if eval_df.empty:
        print(f"  [{variant.alias}] no successful responses; skipping Vertex eval")
        return {}
    task = EvalTask(
        dataset=eval_df,
        metrics=[
            K8S_DEBUG_METRIC,
            MetricPromptTemplateExamples.Pointwise.GROUNDEDNESS,
            MetricPromptTemplateExamples.Pointwise.INSTRUCTION_FOLLOWING,
            MetricPromptTemplateExamples.Pointwise.VERBOSITY,
        ],
        experiment="gemma-4-k8s-debug",
    )
    run_name = f"run-{variant.alias}-{int(time.time())}"
    result = task.evaluate(experiment_run_name=run_name)
    return {k: float(v) for k, v in result.summary_metrics.items()
            if isinstance(v, (int, float))}


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--project", required=True, help="GCP project for Vertex AI")
    p.add_argument("--location", default="us-central1")
    p.add_argument("--dataset", default=Path(__file__).with_name("dataset.jsonl"), type=Path)
    p.add_argument("--out", default=Path(__file__).with_name("results"), type=Path)
    p.add_argument("--variants", default=",".join(v.alias for v in VARIANTS),
                   help="comma-separated subset of variant aliases to run")
    p.add_argument("--skip-vertex", action="store_true",
                   help="Run inference and computed metrics only; skip Vertex AI judges")
    args = p.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    df = load_dataset(args.dataset)

    if not args.skip_vertex:
        vertexai.init(project=args.project, location=args.location)

    selected = {v.alias: v for v in VARIANTS}
    targets = [selected[a] for a in args.variants.split(",") if a in selected]
    if not targets:
        raise SystemExit(f"no matching variants in {args.variants!r}")

    rows: list[dict] = []
    for variant in targets:
        print(f"\n=== {variant.alias} ===")
        responses = collect_responses(variant, df)
        responses.to_json(args.out / f"{variant.alias}-responses.jsonl",
                          orient="records", lines=True)

        row: dict = {"variant": variant.alias}
        row.update(computed_metrics(responses))
        if not args.skip_vertex:
            judge_metrics = evaluate_with_vertex(variant, responses)
            (args.out / f"{variant.alias}-summary.json").write_text(
                json.dumps(judge_metrics, indent=2)
            )
            row.update(judge_metrics)
        rows.append(row)

    summary = pd.DataFrame(rows).set_index("variant")
    summary.to_csv(args.out / "comparison.csv")
    print("\n=== Comparison ===")
    print(summary.to_string())


if __name__ == "__main__":
    main()
