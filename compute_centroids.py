#!/usr/bin/env python3
"""
compute_centroids.py — Build orchestrator centroids from frozen FunctionGemma base.

Run ONCE at build time (in Lightning AI studio) to produce:
  - orchestrator_assets/centroids.bin      (flat float32: num_routes x hidden_dim)
  - orchestrator_assets/centroids_meta.json (metadata for Kotlin loader)

These assets get packaged into the APK under assets/orchestrator/.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def load_route_definitions(path: str) -> dict:
    with open(path, "r") as f:
        return json.load(f)


def load_model_and_tokenizer(model_name: str, device: str):
    print(f"Loading {model_name} on {device}...")
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        torch_dtype=torch.float16 if device == "cuda" else torch.float32,
        device_map="auto" if device == "cuda" else None,
        low_cpu_mem_usage=True,
    )
    model.eval()
    for p in model.parameters():
        p.requires_grad = False
    return model, tokenizer


def compute_centroids(
    model,
    tokenizer,
    route_defs: dict,
    layer_idx: int,
    device: str,
) -> tuple:
    """
    Returns (centroids: np.ndarray[num_routes, hidden_dim], hidden_dim: int)
    """
    routes = route_defs["routes"]
    num_routes = len(routes)
    hidden_dim = None
    centroids_list = []

    # Hook to capture layer output
    captured = {}
    def hook_fn(module, input, output):
        hidden = output[0] if isinstance(output, tuple) else output
        captured["hidden"] = hidden.detach()

    handle = model.model.layers[layer_idx].register_forward_hook(hook_fn)

    try:
        for route in routes:
            name = route["name"]
            utterances = route["utterances"]
            print(f"  Processing route '{name}' ({len(utterances)} utterances)...")

            route_embs = []
            for utt in utterances:
                # Same input format as runtime: "Screen: \nUser: <utterance>"
                text = f"Screen: \nUser: {utt}"

                inputs = tokenizer(
                    text,
                    return_tensors="pt",
                    padding=True,
                    truncation=True,
                    max_length=2048,
                ).to(device)

                with torch.inference_mode():
                    _ = model(**inputs)

                hidden = captured["hidden"]  # [1, seq_len, hidden]
                attn_mask = inputs["attention_mask"]
                last_idx = attn_mask.sum(dim=1) - 1  # [1]

                emb = hidden[0, last_idx[0]].float().cpu().numpy()  # [hidden]
                if hidden_dim is None:
                    hidden_dim = emb.shape[0]

                emb = emb / (np.linalg.norm(emb) + 1e-8)
                route_embs.append(emb)

            centroid = np.mean(np.stack(route_embs), axis=0)
            centroid = centroid / (np.linalg.norm(centroid) + 1e-8)
            centroids_list.append(centroid)

        centroids = np.stack(centroids_list)  # [num_routes, hidden_dim]
        return centroids, hidden_dim

    finally:
        handle.remove()


def write_assets(centroids: np.ndarray, hidden_dim: int, route_defs: dict, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)

    # Binary: flat float32 [num_routes, hidden_dim]
    bin_path = out_dir / "centroids.bin"
    centroids.astype(np.float32).tofile(bin_path)
    print(f"Wrote {bin_path} ({centroids.shape[0]} routes x {centroids.shape[1]} dims, {centroids.nbytes} bytes)")

    # Metadata JSON
    routes = route_defs["routes"]
    meta = {
        "version": route_defs.get("version", "1"),
        "modelBase": route_defs.get("model_base", "google/functiongemma-270m-it"),
        "layerIndex": route_defs.get("layer_index", -2),
        "pooling": route_defs.get("pooling", "mean_last_token"),
        "numRoutes": len(routes),
        "hiddenDim": hidden_dim,
        "routes": [
            {"name": r["name"], "threshold": r["threshold"]}
            for r in routes
        ],
    }
    meta_path = out_dir / "centroids_meta.json"
    with open(meta_path, "w") as f:
        json.dump(meta, f, indent=2)
    print(f"Wrote {meta_path}")

    # Sanity check: print similarity matrix
    sim = centroids @ centroids.T
    names = [r["name"] for r in routes]
    print("\nCentroid cosine similarity matrix:")
    print("       " + "  ".join(f"{n:>12}" for n in names))
    for i, row in enumerate(sim):
        print(f"{names[i]:>8} " + "  ".join(f"{v:.3f}" for v in row))


def main():
    parser = argparse.ArgumentParser(description="Compute orchestrator centroids")
    parser.add_argument("--route-defs", default="route_definitions.json")
    parser.add_argument("--base-model", default="google/functiongemma-270m-it")
    parser.add_argument("--layer-index", type=int, default=-2, help="Negative = from end")
    parser.add_argument("--output-dir", default="orchestrator_assets")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()

    route_defs = load_route_definitions(args.route_defs)
    model, tokenizer = load_model_and_tokenizer(args.base_model, args.device)

    # Resolve layer index
    num_layers = len(model.model.layers)
    layer_idx = args.layer_index if args.layer_index >= 0 else num_layers + args.layer_index
    print(f"Using layer {layer_idx} (0-indexed, {num_layers} total layers)")

    centroids, hidden_dim = compute_centroids(model, tokenizer, route_defs, layer_idx, args.device)
    write_assets(centroids, hidden_dim, route_defs, Path(args.output_dir))

    print("\nDone. Copy orchestrator_assets/ to Android app assets/orchestrator/")
    print("Then run: ./gradlew assembleDebug")


if __name__ == "__main__":
    main()