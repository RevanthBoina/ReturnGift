import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training

def build_model_and_tokenizer(cfg):
    base_model_name = cfg["base_model"]
    tokenizer = AutoTokenizer.from_pretrained(base_model_name)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        base_model_name,
        torch_dtype=torch.bfloat16,
        device_map="auto",
    )

    # Prepare for kbit training
    model = prepare_model_for_kbit_training(model)

    lora_cfg = cfg.get("lora", {})
    peft_config = LoraConfig(
        r=lora_cfg.get("r", 8),
        lora_alpha=lora_cfg.get("alpha", 16),
        lora_dropout=lora_cfg.get("dropout", 0.05),
        target_modules=lora_cfg.get("target_modules", ["q_proj", "v_proj"]),
        task_type="CAUSAL_LM",
        bias="none",
    )
    
    model = get_peft_model(model, peft_config)
    
    # Explicitly enable gradients
    for name, param in model.named_parameters():
        if "lora" in name:
            param.requires_grad = True
            
    model.print_trainable_parameters()
    return model, tokenizer