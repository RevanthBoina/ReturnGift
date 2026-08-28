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

## Direction Rules

→ See `README.md` for ReturnGift's project plan and architecture direction.
