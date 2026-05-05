"""Tool-calling eval for Gemma 4 quantization variants.

Sibling to run_eval.py: rather than judging final-answer JSON quality, this
harness checks whether each variant emits the right tool call(s) with the
right arguments when given the agent's K8sTools schemas.

Computed metrics (deterministic, no judge):
  - tool_call_emitted    : did the model produce any tool_call (vs free text)?
  - tool_count_match     : did the count of tool_calls equal the reference count?
  - tool_name_match      : per-position tool name equality (mean over rows)
  - tool_param_key_match : per-call required-key coverage (mean)
  - tool_param_kv_match  : per-call required-key + value equality (mean)
  - tool_call_full_match : all of the above true for the whole row (strict)

Optional Vertex AI rubric metric scores "tool selection quality" — whether
the model's choice is reasonable given the prompt, even when not exactly
equal to the reference (useful when more than one tool would correctly
answer the prompt).

Usage:
  ./port_forward.sh                              # in another shell
  python run_tool_eval.py --project YOUR_GCP --location us-central1
  python run_tool_eval.py --project YOUR_GCP --skip-vertex
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


TOOL_SELECTION_METRIC = PointwiseMetric(
    metric="tool_selection_quality",
    metric_prompt_template=PointwiseMetricPromptTemplate(
        criteria={
            "right_tool_chosen": (
                "The model's tool_calls choose function(s) appropriate for the user's "
                "request. The reference tool_calls represent one valid solution; "
                "alternative tools that would also satisfy the request can score high."
            ),
            "argument_correctness": (
                "Argument values are extracted correctly from the prompt (namespace, "
                "podName, labelSelector, etc.) — no fabricated values."
            ),
            "no_extraneous_calls": (
                "The model does not emit unrelated tool calls (e.g., fetching logs "
                "when the user only asked for events)."
            ),
        },
        rating_rubric={
            "5": "Tool(s) and arguments match the reference exactly OR are equivalently correct.",
            "4": "Right tool chosen with one minor argument issue (case, missing optional, etc.).",
            "3": "Right tool chosen but at least one argument is wrong or fabricated.",
            "2": "Reasonable but not the best tool, or extra unrelated calls.",
            "1": "Wrong tool, no tool call, or arguments unrelated to the prompt.",
        },
        input_variables=["prompt", "reference"],
    ),
)


def load_dataset(path: Path) -> pd.DataFrame:
    rows = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
    df = pd.DataFrame(rows)
    missing = {"scenario_id", "prompt", "reference_tool_calls"} - set(df.columns)
    if missing:
        raise ValueError(f"dataset missing columns: {missing}")
    return df


def load_tools(path: Path) -> list[dict]:
    return json.loads(path.read_text())


def call_variant(variant: Variant, prompt: str, tools: list[dict],
                 max_tokens: int = 512) -> tuple[list[dict], str]:
    client = OpenAI(base_url=variant.base_url, api_key="not-needed", timeout=120)
    chat = client.chat.completions.create(
        model=variant.served_model_name,
        messages=[{"role": "user", "content": prompt}],
        tools=tools,
        tool_choice="auto",
        max_tokens=max_tokens,
        temperature=0.0,
    )
    msg = chat.choices[0].message
    calls: list[dict] = []
    for tc in (msg.tool_calls or []):
        try:
            args = json.loads(tc.function.arguments)
        except (json.JSONDecodeError, TypeError):
            args = {}
        calls.append({"name": tc.function.name, "arguments": args})
    return calls, (msg.content or "")


def collect_responses(variant: Variant, df: pd.DataFrame, tools: list[dict]) -> pd.DataFrame:
    out = df.copy()
    actual_calls, latencies, errors, fallback_text = [], [], [], []
    for _, row in out.iterrows():
        t0 = time.perf_counter()
        try:
            calls, text = call_variant(variant, row["prompt"], tools)
            err = ""
        except Exception as e:
            calls, text = [], ""
            err = f"{type(e).__name__}: {e}"
        latencies.append(round(time.perf_counter() - t0, 3))
        actual_calls.append(calls)
        fallback_text.append(text)
        errors.append(err)
        print(f"  [{variant.alias}] {row['scenario_id']}: {latencies[-1]}s "
              f"calls={len(calls)}{' ERROR ' + err if err else ''}")
    out["actual_tool_calls"] = actual_calls
    out["fallback_text"] = fallback_text
    out["latency_s"] = latencies
    out["error"] = errors
    return out


def compare_call(actual: dict, reference: dict, required_keys: set[str]) -> dict[str, bool]:
    name_match = actual.get("name") == reference.get("name")
    actual_args = actual.get("arguments") or {}
    ref_args = reference.get("arguments") or {}
    keys_present = all(k in actual_args for k in required_keys)
    kv_match = name_match and keys_present and all(
        str(actual_args.get(k)).strip() == str(ref_args.get(k)).strip()
        for k in required_keys
    )
    return {
        "name_match": name_match,
        "key_match": name_match and keys_present,
        "kv_match": kv_match,
    }


def computed_metrics(df: pd.DataFrame) -> dict[str, float]:
    if df.empty:
        return {}
    rows = df[df["error"] == ""]
    if rows.empty:
        return {"tool_call_emitted": 0.0}
    total = len(rows)
    emitted = sum(1 for c in rows["actual_tool_calls"] if c)
    count_match = 0
    name_scores, key_scores, kv_scores = [], [], []
    full_match = 0
    for _, row in rows.iterrows():
        actual: list = row["actual_tool_calls"]
        reference: list = row["reference_tool_calls"]
        if len(actual) == len(reference):
            count_match += 1
        n = max(len(actual), len(reference))
        if n == 0:
            continue
        name_hits = key_hits = kv_hits = 0
        for i in range(n):
            a = actual[i] if i < len(actual) else {}
            r = reference[i] if i < len(reference) else {}
            req_keys = set((r.get("arguments") or {}).keys())
            cmp = compare_call(a, r, req_keys)
            name_hits += int(cmp["name_match"])
            key_hits += int(cmp["key_match"])
            kv_hits += int(cmp["kv_match"])
        name_scores.append(name_hits / n)
        key_scores.append(key_hits / n)
        kv_scores.append(kv_hits / n)
        if (len(actual) == len(reference)
                and name_hits == n and key_hits == n and kv_hits == n):
            full_match += 1
    return {
        "tool_call_emitted": emitted / total,
        "tool_count_match": count_match / total,
        "tool_name_match": round(sum(name_scores) / len(name_scores), 3) if name_scores else 0.0,
        "tool_param_key_match": round(sum(key_scores) / len(key_scores), 3) if key_scores else 0.0,
        "tool_param_kv_match": round(sum(kv_scores) / len(kv_scores), 3) if kv_scores else 0.0,
        "tool_call_full_match": full_match / total,
        "avg_latency_s": round(rows["latency_s"].mean(), 3),
    }


def evaluate_with_vertex(variant: Variant, df: pd.DataFrame) -> dict[str, float]:
    valid = df[df["error"] == ""].copy()
    if valid.empty:
        return {}
    valid["response"] = valid["actual_tool_calls"].apply(
        lambda calls: json.dumps(calls) if calls else "(no tool call emitted)"
    )
    valid["reference"] = valid["reference_tool_calls"].apply(json.dumps)
    eval_df = valid[["prompt", "response", "reference"]].reset_index(drop=True)
    task = EvalTask(
        dataset=eval_df,
        metrics=[TOOL_SELECTION_METRIC],
        experiment="gemma-4-k8s-tools",
    )
    result = task.evaluate(experiment_run_name=f"run-{variant.alias}-{int(time.time())}")
    return {k: float(v) for k, v in result.summary_metrics.items()
            if isinstance(v, (int, float))}


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--project", required=True)
    p.add_argument("--location", default="us-central1")
    p.add_argument("--dataset", default=Path(__file__).with_name("tool_dataset.jsonl"), type=Path)
    p.add_argument("--tools", default=Path(__file__).with_name("tools_schema.json"), type=Path)
    p.add_argument("--out", default=Path(__file__).with_name("results"), type=Path)
    p.add_argument("--variants", default=",".join(v.alias for v in VARIANTS))
    p.add_argument("--skip-vertex", action="store_true")
    args = p.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    df = load_dataset(args.dataset)
    tools = load_tools(args.tools)

    if not args.skip_vertex:
        vertexai.init(project=args.project, location=args.location)

    selected = {v.alias: v for v in VARIANTS}
    targets = [selected[a] for a in args.variants.split(",") if a in selected]
    if not targets:
        raise SystemExit(f"no matching variants in {args.variants!r}")

    rows: list[dict] = []
    for variant in targets:
        print(f"\n=== {variant.alias} ===")
        responses = collect_responses(variant, df, tools)
        responses.to_json(args.out / f"{variant.alias}-tool-responses.jsonl",
                          orient="records", lines=True)

        row: dict = {"variant": variant.alias}
        row.update(computed_metrics(responses))
        if not args.skip_vertex:
            judge_metrics = evaluate_with_vertex(variant, responses)
            (args.out / f"{variant.alias}-tool-summary.json").write_text(
                json.dumps(judge_metrics, indent=2)
            )
            row.update(judge_metrics)
        rows.append(row)

    summary = pd.DataFrame(rows).set_index("variant")
    summary.to_csv(args.out / "tool-comparison.csv")
    print("\n=== Tool-use comparison ===")
    print(summary.to_string())


if __name__ == "__main__":
    main()
