import yaml
from pathlib import Path


def load_config(path):
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(f"Config not found: {path}")
    with open(path, "r") as f:
        cfg = yaml.safe_load(f)
    required = ["skill_name", "base_model", "dataset_path", "output_adapter_dir", "training", "lora"]
    missing = [k for k in required if k not in cfg]
    if missing:
        raise KeyError(f"{path} is missing required keys: {missing}")
    return cfg
