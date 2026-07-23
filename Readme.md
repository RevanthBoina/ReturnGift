# ReturnGift — Step 4: Fine-Tuning
 
This folder implements the two open TO-DO items tied to Step 4:
 
```
[ ] Lightning AI environment set up (migrated from Colab at Step 4)
[ ] 3 Action Skill adapters fine-tuned (16-bit base -> QAT int4 export)
```
 
## 1. Lightning AI environment setup
 
Unlike Colab, Lightning Studio persists state, so you don't need the
single-notebook loop workaround anymore — you can split work across
scripts/sessions freely.
 
1. Create a Lightning Studio (GPU tier depends on FunctionGemma-270M
   size — a single mid-tier GPU is comfortably enough for a 270M model).
2. In the Studio terminal:
```bash
   git clone <your-repo>   # or upload this folder
   cd returngift_finetuning
   pip install -r requirements.txt
```
3. Upload/mount your Step 3 output (merged, filtered, Gemini-augmented
   dataset) to `data/raw/merged_filtered_dataset.jsonl`.
4. Persistent storage means the dataset, partitions, adapters, and runs/
   directories all survive session restarts — no need to re-download or
   re-partition each time you reconnect.
## 2. Run order
 
```bash
# 1. Partition dataset + prepend the matching Section 2 system prompt
python data_prep.py
 
# 2. Train each Action Skill adapter in isolation (repeat 3x)
python train_adapter.py --config configs/messaging.yaml
python train_adapter.py --config configs/transactional.yaml
python train_adapter.py --config configs/analytical.yaml
 
# 3. QAT int4 export, per adapter (repeat 3x)
python export_qat.py --config configs/messaging.yaml
python export_qat.py --config configs/transactional.yaml
python export_qat.py --config configs/analytical.yaml
```
 
## 3. Where each Section 4 requirement is enforced
 
| Requirement | Where |
|---|---|
| 16-bit base, not 4-bit, before QAT | `train_adapter.py::build_model_and_tokenizer` asserts `load_in_16bit`, loads `bfloat16` |
| QAT int4 at export only | `export_qat.py::quantize_int4_qat`, a separate script/step from training |
| `max_seq_length = 2048` | `configs/*.yaml`, consumed in `train_adapter.py::build_dataset` |
| Gradient checkpointing on | `configs/*.yaml` -> `model.gradient_checkpointing_enable()` |
| 3 separate, isolated `.safetensors` adapters, never merged | Each skill gets its own `adapters/<skill>/` dir; `export_qat.py` merges only into a disposable export copy, never back into the stored adapter |
| Bespoke system prompt matches train ⇄ runtime | `data_prep.py` reads the prompt straight out of `configs/<skill>.yaml` (single source of truth) rather than hardcoding it a second time |
 
## 4. Known gaps to fill in before this is production-ready
 
- **`export_to_tflite_litertlm`** in `export_qat.py` is a stub — the
  ai-edge-torch conversion API changes across versions, so check the
  installed version's current `convert()` signature rather than trusting
  a hardcoded call.
- **QAT calibration pass**: `quantize_int4_qat` currently calls
  `prepare()` → `convert()` without a calibration/fine-tune step in
  between, which degrades to post-training quantization (PTQ) rather
  than true QAT. Wire in a short calibration loop on a held-out slice
  of each skill's partition if quantization-aware accuracy matters more
  than export speed for you.
- **`base_model`** in the configs is a placeholder repo id
  (`google/functiongemma-270m-it`) — point it at wherever your actual
  FunctionGemma-270M-it checkpoint lives (local path or private HF repo).
- Adapter **versioning to HF Hub** (v1, v2, v3...) per Section 7 isn't
  wired up here yet — `model.save_pretrained` writes locally; add
  `push_to_hub()` calls once you've decided on repo naming.
- No **golden-transcript regression check** yet (last TO-DO line) —
  that should run against each exported `.litertlm` before it's trusted,
  separately from this fine-tuning pipeline.
 