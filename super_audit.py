import os
import sys
import subprocess
import json
from pathlib import Path

def print_header(title):
    print("\n" + "=" * 65)
    print(f"⚡  {title:^57}  ⚡")
    print("=" * 65)

def run_pip(args):
    print(f"  👉 Running: pip {' '.join(args)}...")
    res = subprocess.run([sys.executable, "-m", "pip"] + args, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"  🔴 Error: {res.stderr.strip()}")
    else:
        print("  🟢 Done!")

# --- 1. ENV REPAIR ---
print_header("STEP 1: ALIGNING BINARIES (NUMPY 1.X + SCIPY + SKLEARN)")

# Force-install SciPy < 1.13 to ensure it is fully binary compatible with NumPy 1.26.4
run_pip(["install", "scipy<1.13", "scikit-learn", "--force-reinstall", "--quiet"])

# --- 2. IMPORT VALIDATION ---
print_header("STEP 2: VERIFYING DEEP IMPORTS")
try:
    import scipy
    print(f"  🟢 SciPy imported successfully (v{scipy.__version__})")
    import sklearn
    print(f"  🟢 Scikit-Learn imported successfully (v{sklearn.__version__})")
    from transformers import AutoModelForCausalLM
    print("  🎉 SUCCESS: Transformers & AutoModelForCausalLM are fully functional!")
except Exception as e:
    print(f"  🔴 Critical Import Error: {e}")

# --- 3. DATASET AUDIT ---
print_header("STEP 3: SEARCHING FOR SKILL LABELS IN RAW DATA")

def audit_file(file_path):
    path = Path(file_path)
    if not path.exists():
        print(f"  ⚠️ File not found: {file_path}")
        return
    
    skill_counts = {}
    total_rows = 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            total_rows += 1
            try:
                row = json.loads(line)
                # Try finding the skill in different locations
                skill = row.get("skill")
                if not skill and "metadata" in row:
                    skill = row["metadata"].get("skill")
                
                skill = skill or "unlabeled"
                skill_counts[skill] = skill_counts.get(skill, 0) + 1
            except Exception:
                pass
                
    print(f"  📊 {path.name} ({total_rows:,} total rows):")
    for skill, count in skill_counts.items():
        print(f"     - {skill}: {count:,} rows")

# Audit the root dataset
audit_file("dataset.jsonl")
# Audit the filtered/merged dataset
audit_file("data/raw/merged_filtered_dataset.jsonl")

# --- 4. CONFIG CHECK ---
print_header("STEP 4: OUTPUT PARTITIONS STATUS")
partitions_dir = Path("data/partitions")
if partitions_dir.exists():
    for f in partitions_dir.glob("*.jsonl"):
        lines = sum(1 for _ in open(f))
        print(f"  🟢 {f.name:<25}: {lines:,} rows ready.")
else:
    print("  🔴 data/partitions/ directory does not exist.")

print("\n" + "=" * 65 + "\n")
