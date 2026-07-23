#!/usr/bin/env python3
"""
eval_golden.py — Golden transcript evaluation with promotion gate.

Usage:
  # Skill adapter evaluation (existing)
  python eval_golden.py --skill messaging --adapter-path adapters/messaging --adapter-version v1 \
       --compare-to results/messaging_deployed_baseline.json

  # Orchestrator routing evaluation (new)
  python eval_golden.py --mode routing --centroids-bin orchestrator_assets/centroids.bin \
       --centroids-meta orchestrator_assets/centroids_meta.json \
       --compare-to results/routing_deployed_baseline.json
"""
import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Tuple, Any

import torch
import numpy as np
from transformers import AutoModelForCausalLM, AutoTokenizer


# ----------------------- Skill Adapter Evaluation (existing) -----------------------

def load_skill_config(skill: str) -> dict:
    import yaml
    with open(f"configs/{skill}.yaml", "r") as f:
        return yaml.safe_load(f)


def params_match(pred: dict, expected: dict) -> bool:
    return (pred.keys() == expected.keys()) and all(
        str(pred[k]).strip().lower() == str(expected[k]).strip().lower()
        if isinstance(expected[k], str) else pred[k] == expected[k]
        for k in pred
    )


def compare_results(new_results_path: str, baseline_path: str, label: str) -> int:
    with open(new_results_path, 'r') as f: new = json.load(f)
    with open(baseline_path, 'r') as f: old = json.load(f)

    regressions = []
    improvements = []

    for case_id, result in new.items():
        old_pass = old.get(case_id, {}).get("overall_pass", False)
        new_pass = result["overall_pass"]

        if old_pass and not new_pass:
            regressions.append(case_id)
        elif not old_pass and new_pass:
            improvements.append(case_id)

    print(f"\n=== Golden Transcript Check: {label} vs {baseline_path} ===")
    print(f"Overall: {sum(r['overall_pass'] for r in new.values())}/{len(new)} passed")
    print(f"Regressions: {len(regressions)} ({regressions})")
    print(f"Improvements: {len(improvements)}")

    return 1 if regressions else 0


def run_skill_evaluation(args) -> int:
    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    model = AutoModelForCausalLM.from_pretrained(
        args.base_model, torch_dtype=torch.float16, device_map="auto"
    )
    model.load_adapter(args.adapter_path)
    model.eval()

    config = load_skill_config(args.skill)
    system_prompt = config["system_prompt"]

    transcript_path = Path(f"golden_transcripts/{args.skill}.jsonl")
    results = {}

    with open(transcript_path, "r") as f:
        for line in f:
            case = json.loads(line)
            prompt = f"{system_prompt}\nScreen: {case['screen_state']}\nUser: {case['instruction']}"

            inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
            output_tokens = model.generate(**inputs, max_new_tokens=100)
            pred_text = tokenizer.decode(output_tokens[0], skip_special_tokens=True)

            try:
                match = re.search(r'\{.*\}', pred_text)
                prediction = json.loads(match.group(0))
                tool_match = prediction.get("tool") == case["expected_tool"]
                params_match_val = params_match(prediction.get("params", {}), case["expected_params"])
            except:
                tool_match, params_match_val = False, False

            results[case["id"]] = {
                "overall_pass": tool_match and params_match_val,
                "tool_match": tool_match,
                "params_match": params_match_val
            }

    output_filename = f"results/{args.skill}_{args.adapter_version}.json"
    Path(output_filename).parent.mkdir(parents=True, exist_ok=True)
    with open(output_filename, "w") as f:
        json.dump(results, f, indent=2)
    print(f"Evaluation complete. Results saved to {output_filename}")

    if args.compare_to:
        return compare_results(output_filename, args.compare_to, args.skill)
    return 0


# ----------------------- Orchestrator Routing Evaluation (new) -----------------------

def load_centroids(bin_path: str, meta_path: str) -> Tuple[np.ndarray, dict]:
    with open(meta_path, "r") as f:
        meta = json.load(f)
    centroids = np.fromfile(bin_path, dtype=np.float32).reshape(meta["num_routes"], meta["hidden_dim"])
    return centroids, meta


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Cosine similarity between vector a and matrix b (rows)."""
    a_norm = a / (np.linalg.norm(a) + 1e-8)
    b_norm = b / (np.linalg.norm(b, axis=1, keepdims=True) + 1e-8)
    return b_norm @ a_norm


def run_routing_evaluation(args) -> int:
    """Evaluate orchestrator routing using frozen base + baked centroids."""
    centroids, meta = load_centroids(args.centroids_bin, args.centroids_meta)
    routes = meta["routes"]
    route_names = [r["name"] for r in routes]
    thresholds = {r["name"]: r["threshold"] for r in routes}
    layer_idx = meta["layer_index"]

    print(f"Loading frozen base model: {args.base_model}")
    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        torch_dtype=torch.float16,
        device_map="auto",
        low_cpu_mem_usage=True,
    )
    model.eval()
    for p in model.parameters():
        p.requires_grad = False

    # Hook to capture layer -2 hidden states
    captured = {}

    def hook_fn(module, input, output):
        hidden = output[0] if isinstance(output, tuple) else output
        captured["hidden"] = hidden.detach()

    handle = model.model.layers[layer_idx].register_forward_hook(hook_fn)

    try:
        # Load routing golden transcripts
        transcript_path = Path("golden_transcripts/routing.jsonl")
        results = {}
        correct = 0
        total = 0

        with open(transcript_path, "r") as f:
            for line in f:
                case = json.loads(line)
                total += 1

                # Format same as centroid computation
                instruction_text = f"Screen: {case['screen_state']}\nUser: {case['instruction']}"

                inputs = tokenizer(
                    instruction_text,
                    return_tensors="pt",
                    padding=True,
                    truncation=True,
                    max_length=2048,
                ).to(model.device)

                with torch.inference_mode():
                    _ = model(**inputs)

                hidden = captured["hidden"]  # [1, seq, hidden]
                attention_mask = inputs["attention_mask"]
                last_idx = attention_mask.sum(dim=1) - 1  # [1]
                emb = hidden[0, last_idx[0]].float().cpu().numpy()  # [hidden]
                emb = emb / (np.linalg.norm(emb) + 1e-8)

                # Cosine similarity against all centroids
                sims = cosine_similarity(emb, centroids)  # [num_routes]
                best_idx = int(np.argmax(sims))
                best_score = float(sims[best_idx])
                predicted_route = route_names[best_idx]

                # Apply threshold
                if best_score < thresholds[predicted_route]:
                    predicted_route = "unresolved"

                expected_route = case["expected_route"]
                passed = predicted_route == expected_route
                if passed:
                    correct += 1

                results[case["id"]] = {
                    "overall_pass": passed,
                    "predicted_route": predicted_route,
                    "expected_route": expected_route,
                    "similarity": best_score,
                    "all_scores": {route_names[i]: float(sims[i]) for i in range(len(route_names))},
                }

        accuracy = correct / total if total > 0 else 0.0
        print(f"\n=== Routing Evaluation ===")
        print(f"Accuracy: {correct}/{total} = {accuracy:.3f}")
        print(f"Per-route scores logged in results")

        # Save results
        output_filename = f"results/routing_{args.adapter_version}.json"
        Path(output_filename).parent.mkdir(parents=True, exist_ok=True)
        with open(output_filename, "w") as f:
            json.dump(results, f, indent=2)
        print(f"Results saved to {output_filename}")

        if args.compare_to:
            return compare_results(output_filename, args.compare_to, "routing")
        return 0

    finally:
        handle.remove()


# ----------------------- Main -----------------------

def main():
    parser = argparse.ArgumentParser(description="Golden transcript evaluation")
    subparsers = parser.add_subparsers(dest="mode", required=True)

    # Skill adapter subcommand
    skill_parser = subparsers.add_parser("skill", help="Evaluate skill adapter")
    skill_parser.add_argument("--skill", required=True, choices=["messaging", "transactional", "analytical"])
    skill_parser.add_argument("--adapter-path", required=True)
    skill_parser.add_argument("--adapter-version", required=True)
    skill_parser.add_argument("--base-model", default="google/functiongemma-270m-it")
    skill_parser.add_argument("--compare-to", default=None)

    # Routing subcommand
    routing_parser = subparsers.add_parser("routing", help="Evaluate orchestrator routing")
    routing_parser.add_argument("--centroids-bin", required=True, help="Path to centroids.bin")
    routing_parser.add_argument("--centroids-meta", required=True, help="Path to centroids_meta.json")
    routing_parser.add_argument("--adapter-version", required=True, help="Version tag for results file")
    routing_parser.add_argument("--base-model", default="google/functiongemma-270m-it")
    routing_parser.add_argument("--compare-to", default=None)

    args = parser.parse_args()

    if args.mode == "skill":
        sys.exit(run_skill_evaluation(args))
    elif args.mode == "routing":
        sys.exit(run_routing_evaluation(args))


if __name__ == "__main__":
    main()