"""
Section 3 + Section 2 bridge.

Takes your already-merged, already-filtered dataset (Mobile Actions +
real ADB captures + Gemini-augmented rows, generic hardware/OS-control
rows removed) and:

  1. Splits it into the 3 in-memory dataframes / files matching the
     Action Skill split (messaging / transactional / analytical).
  2. Prepends the EXACT bespoke system prompt string for that skill
     (from Section 2 / configs/<skill>.yaml) as a fixed system turn
     on every row, before tokenization.

This is deliberately a separate, inspectable step from training so you
can diff the prepended prompt against configs/*.yaml before you burn
GPU hours on a mismatch (train/runtime prompt drift is the #1 way
these adapters quietly underperform).

Expected input format (adjust `load_raw_dataset` to your actual source):
    {
      "skill": "messaging" | "transactional" | "analytical",
      "screen_state": "<accessibility tree / OCR text>",
      "instruction": "<user instruction>",
      "tool_call": {"tool": "...", "params": {...}}
    }

Output: one JSONL file per skill under data/partitions/, each row shaped
as a chat-style example:
    {"messages": [
        {"role": "system", "content": <verbatim Section 2 prompt>},
        {"role": "user", "content": "<screen_state>\n\n<instruction>"},
        {"role": "assistant", "content": "<json.dumps(tool_call)>"}
    ]}
"""
import json
import sys
import yaml
from pathlib import Path
from collections import defaultdict

CONFIG_DIR = Path("configs")
OUTPUT_DIR = Path("data/partitions")
RAW_DATASET_PATH = Path("data/raw/merged_filtered_dataset.jsonl")  # your Step 3 output


def load_skill_prompts():
    """Read the verbatim system prompt for each skill straight from the
    yaml configs, so this script can never drift from configs/*.yaml."""
    prompts = {}
    if not CONFIG_DIR.exists():
        raise FileNotFoundError(f"Config dir not found: {CONFIG_DIR.resolve()}")

    for cfg_path in CONFIG_DIR.glob("*.yaml"):
        cfg = yaml.safe_load(cfg_path.read_text())
        try:
            skill = cfg["skill_name"]
            prompt = cfg["system_prompt"]
        except KeyError as e:
            raise KeyError(
                f"{cfg_path} is missing required key {e}. "
                "Every configs/*.yaml must define 'skill_name' and 'system_prompt'."
            ) from e
        # yaml `>` folded scalars collapse newlines to spaces; strip trailing
        # whitespace so the string is stable/deterministic across loads.
        prompts[skill] = prompt.strip()
    return prompts


def load_raw_dataset(path: Path):
    if not path.exists():
        raise FileNotFoundError(
            f"Raw dataset not found at {path.resolve()}. "
            "Did the Step 3 merge/filter run produce this file?"
        )
    with path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                yield json.loads(line)


def build_example(row: dict, system_prompt: str) -> dict:
    user_content = ""
    assistant_content = ""

    # 1. Try extracting from standard 'messages' conversational structure
    if "messages" in row and isinstance(row["messages"], list):
        for msg in row["messages"]:
            if msg.get("role") == "user":
                user_content = msg.get("content", "")
            elif msg.get("role") == "assistant":
                if "tool_calls" in msg and msg["tool_calls"]:
                    assistant_content = json.dumps(msg["tool_calls"], separators=(",", ":"))
                else:
                    assistant_content = msg.get("content", "")

    # 2. Fallbacks to prevent KeyErrors
    if not user_content:
        user_content = f"{row.get('screen_state', '')}\n\n{row.get('instruction', '')}".strip()

    if not assistant_content:
        tool_call = row.get("tool_call")
        if tool_call:
            assistant_content = json.dumps(tool_call, separators=(",", ":"))
        else:
            assistant_content = row.get("response", "")

    return {
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content},
            {"role": "assistant", "content": assistant_content},
        ]
    }


def main():
    prompts = load_skill_prompts()
    print(f"Loaded system prompts for skills: {list(prompts)}")

    buckets = defaultdict(list)
    skipped_unknown_skill = 0
    empty_assistant = 0

    for row in load_raw_dataset(RAW_DATASET_PATH):
        skill = row.get("skill")
        if skill not in prompts:
            skipped_unknown_skill += 1
            continue
        example = build_example(row, prompts[skill])
        if not example["messages"][2]["content"]:
            # Silently-empty targets train the model to output "" — surface this
            # instead of letting it disappear into the partition file.
            empty_assistant += 1
        buckets[skill].append(example)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for skill, examples in buckets.items():
        out_path = OUTPUT_DIR / f"{skill}.jsonl"
        with out_path.open("w") as f:
            for ex in examples:
                f.write(json.dumps(ex) + "\n")
        print(f"  {skill}: {len(examples)} rows -> {out_path}")

    if skipped_unknown_skill:
        print(f"Skipped {skipped_unknown_skill} rows with unrecognized/missing 'skill' field.")
    if empty_assistant:
        print(
            f"WARNING: {empty_assistant} rows have an EMPTY assistant target "
            "(no tool_call, no response, no assistant message content). "
            "These will still be written to the partition files — inspect them "
            "before training, they will teach the model to emit empty output.",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()