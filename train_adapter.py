import os
os.environ["USE_TF"] = "0"
os.environ["USE_TORCH"] = "1"
import argparse
from pathlib import Path

from transformers import TrainingArguments, Trainer
from utils.training_utils import load_config
from utils.model_utils import build_model_and_tokenizer
from utils.data_utils import build_dataset

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, help="Path to a configs/<skill>.yaml file")
    args = parser.parse_args()

    cfg = load_config(args.config)
    skill = cfg["skill_name"]
    print(f"=== Training Action Skill adapter: {skill} ===")

    model, tokenizer = build_model_and_tokenizer(cfg)
    
    # --- SMART FIX: Force Training Mode and Verify Gradients ---
    model.train()
    model.enable_input_require_grads()
    
    # Freeze everything, then unfreeze LoRA
    for param in model.parameters():
        param.requires_grad = False
    for name, param in model.named_parameters():
        if "lora" in name:
            param.requires_grad = True

    model.print_trainable_parameters()
    # -----------------------------------------------------------

    train_ds = build_dataset(cfg, tokenizer)

    t = cfg["training"]
    training_args = TrainingArguments(
        output_dir=f"runs/{skill}",
        fp16=t.get("fp16", True),
        bf16=t.get("bf16", False),
        per_device_train_batch_size=t["per_device_train_batch_size"],
        gradient_accumulation_steps=t["gradient_accumulation_steps"],
        learning_rate=t["learning_rate"],
        warmup_ratio=t["warmup_ratio"],
        logging_steps=t["logging_steps"],
        save_strategy=t["save_strategy"],
        num_train_epochs=t["num_train_epochs"],
        report_to=[],
        gradient_checkpointing=t.get("gradient_checkpointing", True),
        remove_unused_columns=False, # Essential for custom datasets
    )

    trainer = Trainer(model=model, args=training_args, train_dataset=train_ds)
    trainer.train()

    # Save adapter
    out_dir = Path(cfg["output_adapter_dir"])
    out_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(out_dir, safe_serialization=True)
    tokenizer.save_pretrained(out_dir)
    print(f"Adapter saved to {out_dir}")

if __name__ == "__main__":
    main()