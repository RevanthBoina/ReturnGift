# ReturnGift Architecture Notes

This document tracks the active architecture boundaries and refactor guardrails.

→ The upstream ReturnGift architecture reconstruction history has been removed. ReturnGift starts from the refactored harness baseline (Phases 0–5 complete) and maintains it forward from here.

## Non-Negotiables

- Do **not** change product behavior unless the change is a confirmed bug fix.
- Keep persisted config/MMKV keys compatible unless a migration is explicitly planned and tested.
- Every refactor must declare its scope, its invariants, and the QA bundle that must be rerun.

## Current Baseline (inherited from harness refactor)

The following boundaries are already in place and must be preserved:

- `ChatSessionController` owns local runtime client selection and chat send pipeline
- `ConversationStore` owns conversation persistence and identity
- `TaskSessionStore` owns live task-session state (single source of truth)
- `TaskFlowController` owns task-mode send flow and task event rendering
- `ActiveTaskShellController` owns active monitor/task shell state
- `AppCapabilityCoordinator` owns permission/capability truth
- `LocalModelRuntime` owns engine acquisition and single-shot inference

## ReturnGift-Specific Changes

- Cloud routing removed: `CloudProvider`, `AnthropicLlmClient`, `OpenAiLlmClient`, `PipelineRouter` cloud branch, and `LangChain4jToolBridge` are present but cloud paths are disabled pending LoRA-only architecture hardening.
- SkillOpt Skill docs replace per-task prompt tuning. New task sequences → `assets/playbooks/` or `agent/skill/`, not model retrains.

## What Should Not Happen

- No adding cloud fallback back without explicit architecture decision.
- No per-vertical hardcoded workflows in core harness.
- No undocumented storage migration.
