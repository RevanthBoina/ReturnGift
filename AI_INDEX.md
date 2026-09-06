# ReturnGift AI Index

This is the repo map for coding agents. Keep canonical information in existing files; do not create new root docs when one of these files already owns the topic.

## Canonical Root Docs

| File | Owns |
|------|------|
| `README.md` | Project overview and architecture direction |
| `CLAUDE.md` | Agent/project working rules |
| `QA_CHECKLIST.md` | QA methodology, release gate, test cases |
| `RELEASING.md` | Release signing, tag workflow, APK publishing |
| `BACKLOG.md` | Prioritized bugs, features, QA gaps |
| `AI_INDEX.md` | This repo map |

## Directory Map

| Path | Purpose |
|------|---------|
| `app/src/main/java/com/returngift/agent/` | Android app source |
| `app/src/main/assets/playbooks/` | Built-in playbooks used by the agent harness |
| `scripts/` | QA and automation scripts |
| `.github/workflows/` | CI and signed release workflow |
| `docs/specs/` | Feature/subsystem design specs (e.g. `tier1-intent-matching.md`) |
| `fixtures/` | Golden corpora for deterministic layers (e.g. `tier1_golden_utterances.jsonl`) |

## Key Implementation Files (W1–W9 + U-pack additions)

| File | Purpose |
|------|---------|
| `app/src/main/java/com/returngift/agent/agent/knowledge/AppCatalog.kt` | App address registry — built at setup, persists JSON + vault markdown |
| `app/src/main/java/com/returngift/agent/agent/exec/TargetSpecGate.kt` | Pure Kotlin gate detecting enumeration-without-names (e.g., "open 5 apps") |
| `app/src/main/java/com/returngift/agent/agent/exec/DeterministicUiExecutor.kt` | Bounded executor with WAIT_FOR_TARGET polling + waitForMs per step |
| `app/src/main/java/com/returngift/agent/agent/exec/StructuredRoutineRegistry.kt` | Registry of deterministic routines (LinkedInPostRoutine) |
| `app/src/main/java/com/returngift/agent/agent/exec/routines/LinkedInPostRoutine.kt` | LinkedIn posting routine with hardened selectors + wait |
| `app/src/main/java/com/returngift/agent/agent/PipelineRouter.kt` | Tier-1 router with isEscalation param to skip to Tier-3 |
| `app/src/main/java/com/returngift/agent/agent/Tier1Telemetry.kt` | Escalation tracking for routing quality |
| `app/src/main/java/com/returngift/agent/agent/dryrun/DryRunRunner.kt` | Preview mode with keep_preview_mode + context-aware Toast |
| `app/src/main/java/com/returngift/agent/ui/chat/ChatScreen.kt` | Chat UI with new TopAppBar, ModelSheet, mode chip, preview banner |
| `app/src/main/java/com/returngift/agent/ui/chat/TaskFlowController.kt` | Task flow with preview auto-reset in cleanupAfterTask |

## Removed Files

| File | Reason |
|------|--------|
| `app/src/main/java/com/returngift/agent/agent/routing/AdaptiveRouter.kt` | Dead code — no-op recordResult, live routing is PipelineRouter |
| `app/src/main/java/com/returngift/agent/agent/pipeline/IntegratedAgentPipeline.kt` | Dead code — unused |

## Direction Rules

→ See `README.md` for ReturnGift's project plan and architecture direction.
