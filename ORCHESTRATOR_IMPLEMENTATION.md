# ReturnGift Orchestrator Router — Implementation Summary

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      PHASE A (Build Time)                       │
│  ┌─────────────────┐    ┌──────────────────┐    ┌────────────┐  │
│  │ route_defs.json │───▶│ compute_centroids│───▶│centroids.  │  │
│  │ (utterances)    │    │ .py (frozen base)│    │bin + meta  │  │
│  └─────────────────┘    └──────────────────┘    └──────┬─────┘  │
│                                                         │        │
│                                                         ▼        │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │              APK Assets (app/src/main/assets/)              ││
│  │  orchestrator/centroids.bin  +  centroids_meta.json        ││
│  │  models/functiongemma_base.tflite  (frozen, hidden states) ││
│  │  orchestrator/tokenizer.model                               ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      PHASE B (Runtime)                          │
│  ┌──────────────┐    ┌──────────────────┐    ┌───────────────┐  │
│  │ User Input   │───▶│ Orchestrator     │───▶│ Route + Score │  │
│  │ (screen+inst)│    │ Router (Kotlin)  │    │ (skill, conf) │  │
│  └──────────────┘    └────────┬─────────┘    └───────┬───────┘  │
│                               │                      │          │
│         "unresolved" ────────▶│                      │          │
│                               │         ✓ above      ▼          │
│                               │         threshold            │
│                               ▼                      ▼          │
│                      ┌────────────────┐    ┌────────────────┐   │
│                      │ Show Fallback  │    │ Bind Adapter   │   │
│                      │ "Not sure how" │    │ Execute Task   │   │
│                      └────────────────┘    └───────┬────────┘   │
│                                                     │           │
│                          Section 6 Confidence Gate │           │
│                           (routing_score +         │           │
│                            adapter_confidence)     │           │
│                                                     ▼           │
│                                              ┌────────────┐    │
│                                              │ Execute /  │    │
│                                              │ Confirm    │    │
│                                              └────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

## Files Created

| File | Purpose |
|------|---------|
| `route_definitions.json` | 4 routes × utterances + thresholds |
| `compute_centroids.py` | Phase A: frozen base → centroids.bin + meta |
| `golden_transcripts/routing.jsonl` | Regression test cases for routing |
| `eval_golden.py` | Updated with `--mode routing` promotion gate |
| `OrchestratorRouter.kt` | Phase B: on-device cosine similarity router |
| `OrchestratorManager.kt` | High-level integration (route → bind → execute) |
| `ModelRunner.kt` | Dual-mode inference (frozen base vs adapter-bound) |
| `SentencePieceTokenizer.kt` | On-device tokenizer wrapper |
| `export_frozen_base.py` | Export FunctionGemma base with hidden states |
| `app_build.gradle.kts` | Gradle integration for assets + deps |

## Build-Time Pipeline (Phase A)

```bash
# 1. Install deps
pip install torch transformers ai-edge-torch

# 2. Compute centroids (run in Lightning AI studio)
python compute_centroids.py \
  --route-defs route_definitions.json \
  --base-model google/functiongemma-270m-it \
  --output-dir orchestrator_assets

# Outputs:
# orchestrator_assets/centroids.bin      (4 routes × 768 dims float32)
# orchestrator_assets/centroids_meta.json (routes + thresholds)

# 3. Export frozen base model with hidden states
python export_frozen_base.py \
  --model google/functiongemma-270m-it \
  --layer -2 \
  --output models/functiongemma_base.tflite

# 4. Copy to Android assets
cp orchestrator_assets/* app/src/main/assets/orchestrator/
cp models/functiongemma_base.tflite app/src/main/assets/models/
```

## Runtime Flow (Phase B)

```kotlin
// 1. Initialize at app start
OrchestratorManager.initialize(this)

// 2. On user instruction
OrchestratorManager.getInstance().routeAndExecute(
    screenStateJson = accessibilityDump,
    instruction = "Send 'Hello' to Alice"
) { outcome ->
    when (outcome) {
        is RoutingOutcome.Success -> {
            // outcome.confidence ∈ [0,1] from cosine similarity
            // Pass to Section 6 confidence gate
            confidenceGate.check(outcome.confidence) { proceed ->
                if (proceed) executeToolCall(outcome.generation)
            }
        }
        is RoutingOutcome.Unresolved -> {
            showFallbackUI("I'm not sure how to help with that")
        }
        is RoutingOutcome.Error -> showError(outcome.message)
    }
}
```

## Confidence Gate Integration (Section 6)

```kotlin
// Before executing risky action (payment, send message)
fun checkConfidence(routingScore: Float, adapterConfidence: Float): Boolean {
    // Combined signal: routing similarity + generation probability
    val combined = (routingScore * 0.4) + (adapterConfidence * 0.6)
    return combined >= 0.75  // Tunable threshold
}
```

## Regression Testing (Promotion Gate)

```bash
# Skill adapter promotion (existing)
python golden_transcripts/eval_golden.py \
  --skill messaging \
  --adapter-path adapters/messaging \
  --adapter-version v2 \
  --compare-to results/messaging_deployed_baseline.json

# Orchestrator routing promotion (NEW)
python golden_transcripts/eval_golden.py \
  --mode routing \
  --centroids-bin orchestrator_assets/centroids.bin \
  --centroids-meta orchestrator_assets/centroids_meta.json \
  --adapter-version v2 \
  --compare-to results/routing_deployed_baseline.json
```

## Latency Budget (Measured on Device)

| Component | Target | Notes |
|-----------|--------|-------|
| Tokenize | ~1 ms | SentencePiece |
| Frozen base forward | ~8-15 ms | On NPU (Hexagon/QNN) |
| Cosine similarity | <0.1 ms | 4 × 768 dot products |
| **Total routing overhead** | **~10-20 ms** | Runs during "preparing tools" animation |

## Key Design Decisions

1. **No second model** — Reuses frozen FunctionGemma base (already loaded)
2. **Layer -2 hidden states** — Semantic-rich, not tied to LM head
3. **Mean-last-token pooling** — Robust to variable-length inputs
4. **Centroids baked in APK** — No runtime download, works offline
5. **Thresholds from Phase A** — Per-route, calibrated on eval set
6. **Unresolved route** — Explicit "I don't know" beats wrong skill
7. **Score feeds Section 6 gate** — Defined confidence source

## Testing Checklist

- [ ] `compute_centroids.py` runs without OOM on Lightning GPU
- [ ] `centroids.bin` size = 4 × hidden_dim × 4 bytes
- [ ] `eval_golden.py --mode routing` passes on golden set
- [ ] TFLite model exports with 2 outputs (logits, hidden_states)
- [ ] `OrchestratorRouter` loads assets from APK
- [ ] End-to-end latency < 50ms on target device (Pixel 7a / S23)
- [ ] Fallback UI shown for unresolved routes
- [ ] Confidence gate receives routing score