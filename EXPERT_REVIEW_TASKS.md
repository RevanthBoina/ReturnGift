# Expert Review — Action Items

Source: external expert review of repository state.
Track each item here; mark complete when done.

---

## Finding 1 — Source code not in version control [COMPLETE]

~~The actual Android harness source (Kotlin accessibility service, tool layer, task loop,~~
~~PokeClaw fork baseline) has never been pushed to this repository.~~

**Done:**
- [x] Full Android source pushed to `source_repo/` — Kotlin accessibility service
      (`ClawAccessibilityService.java`), tool layer (`tool/impl/`), task loop
      (`TaskOrchestrator.kt`), skill layer (`BuiltInSkills.kt`, `SkillRegistry.kt`,
      `SkillExecutor.kt`), LLM clients, UI, channels, and all supporting code.
- [x] CI present: `.github/workflows/build.yml` builds debug APK on push/PR to `main`.
- [x] Working baseline is in version control — no longer at risk of being lost.

---

## Finding 2 — LICENSE compliance [COMPLETE]

~~LICENSE was plain MIT. ReturnGift is a PokeClaw fork; Apache-2.0 requires derivative works~~
~~to retain the original license, not relicense as MIT. No NOTICE file crediting PokeClaw.~~

**Done:**
- [x] `source_repo/LICENSE` is Apache-2.0 (correct — already was)
- [x] `source_repo/NOTICE` credits PokeClaw / agents.io / Nicole with Apache-2.0 attribution
- [x] `source_repo/README.md` attribution section corrected (was "derived from ReturnGift", now correctly credits PokeClaw)

---

## Finding 3 — skill_integration_analysis.md references files that don't exist [COMPLETE]

~~`skill_integration_analysis.md` was written against a local PokeClaw fork that was never~~
~~pushed. It references `send_message.md`, `open_and_search.md`, `open_and_navigate.md`,~~
~~and `BuiltInSkills.kt` as "existing" — none of those existed in this repo at the time.~~

**Done:**
- [x] `skill_integration_analysis.md` created at `source_repo/skill_integration_analysis.md`
      against the committed source (Finding 1 resolved first).
- [x] Verified: `BuiltInSkills.kt`, `SkillRegistry.kt`, `SkillExecutor.kt`, `Skill.kt` all
      committed and consistent with the YAML schema.
- [x] Verified: playbooks (`send-message.md`, `open-and-search.md`, `open-and-navigate.md`)
      present in `app/src/main/assets/playbooks/`.
- [x] Analysis documents the gap between the 9 Kotlin BuiltInSkills and the 19 YAML skills,
      and the integration path still required.

**Still required (blocked by Finding 4):**
- [ ] Re-verify `skill_integration_analysis.md` after fixtures are committed and
      `tree_hash` values are filled in.

---

## Finding 4 — 21 fixture XML files not committed [OPEN — requires device capture]

`skill_definitions_v2.yaml` references 22 `fixtures/screen_N.xml` paths across all 19 skills.
None of these files exist in the repository. The ADB-captured accessibility trees live only
on the capture device.

**Action required:**
- [ ] Capture all 22 files from SM-S918B (Android 14, OneUI 6) using `adb shell uiautomator dump`
- [ ] Commit to `fixtures/` at the exact paths the YAML expects (see `fixtures/README.md`)
- [ ] Generate SHA-256 hash for each file and backfill `tree_hash` in `skill_definitions_v2.yaml`

See `fixtures/README.md` for the full file list and capture commands.

---

## Finding 5 — tree_hash placeholder and missing hashes [PARTIALLY COMPLETE]

One skill (`send_message`) had `tree_hash: "sha256:placeholder_wa_chat_list"` — a known
unfixed placeholder. The other 18 skills had no `tree_hash` field at all. The staleness
detection mechanism cannot function without real hashes.

**Done:**
- [x] Removed `sha256:placeholder_wa_chat_list` placeholder
- [x] Added `tree_hash: null` to all 22 fixture entries across all 19 skills

**Still required (blocked by Finding 4):**
- [ ] Replace all `tree_hash: null` with real `sha256:...` values after fixtures are committed

---

## Finding 6 — Status labels overstate readiness [COMPLETE]

17 skills were `stable`, 2 `canary`, 1 `draft` — but none have been validated against
real committed fixtures. Labels described intent, not verified state.

**Done:**
- [x] All 19 skills relabeled `status: draft`
- [ ] Promote individual skills to `canary` only after: fixture committed + tree_hash filled + skill run against real fixture on device
- [ ] Promote to `stable` only after: canary soak on ≥2 device profiles

---

## Finding 7 — Three skills outside 8-domain on-device taxonomy [COMPLETE]

`search_repository`, `create_design`, and `create_video_project` don't fit the 8-domain
taxonomy for 270M on-device slot-extractor skills. `create_design` and `create_video_project`
are multi-step stateful workflows closer to the cloud Deep-Agent tier.

**Done:**
- [x] Added `scope_note` to all three skills in `skill_definitions_v2.yaml` flagging the
      unresolved tier decision and blocking promotion to canary until resolved

**Decision required:**
- [ ] Decide for each: local FunctionGemma Action Skill, or cloud Deep-Agent tier?
      - `search_repository` — read-only, low complexity, plausible as local skill
      - `create_design` — WebView inaccessibility makes local automation unreliable; likely cloud
      - `create_video_project` — multi-minute stateful, no deep links; likely cloud

---

## Summary

| # | Finding | Status |
|---|---------|--------|
| 1 | Source code not pushed | COMPLETE — full Android source + CI committed |
| 2 | LICENSE / NOTICE / attribution | COMPLETE |
| 3 | skill_integration_analysis.md references missing files | COMPLETE — doc created against committed source |
| 4 | 21 fixture XMLs not committed | OPEN — requires device capture |
| 5 | tree_hash placeholder + missing hashes | PARTIAL — nulls set, real hashes need fixtures |
| 6 | Status labels overstate readiness | COMPLETE — all relabeled draft |
| 7 | Three scope-drift skills | COMPLETE — scope_note added, decision pending |
