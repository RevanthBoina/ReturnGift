import os
import sys
import json
import yaml
import subprocess
from pathlib import Path
from datetime import datetime

def print_header(title):
    print("\n" + "=" * 65)
    print(f"⚡  {title:^57}  ⚡")
    print("=" * 65)

print_header("LIGHTNING STUDIO TRAINING PROGRESS MONITOR")

# 1. Check for running processes
print("\n[1] Active Training Processes:")
try:
    cmd = ["pgrep", "-f", "train_adapter.py"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    pids = res.stdout.strip().split()
    if pids:
        print(f"  🟢 Active training process detected! PID(s): {', '.join(pids)}")
        # Get active running command details
        ps_cmd = ["ps", "-fp", pids[0]]
        ps_res = subprocess.run(ps_cmd, capture_output=True, text=True)
        lines = ps_res.stdout.strip().splitlines()
        if len(lines) > 1:
            print(f"     📋 Process: {lines[1]}")
    else:
        print("  ⚪ No active 'train_adapter.py' training process detected in this session.")
except Exception as e:
    print(f"  ⚠️ Could not check running processes: {e}")

# 2. Check Adapter Build Outputs & Metrics
print("\n[2] Adapter Build & Loss Metric Progress:")
configs_dir = Path("configs")
skills = ["transactional", "messaging", "analytical"]

for skill in skills:
    print(f"\n  Skill: {skill.upper()}")
    cfg_path = configs_dir / f"{skill}.yaml"
    output_dir = Path(f"adapters/{skill}") # Default fallback
    
    if cfg_path.exists():
        try:
            with open(cfg_path, "r") as f:
                cfg = yaml.safe_load(f) or {}
                # Extract customized save directory if it exists
                output_dir = Path(cfg.get("output_adapter_dir") or cfg.get("output_dir") or f"adapters/{skill}")
        except Exception:
            pass
            
    print(f"    📁 Path: {output_dir}")
    if not output_dir.exists():
        print("    🔴 Status: Not started yet.")
        continue
        
    # Check for final compiled adapter file
    final_weights = list(output_dir.glob("**/adapter_model.safetensors"))
    if final_weights:
        mod_time = datetime.fromtimestamp(final_weights[0].stat().st_mtime).strftime('%Y-%m-%d %H:%M:%S')
        print(f"    🟢 Status: COMPLETE! Final adapter saved at {mod_time}")
    else:
        print("    🟡 Status: Training in progress (or incomplete).")

    # Check for Hugging Face checkpoint folders
    checkpoints = sorted(list(output_dir.glob("**/checkpoint-*")), key=os.path.getmtime)
    if checkpoints:
        latest_cp = checkpoints[-1]
        cp_time = datetime.fromtimestamp(latest_cp.stat().st_mtime).strftime('%Y-%m-%d %H:%M:%S')
        print(f"    📦 Latest Checkpoint: {latest_cp.name} (Updated: {cp_time})")
        
        # Pull trainer_state.json to extract the loss curves
        state_file = latest_cp / "trainer_state.json"
        if state_file.exists():
            try:
                with open(state_file, "r") as sf:
                    state = json.load(sf)
                    history = state.get("log_history", [])
                    metrics = [log for log in history if "loss" in log or "eval_loss" in log]
                    if metrics:
                        latest = metrics[-1]
                        step = latest.get("step")
                        loss = latest.get("loss", latest.get("eval_loss"))
                        epoch = latest.get("epoch")
                        print(f"       📈 Loss Curve: Step {step:<5} | Loss: {loss:.4f} | Epoch: {epoch:.2f}")
                    else:
                        print("       📈 Loss Curve: No metrics recorded in state logs yet.")
            except Exception as e:
                print(f"       ⚠️ Failed to read trainer state: {e}")
    else:
        print("    ⚪ Checkpoints: No checkpoints written to disk yet.")

print("\n" + "=" * 65)
print("🚀 MONITOR RUN COMPLETE")
print("=" * 65)
