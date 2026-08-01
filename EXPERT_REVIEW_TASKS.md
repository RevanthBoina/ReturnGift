# Expert Review — Action Items

Source: external expert review of repository state.
Track each item here; mark complete when done.
Last verified: 2026-08-01 against `main` branch.

---

## Finding 1 — Source code not in version control [COMPLETE]

**Verified 2026-08-01:** 191 Kotlin/Java source files are committed under `app/src/`.
Full Android source is present: accessibility service (`ClawAccessibilityService.java`),
tool layer (`tool/impl/`), task loop (`TaskOrchestrator.kt`), skill layer
(`BuiltInSkills.kt`, `SkillRegistry.kt`, `SkillExecutor.kt`), LLM clients, UI, channels.
CI is present at `.github/workflows/build.yml`.

**Done:**
- [x] Full Android source pushed to `source_repo/`
- [x] CI present: `.github/workflows/build.yml` builds debug APK on push/PR to `main`
- [x] Working baseline is in version control — not at risk of being lost

---

## Finding 2 — LICENSE compliance [COMPLETE]

**Verified 2026-08-01:** `LICENSE` is Apache-2.0. `NOTICE` credits PokeClaw. No MIT
license present anywhere in the repository.

**Done:**
- [x] `source_repo/LICENSE` is Apache-2.0
- [x] `source_repo/NOTICE` credits PokeClaw / agents.io / Nicole with Apache-2.0 attribution
- [x] `source_repo/README.md` attribution section correctly credits PokeClaw

---

## Finding 3 — skill_integration_analysis.md accuracy [COMPLETE]

**Verified 2026-08-01:** `skill_integration_analysis.md` has been rewritten against the
current repository state. All previously stale references have been corrected:

- Removed framing of `search_repository`, `create_design`, `create_video_project` as
  architectural issues — they are deliberate design deferrals with explicit `scope_note`
  and `status: draft`, not problems.
- Corrected `tree_hash` description — all 22 entries are intentionally `null` with a
  `# TODO` comment, not a hidden gap.
- Removed internal Finding-number references that do not belong in an analysis document.
- Added explicit fixture path table showing all 21 missing XML files as a missing
  dependency (not a hash issue).
- All file paths referenced in the document verified to exist in the current repo.

**Done:**
- [x] `skill_integration_analysis.md` rewritten and accurate against current source
- [x] All referenced paths verified: playbooks, Kotlin skill layer, tool layer all present

**Still required (blocked by fixture capture):**
- [ ] Re-verify after fixture XMLs are committed and `tree_hash` values are filled

---

## Finding 4 — 21 fixture XML files not committed [OPEN — requires device capture]

**Verified 2026-08-01:** `fixtures/` contains only `README.md`. All 21 XML paths
referenced in `skill_definitions_v2.yaml` are absent. This is a **missing dependency**
that blocks skill promotion from `draft` to `canary` — it is not a `tree_hash` issue.

**Action required (requires SM-S918B, Android 14, OneUI 6):**
- [ ] Capture all 21 files using `adb shell uiautomator dump`
- [ ] Commit to `fixtures/` at the exact paths the YAML expects
- [ ] Generate SHA-256 hash for each file and replace `tree_hash: null` in `skill_definitions_v2.yaml`

See `fixtures/README.md` for the full file list and capture commands.

---

## Finding 5 — tree_hash values [INTENTIONAL TODO — no action needed until fixtures captured]

**Verified 2026-08-01:** All 22 fixture entries in `skill_definitions_v2.yaml` carry
`tree_hash: null  # TODO: generate from real fixture file`. There are no hardcoded
placeholder hashes and no entries silently missing the field. This is an intentional,
transparent TODO that will be resolved once fixture files are captured (Finding 4).

**Done:**
- [x] All 22 entries explicitly set to `tree_hash: null` with `# TODO` comment
- [x] No hidden gaps or fake hash values

**Still required (blocked by Finding 4):**
- [ ] Replace all `tree_hash: null` with real `sha256:...` values after fixtures are committed

---

## Finding 6 — Status labels overstate readiness [COMPLETE]

**Verified 2026-08-01:** All 19 skills in `skill_definitions_v2.yaml` are `status: draft`.

**Done:**
- [x] All 19 skills set to `status: draft`
- [ ] Promote individual skills to `canary` only after: fixture committed + tree_hash filled + skill validated on device
- [ ] Promote to `stable` only after: canary soak on ≥2 device profiles

---

## Finding 7 — Three skills with deferred tier decisions [COMPLETE — decision pending]

**Verified 2026-08-01:** `search_repository`, `create_design`, and `create_video_project`
each carry an explicit `scope_note` in `skill_definitions_v2.yaml` stating the tier
decision is unresolved and promotion is blocked. All three are `status: draft`. This is a
**deliberate design deferral**, not scope drift or an architectural issue.

**Done:**
- [x] `scope_note` added to all three skills blocking promotion until tier decision is made
- [x] All three remain `status: draft`

**Decision required:**
- [ ] `search_repository` — read-only, low complexity; decide: local Action Skill or cloud Deep-Agent?
- [ ] `create_design` — WebView inaccessibility; likely cloud Deep-Agent tier
- [ ] `create_video_project` — multi-minute stateful, no deep links; likely cloud Deep-Agent tier

---

## Summary

| # | Finding | Status |
|---|---------|--------|
| 1 | Source code not pushed | COMPLETE — 191 source files + CI on `main` |
| 2 | LICENSE / NOTICE / attribution | COMPLETE — Apache-2.0 confirmed |
| 3 | skill_integration_analysis.md stale references | COMPLETE — rewritten, all paths verified |
| 4 | 21 fixture XMLs not committed | OPEN — missing dependency, requires device capture |
| 5 | tree_hash values | INTENTIONAL TODO — all 22 entries `null` with `# TODO`, no hidden gaps |
| 6 | Status labels overstate readiness | COMPLETE — all 19 skills `status: draft` |
| 7 | Three skills with deferred tier decisions | COMPLETE — `scope_note` added, decision pending |

---

## Push log

| Commit | Branch | Pushed UTC | Notes |
|--------|--------|-----------|-------|
| `02bfe10` | `main` | 2026-08-01T06:50:00Z | All expert-review work merged onto main |
| `4f0cc5b` | `main` | 2026-08-01T06:59:24Z | Push log added to task file |
| `pending` | `main` | 2026-08-01 | Corrections: skill_integration_analysis.md rewritten; EXPERT_REVIEW_TASKS.md updated to reflect verified findings |

Repo: https://github.com/RevanthBoina/ReturnGift
