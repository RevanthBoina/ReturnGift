# ReturnGift Backlog

Items go in, get prioritized, get done, get crossed out.

Priority: `P0` = blocks users, fix now. `P1` = next up. `P2` = when we get to it. `P3` = nice to have.

---

## Bugs

- [ ] **P1** Investigate MediaTek/Samsung local-engine bring-up failures (OpenCL/LiteRT engine creation errors on some devices after GPU→CPU fallback)
- [ ] **P2** Settings screen: active model row breaks layout when the model name is long

## Features

- [ ] **P1** LoRA Action Skills infrastructure: define SkillOpt Skill doc format and loading pipeline
- [ ] **P1** Persistent global instructions: user-editable local instructions layer, short, inspectable, local-first
- [ ] **P1** Per-app allow-list (Part B safeguard): settings screen with on/off toggle per app, first-encounter permission prompt
- [ ] **P1** Mid-task popup/notification interrupt handling (Part A safeguard): overlay detector, AUTO_DISMISSABLE vs PAUSE_AND_CONFIRM classification
- [ ] **P1** One-tap Undo (Part C safeguard): post-tool-call snackbar with direct reverse-action, undoable tool tagging
- [ ] **P1** More small local model options: 1B–1.5B class models for lower-RAM devices
- [ ] **P1** Local model import UX: shared-storage `.litertlm` import
- [ ] **P2** Chat keyboard dismissal polish
- [ ] **P2** Unified task registry: monitor + agent tasks tracked in same system

## Backlog (recorded decisions)

- [ ] **OUT-OF-SCOPE** Audit/decide: delete or revive Phase-2 pipeline stack (unwired as of 2026-08-28) — `pipeline/IntegratedAgentPipeline`, `pipeline/Phase2Pipeline`, `routing/AdaptiveRouter`, `planner/TaskPlanner` are each referenced only from within the stack; live routing is `TaskOrchestrator`/`TaskFlowController` → `PipelineRouter.route()`. (A3)
- [ ] **OUT-OF-SCOPE** Project-wide English-only: remove `res/values-ja/` and `res/values-zh/` string folders. Recorded decision (D2c); separate follow-up PR.

## QA Gaps

- [ ] **P0** Full QA on signed-release APK BEFORE pushing any version tag
- [ ] **P1** Local full-sweep QA after on-device model swap
- [ ] **P1** Per-app allow-list E2E: first-encounter prompt fires, allow-once vs add-to-list are distinct
- [ ] **P1** Interrupt handling E2E: incoming call mid-task pauses correctly, AUTO_DISMISSABLE resumes

---

## Done

_Move completed items here with date._
