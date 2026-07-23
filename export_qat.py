import argparse
import os
import sys
from pathlib import Path

import torch
import yaml
from transformers import AutoTokenizer, AutoModelForCausalLM
from peft import PeftModel

from torchao.quantization import (
    quantize_,
    Int4WeightOnlyConfig,
    Int8WeightOnlyConfig,
)


def load_config(path: str) -> dict:
    with open(path, "r") as f:
        return yaml.safe_load(f)


def build_quant_config(cfg: dict):
    """
    Reads an optional `quantization:` block from configs/<skill>.yaml, e.g.:

        quantization:
          weight_dtype: int4   # or int8
          group_size: 32       # only used for int4

    Falls back to int4/group_size=32 (a reasonable default for edge/mobile
    deployment) if the block is absent, but prints what it picked so you
    never silently quantize with the wrong precision.
    """
    q = cfg.get("quantization", {})
    weight_dtype = q.get("weight_dtype", "int4")
    group_size = q.get("group_size", 32)

    if weight_dtype == "int4":
        print(f"Quantization: weight-only int4, group_size={group_size}")
        return Int4WeightOnlyConfig(group_size=group_size)
    elif weight_dtype == "int8":
        print("Quantization: weight-only int8")
        return Int8WeightOnlyConfig()
    else:
        raise ValueError(
            f"Unsupported quantization.weight_dtype '{weight_dtype}' in config. "
            "Expected 'int4' or 'int8'."
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, help="Path to configs/<skill>.yaml")
    parser.add_argument(
        "--output-dir",
        default=None,
        help="Where to write the quantized checkpoint / edge artifact. "
        "Defaults to export/<skill_name>/",
    )
    parser.add_argument(
        "--skip-edge-export",
        action="store_true",
        help="Only produce the quantized PyTorch checkpoint; skip the "
        "litert_torch conversion to a deployable edge artifact.",
    )
    args = parser.parse_args()

    cfg = load_config(args.config)
    base_model_name = cfg["base_model"]
    skill_name = cfg["skill_name"]
    adapter_path = f"adapters/{skill_name}"
    output_dir = Path(args.output_dir or f"export/{skill_name}")
    output_dir.mkdir(parents=True, exist_ok=True)

    hf_token = os.environ.get("HF_TOKEN")
    if not hf_token:
        print(
            "WARNING: HF_TOKEN not set in environment. Loading will fail if "
            f"'{base_model_name}' is gated. Run: export HF_TOKEN=hf_xxx",
            file=sys.stderr,
        )

    print(f"Loading tokenizer and base model: {base_model_name}")
    tokenizer = AutoTokenizer.from_pretrained(base_model_name, token=hf_token)
    base_model = AutoModelForCausalLM.from_pretrained(
        base_model_name,
        dtype=torch.float16,  # transformers 4.57 renamed torch_dtype -> dtype
        device_map="auto",
    )

    print(f"Loading adapter from: {adapter_path}")
    peft_model = PeftModel.from_pretrained(base_model, adapter_path)

    print("Merging LoRA adapter into base weights...")
    # torchao's quantize_ operates on plain nn.Linear modules — it can't see
    # through a PeftModel's LoRA wrapper, so this merge is required before
    # quantization, not optional.
    model = peft_model.merge_and_unload()
    model.eval()

    quant_config = build_quant_config(cfg)

    print("Quantizing merged model with torchao...")
    quantize_(model, quant_config)
    print("Quantization complete.")

    quantized_ckpt_path = output_dir / "quantized_model"
    print(f"Saving quantized checkpoint to: {quantized_ckpt_path}")
    model.save_pretrained(quantized_ckpt_path)
    tokenizer.save_pretrained(quantized_ckpt_path)

    if args.skip_edge_export:
        print("Skipping edge export (--skip-edge-export set). Done.")
        return

    # --- Edge export via litert_torch (ai-edge-torch's successor) ---
    # This is the step that actually produces a deployable artifact; the
    # quantized checkpoint above is still a PyTorch model.
    try:
        import litert_torch
    except ImportError:
        print(
            "litert_torch not importable — quantized checkpoint was saved, "
            "but edge conversion was skipped. Install/check the environment "
            "if you need a deployable artifact.",
            file=sys.stderr,
        )
        return

    print("Converting to edge format via litert_torch...")
    sample_input_ids = tokenizer("placeholder calibration input", return_tensors="pt").input_ids

    try:
        edge_model = litert_torch.convert(model, (sample_input_ids,))
        edge_out_path = output_dir / f"{skill_name}.tflite"
        edge_model.export(str(edge_out_path))
        print(f"Edge artifact written to: {edge_out_path}")
    except Exception as e:
        # litert_torch's convert() signature/behavior is the newest, least
        # battle-tested piece of this stack — surface the real error instead
        # of masking it, since the exact call shape may need adjusting to
        # functiongemma-270m-it's actual input signature.
        print(
            f"Edge conversion failed: {e}\n"
            "Quantized PyTorch checkpoint is still saved and usable. "
            "Inspect litert_torch.convert()'s expected input signature "
            "for this model before retrying.",
            file=sys.stderr,
        )
        raise


if __name__ == "__main__":
    main()