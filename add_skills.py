import json
import os

input_file = "dataset.jsonl"
output_dir = "data/raw"
output_file = os.path.join(output_dir, "merged_filtered_dataset.jsonl")

os.makedirs(output_dir, exist_ok=True)

def classify_skill(row):
    if "skill" in row:
        return row["skill"]
        
    # Convert row dict to string to search for keyword matches
    text = str(row).lower()
    
    # Heuristics for transactional (tool use, function calling, api, control, etc.)
    transactional_keywords = ["tool", "function", "api", "call:", "declaration:", "parameter", "arguments", "json", "set alarm", "turn on", "flashlight", "calendar", "get_weather"]
    # Heuristics for analytical (math, logic, analysis, reasoning, etc.)
    analytical_keywords = ["math", "reason", "calculate", "analyze", "summarize", "explain", "why", "steps", "solve", "logic", "compare", "proof"]
    
    if any(k in text for k in transactional_keywords):
        return "transactional"
    elif any(k in text for k in analytical_keywords):
        return "analytical"
    else:
        return "messaging"

count = 0
with open(input_file, "r", encoding="utf-8") as f_in, open(output_file, "w", encoding="utf-8") as f_out:
    for line in f_in:
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            row["skill"] = classify_skill(row)
            f_out.write(json.dumps(row) + "\n")
            count += 1
        except Exception as e:
            pass

print(f"Dataset successfully labeled! Processed {count} rows. Output saved to {output_file}")
