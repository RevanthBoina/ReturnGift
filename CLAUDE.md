# ReturnGift — Project Rules

## Read This First

ReturnGift is a private/internal Android mobile-agent harness built on a single on-device foundation model with LoRA-based Action Skills. There is no cloud fallback mode. All task execution runs on-device via LiteRT-LM.

→ See `README.md` for the current architecture direction and full repository structure.

## Project Files

| File | What | When to update |
|------|------|----------------|
| `README.md` | Architecture direction, full repo structure | When direction or structure changes |
| `AI_INDEX.md` | Repo map for coding agents | When files or directories move |
| `CLAUDE.md` | Project rules | When workflow/rules change |
| `QA_CHECKLIST.md` | E2E test cases + debug changelog | Every code change |
| `RELEASING.md` | Signing and release workflow | When the release process changes |
| `BACKLOG.md` | Features, bugs, ideas with priority | When new items come in or items get done |

## Repository Structure Rules

- **agent-core/** — pure Kotlin/JVM, zero `android.*` imports. All agent logic lives here.
- **app/** — Android shell only. Wires agent-core into platform. No business logic.
- **skill_library/** — YAML skill definitions + Python lifecycle pipeline. No Kotlin.
- **docs/** — documentation only. No code. ADRs in `docs/adr/`, specs in `docs/specs/`.
- **scripts/** — shell scripts only. No app logic.
- Do NOT add new root-level directories without updating `README.md` and `AI_INDEX.md`.

## QA-First Development (MANDATORY)

Every code change MUST include E2E QA. No exceptions.

### Per-Change QA (every commit)

1. **Design QA tests FIRST** — before writing code, define what the E2E test looks like
2. **Add tests to `QA_CHECKLIST.md`** — permanent, under the relevant section
3. **Tests must be E2E via ADB** — simulate real user behavior
4. **Cover edge cases** — happy path + error path + boundary conditions
5. **Run the new tests** — execute them, verify PASS, record results
6. **Run affected existing tests** — any section that could be impacted

## Android Patterns

- All errors must be user-visible (Toast, system message, or dialog) — never silent failures
- Permission checks before features that need them

## Debug Logging (MANDATORY)

Every code path must be traceable through logcat alone.

### Log levels

- `XLog.e` — errors that affect user experience
- `XLog.w` — recoverable issues
- `XLog.i` — key lifecycle events
- `XLog.d` — detailed flow tracing

## Architecture Note

ReturnGift runs a single on-device foundation model with LoRA-based Action Skills — no cloud routing, no dual-mode switching. New tool-primitive *sequences* are handled as SkillOpt Skill docs (markdown playbooks in `app/src/main/assets/playbooks/`) + YAML definitions in `skill_library/skills/`, not LoRA retrains. The harness layer (Accessibility, tools, task loop) is generic and device-resident.
