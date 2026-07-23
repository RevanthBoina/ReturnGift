#!/usr/bin/env python3
"""
package_orchestrator_assets.py

Packages centroids + metadata + tokenizer into Android raw assets.
Run after compute_centroids.py to produce app/src/main/assets/orchestrator/
"""
import argparse
import json
import shutil
from pathlib import Path


def package_assets(centroids_dir: Path, tokenizer_path: Path, output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)

    # Copy centroids.bin
    shutil.copy2(centroids_dir / "centroids.bin", output_dir / "centroids.bin")
    print(f"Copied centroids.bin -> {output_dir}/centroids.bin")

    # Copy centroids_meta.json
    shutil.copy2(centroids_dir / "centroids_meta.json", output_dir / "centroids_meta.json")
    print(f"Copied centroids_meta.json -> {output_dir}/centroids_meta.json")

    # Copy tokenizer vocab (SentencePiece model or vocab.json)
    if tokenizer_path.exists():
        shutil.copy2(tokenizer_path, output_dir / "tokenizer.model")
        print(f"Copied tokenizer -> {output_dir}/tokenizer.model")
    else:
        print(f"WARNING: Tokenizer not found at {tokenizer_path}")

    # Verify assets
    meta_path = output_dir / "centroids_meta.json"
    with open(meta_path, "r") as f:
        meta = json.load(f)

    bin_path = output_dir / "centroids.bin"
    expected_bytes = meta["num_routes"] * meta["hidden_dim"] * 4
    actual_bytes = bin_path.stat().st_size
    assert actual_bytes == expected_bytes, f"Size mismatch: {actual_bytes} != {expected_bytes}"

    print(f"\nPackaged successfully:")
    print(f"  Routes: {meta['num_routes']}")
    print(f"  Hidden dim: {meta['hidden_dim']}")
    print(f"  Model base: {meta['model_base']}")
    print(f"  Layer index: {meta['layer_index']}")
    print(f"  Pooling: {meta['pooling']}")
    for r in meta["routes"]:
        print(f"    {r['name']}: threshold={r['threshold']}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--centroids-dir", default="orchestrator_assets", help="Output from compute_centroids.py")
    parser.add_argument("--tokenizer", default="tokenizer.model", help="SentencePiece model file")
    parser.add_argument("--output-dir", default="app/src/main/assets/orchestrator", help="Android assets dir")
    args = parser.parse_args()

    package_assets(Path(args.centroids_dir), Path(args.tokenizer), Path(args.output_dir))


if __name__ == "__main__":
    main()