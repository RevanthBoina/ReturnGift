import yaml
import re
from pathlib import Path

def print_header(title):
    print("\n" + "=" * 60)
    print(f"⚡  {title:^52}  ⚡")
    print("=" * 60)

# 1. Optimize Config YAMLs
print_header("STEP 1: OPTIMIZING CONFIGS FOR T4 GPU")
configs = ["configs/messaging.yaml", "configs/transactional.yaml", "configs/analytical.yaml"]
for cfg_name in configs:
    cfg_path = Path(cfg_name)
    if not cfg_path.exists():
        print(f"  ⚠️ Config not found: {cfg_name}")
        continue
    
    with open(cfg_path, "r") as f:
        cfg = yaml.safe_load(f) or {}
        
    # Apply memory optimizations
    cfg["per_device_train_batch_size"] = 2
    cfg["gradient_accumulation_steps"] = 8
    cfg["gradient_checkpointing"] = True
    cfg["fp16"] = True
    cfg["bf16"] = False
    if "max_seq_length" not in cfg:
        cfg["max_seq_length"] = 512
        
    # Update nested Trainer keys if present
    if "training_arguments" in cfg:
        ta = cfg["training_arguments"]
        ta["per_device_train_batch_size"] = 2
        ta["gradient_accumulation_steps"] = 8
        ta["gradient_checkpointing"] = True
        ta["fp16"] = True
        ta["bf16"] = False
        ta["max_seq_length"] = 512
        
    with open(cfg_path, "w") as f:
        yaml.safe_dump(cfg, f, default_flow_style=False)
    print(f"  🟢 {cfg_name:<25}: Config updated (Batch: 2, Accumulation: 8, FP16: True, GradCheckpointing: True)")

# 2. Safety Audit train_adapter.py
print_header("STEP 2: ENFORCING MEMORY LIMITS IN SCRIPT")
adapter_script = Path("train_adapter.py")
if adapter_script.exists():
    with open(adapter_script, "r") as f:
        code = f.read()
    
    # Locate where TrainingArguments is set up and ensure safety overrides are injected
    # We will dynamically inject safety args right after TrainingArguments creation
    modified = False
    
    # 1. Enforce gradient checkpointing and batch sizes directly in TrainingArguments instantiation
    pattern = r"TrainingArguments\("
    if re.search(pattern, code):
        # We append direct attribute overrides right after TrainingArguments is parsed to guarantee they apply
        replacement = "TrainingArguments(\n    gradient_checkpointing=True,\n    fp16=True,\n    bf16=False,\n    per_device_train_batch_size=2,\n    gradient_accumulation_steps=8,"
        code = re.sub(pattern, replacement, code, count=1)
        modified = True
        
    # 2. Enforce truncation length to prevent massive outliers from crashing the system
    if "max_length" not in code and "truncation" not in code:
        code = code.replace("tokenizer(", "tokenizer(truncation=True, max_length=512, ")
        modified = True
        
    if modified:
        with open(adapter_script, "w") as f:
            f.write(code)
        print("  🟢 train_adapter.py patched with strict safety parameters!")
    else:
        print("  ℹ️  train_adapter.py already optimized or structurally safe.")
else:
    print("  🔴 train_adapter.py not found!")

print("\n" + "=" * 60 + "\n")
