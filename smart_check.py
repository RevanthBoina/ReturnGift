import os
import sys
from pathlib import Path

def run_diagnostics():
    print("=" * 50)
    print("⚡  LIGHTNING STUDIO WORKSPACE DIAGNOSTIC  ⚡")
    print("=" * 50)

    # 1. Check HF Token (Crucial for gated Gemma access)
    print("\n[1] Hugging Face Access:")
    hf_token = os.getenv("HF_TOKEN")
    if hf_token:
        print("  🟢 HF_TOKEN is loaded in this terminal environment.")
    else:
        print("  🔴 HF_TOKEN is NOT set!")
        print("     ℹ️  Note: 'google/functiongemma-270m-it' is a gated model.")
        print("     👉 Action: Run 'export HF_TOKEN=your_huggingface_token' in your terminal.")

    # 2. Check Directories & Configs
    print("\n[2] Configurations (configs/):")
    configs = ["messaging.yaml", "transactional.yaml", "analytical.yaml"]
    for cfg in configs:
        path = Path("configs") / cfg
        if path.exists():
            print(f"  🟢 Found {path}")
        else:
            print(f"  🔴 Missing {path}")

    # 3. Check Dataset Partitions
    print("\n[3] Partition Files (data/partitions/):")
    partitions = ["messaging.jsonl", "transactional.jsonl", "analytical.jsonl"]
    for part in partitions:
        path = Path("data/partitions") / part
        if path.exists():
            lines = sum(1 for _ in open(path))
            print(f"  🟢 Found {path} ({lines:,} records)")
        else:
            print(f"  🔴 Missing {path}")

    # 4. Check Python Dependencies
    print("\n[4] Library Compatibility:")
    packages = ["transformers", "peft", "ai_edge_torch", "torch", "numpy"]
    for pkg in packages:
        try:
            mod = __import__(pkg)
            version = getattr(mod, "__version__", "Installed")
            print(f"  🟢 {pkg:<15} : v{version}")
        except ImportError:
            print(f"  🔴 {pkg:<15} : NOT INSTALLED")

    print("\n" + "=" * 50)
    print("🚀 DIAGNOSTIC COMPLETE")
    print("=" * 50)

if __name__ == "__main__":
    run_diagnostics()
