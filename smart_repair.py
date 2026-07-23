import sys
import subprocess
import json
from pathlib import Path

def run_repair():
    print("=" * 60)
    print("🛠️  RUNNING SMART TERMINAL SELF-REPAIR  🛠️")
    print("=" * 60)

    # Step 1: Force active python env to downgrade NumPy
    print("\n[Step 1] Downgrading NumPy to stable 1.x in current environment...")
    try:
        subprocess.run([sys.executable, "-m", "pip", "install", "numpy<2", "--force-reinstall"], check=True)
        import numpy as np
        print(f"  🟢 NumPy successfully changed! Active version: {np.__version__}")
    except Exception as e:
        print(f"  🔴 Failed to downgrade NumPy: {e}")

    # Step 2: Verify Deep Transformers Imports
    print("\n[Step 2] Testing deep library imports...")
    try:
        from transformers import AutoModelForCausalLM, AutoTokenizer
        print("  🟢 Transformers library imported flawlessly!")
    except Exception as e:
        print(f"  🔴 Transformers is still broken: {e}")

    # Step 3: Audit Raw Dataset Skills
    print("\n[Step 3] Analyzing raw dataset skill labels...")
    raw_path = Path("data/raw/merged_filtered_dataset.jsonl")
    if not raw_path.exists():
        raw_path = Path("dataset.jsonl")
        
    if raw_path.exists():
        skill_counts = {}
        with open(raw_path, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    row = json.loads(line)
                    # Check root, metadata, or standard fields
                    skill = row.get("skill") or row.get("metadata", {}).get("skill", "unknown")
                    skill_counts[skill] = skill_counts.get(skill, 0) + 1
                except Exception:
                    pass
        print(f"  📊 Found skills in raw data: {skill_counts}")
        
        # If skills were under metadata, adjust data_prep.py on the fly!
        if "unknown" in skill_counts and len(skill_counts) == 1:
             print("  ⚠️ All records mapped to 'unknown'. Let's check deep keys in the first row:")
             with open(raw_path, "r") as f:
                 first = json.loads(f.readline())
                 print(f"  First Row Keys: {list(first.keys())}")
                 if "metadata" in first:
                     print(f"  Metadata Keys: {list(first['metadata'].keys())}")
    else:
        print("  🔴 Could not find a raw dataset file to analyze!")

    # Step 4: Re-run Partition Generation
    print("\n[Step 4] Attempting to re-partition dataset...")
    try:
        res = subprocess.run([sys.executable, "data_prep.py"], capture_output=True, text=True)
        print(res.stdout)
        if res.stderr:
            print(f"  Warnings/Errors:\n{res.stderr}")
    except Exception as e:
        print(f"  🔴 Failed to execute data_prep.py: {e}")

    # Step 5: Final Check on Partitions
    print("\n[Step 5] Checking output partitions...")
    partitions = ["messaging.jsonl", "transactional.jsonl", "analytical.jsonl"]
    for part in partitions:
        p_path = Path("data/partitions") / part
        if p_path.exists():
            lines = sum(1 for _ in open(p_path))
            print(f"  🟢 {part:<20}: {lines:,} rows ready.")
        else:
            print(f"  🔴 {part:<20}: MISSING.")

    print("\n" + "=" * 60)
    print("🎉 REPAIR PIPELINE COMPLETE")
    print("=" * 60)

if __name__ == "__main__":
    run_repair()
