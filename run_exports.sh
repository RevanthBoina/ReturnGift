# Ensure you have adapters and configs directories populated correctly.
# Make sure ai-edge-torch, transformers, peft, torch, PyYAML are installed.

# Export Messaging model
python export_qat.py --config configs/messaging.yaml --adapter_dir adapters --output_dir quantized_models --calibration_dataset dataset.jsonl

# Export Transactional model
python export_qat.py --config configs/transactional.yaml --adapter_dir adapters --output_dir quantized_models --calibration_dataset dataset.jsonl

# Export Analytical model
python export_qat.py --config configs/analytical.yaml --adapter_dir adapters --output_dir quantized_models --calibration_dataset dataset.jsonl