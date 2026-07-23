import re

with open("data_prep.py", "r", encoding="utf-8") as f:
    code = f.read()

# Replace the original build_example function with a robust version
old_func = """def build_example(row: dict, system_prompt: str) -> dict:
    user_content = f"{row['screen_state']}\\n\\n{row['instruction']}"
    assistant_content = json.dumps(row["tool_call"], separators=(",", ":"))
    return {
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content},
            {"role": "assistant", "content": assistant_content},
        ]
    }"""

new_func = """def build_example(row: dict, system_prompt: str) -> dict:
    import json
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
        user_content = f"{row.get('screen_state', '')}\\n\\n{row.get('instruction', '')}".strip()
        
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
    }"""

# Direct replacement of the function
if old_func in code:
    code = code.replace(old_func, new_func)
else:
    # Use robust regex replacement if whitespace differed slightly
    pattern = r"def build_example\(row:\s*dict,\s*system_prompt:\s*str\)\s*->\s*dict:.*?\n\s*return\s*\{.*?\}"
    code = re.sub(pattern, new_func, code, flags=re.DOTALL)

# Redirect input to the merged/filtered labeled dataset
code = code.replace('"dataset.jsonl"', '"data/raw/merged_filtered_dataset.jsonl"')
code = code.replace("'dataset.jsonl'", "'data/raw/merged_filtered_dataset.jsonl'")

# Use safe get for skill to prevent any KeyError
code = re.sub(r'row\["skill"\]', 'row.get("skill", "messaging")', code)
code = re.sub(r"row\['skill'\]", "row.get('skill', 'messaging')", code)

with open("data_prep.py", "w", encoding="utf-8") as f:
    f.write(code)

print("data_prep.py patched successfully!")
