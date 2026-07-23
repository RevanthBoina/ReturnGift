import argparse
import json
import yaml
import re
import os
import sys
from transformers import AutoModelForCausalLM, AutoTokenizer

def load_config(skill):
    with open(f"configs/{skill}.yaml", "r") as f:
        return yaml.safe_load(f)

def params_match(pred, expected):
    return (pred.keys() == expected.keys()) and all(
        str(pred[k]).strip().lower() == str(expected[k]).strip().lower()
        if isinstance(expected[k], str) else pred[k] == expected[k]
        for k in pred)

def compare_results(new_results_path, baseline_path, skill):
    with open(new_results_path, 'r') as f: new = json.load(f)
    with open(baseline_path, 'r') as f: old = json.load(f)
    
    regressions = []
    improvements = []
    
    for case_id, result in new.items():
        old_pass = old.get(case_id, {}).get("overall_pass", False)
        new_pass = result["overall_pass"]
        
        if old_pass and not new_pass:
            regressions.append(case_id)
        elif not old_pass and new_pass:
            improvements.append(case_id)
            
    print(f"\n=== Golden Transcript Check: {skill} vs {baseline_path} ===")
    print(f"Overall: {sum(r['overall_pass'] for r in new.values())}/{len(new)} passed")
    print(f"Regressions: {len(regressions)} ({regressions})")
    print(f"Improvements: {len(improvements)}")
    
    return 1 if regressions else 0

def run_evaluation(args):
    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    model = AutoModelForCausalLM.from_pretrained(args.base_model)
    model.load_adapter(args.adapter_path)
    model.eval()
    
    config = load_config(args.skill)
    system_prompt = config["system_prompt"]
    
    transcript_path = f"golden_transcripts/{args.skill}.jsonl"
    results = {}
    
    with open(transcript_path, "r") as f:
        for line in f:
            case = json.loads(line)
            prompt = f"{system_prompt}\nScreen: {case['screen_state']}\nUser: {case['instruction']}"
            
            inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
            output_tokens = model.generate(**inputs, max_new_tokens=100)
            pred_text = tokenizer.decode(output_tokens[0], skip_special_tokens=True)
            
            try:
                match = re.search(r'\{.*\}', pred_text)
                prediction = json.loads(match.group(0))
                tool_match = prediction.get("tool") == case["expected_tool"]
                params_match_val = params_match(prediction.get("params", {}), case["expected_params"])
            except:
                tool_match, params_match_val = False, False
            
            results[case["id"]] = {
                "overall_pass": tool_match and params_match_val,
                "tool_match": tool_match,
                "params_match": params_match_val
            }
            
    output_filename = f"results/{args.skill}_{args.adapter_version}.json"
    with open(output_filename, "w") as f:
        json.dump(results, f, indent=2)
    print(f"Evaluation complete. Results saved to {output_filename}")
    
    if args.compare_to:
        exit_code = compare_results(output_filename, args.compare_to, args.skill)
        sys.exit(exit_code)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--skill", required=True)
    parser.add_argument("--adapter-path", required=True)
    parser.add_argument("--adapter-version", required=True)
    parser.add_argument("--base-model", default="google/functiongemma-270m-it")
    parser.add_argument("--compare-to", default=None)
    args = parser.parse_args()
    
    run_evaluation(args)