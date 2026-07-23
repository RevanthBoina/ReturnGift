import json
from pathlib import Path
from datasets import Dataset


def build_dataset(cfg, tokenizer):
    data_path = Path(cfg["dataset_path"])
    if not data_path.exists():
        raise FileNotFoundError(f"Dataset partition not found: {data_path}")

    rows = []
    with open(data_path, "r") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))

    if not rows:
        raise ValueError(f"{data_path} loaded but contained 0 rows")

    max_len = cfg.get("max_seq_length", 2048)

    def tokenize(example):
        # Real schema: {"messages": [{"role": "system"/"user"/"assistant", "content": ...}]}
        text = tokenizer.apply_chat_template(
            example["messages"], tokenize=False, add_generation_prompt=False
        )
        tok = tokenizer(text, truncation=True, max_length=max_len, padding="max_length")
        tok["labels"] = tok["input_ids"].copy()
        return tok

    ds = Dataset.from_list(rows)
    return ds.map(tokenize, remove_columns=ds.column_names)
