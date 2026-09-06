# Release Build Diagnostics — Lessons Learned

This document captures the root causes and fixes for release build failures encountered during the v3.x release cycle. It serves as a reference for future contributors to avoid repeating these mistakes.

## Pitfall 1: `@file:OptIn` Must Precede Package Declaration (Kotlin)

**Symptom:** Compilation error: `OptIn annotation is not allowed on non-file declarations` or `This type is experimental and needs to be opted into...`

**Root Cause:** In Kotlin, file-level annotations (`@file:OptIn`, `@file:JvmName`, etc.) **must appear before the `package` declaration**. Placing `@OptIn` on the class declaration (`class ComposeChatActivity`) is insufficient for experimental APIs that require file-level opt-in.

**Fix:**
```kotlin
// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

@file:OptIn(ExperimentalMaterial3WindowSizeClassApi::class)

package com.returngift.agent.ui.chat

import ...
```

**Not this:**
```kotlin
package com.returngift.agent.ui.chat

import ...

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class ComposeChatActivity : ComponentActivity() { ... }
```

**Affected APIs:** `ExperimentalMaterial3WindowSizeClassApi`, `ExperimentalLayoutApi`, and other experimental Compose APIs requiring file-level opt-in.

**Lesson:** Always place `@file:OptIn` annotations immediately after the license header and before the `package` statement. Grep for `@OptIn(` on class declarations — if it's for an experimental API, it likely needs to be `@file:OptIn` before package.

---

## Pitfall 2: CI Preflight Must Run Before Gradle

**Symptom:** Gradle build fails with errors that could have been caught in seconds (missing imports, return in default args, ic_menu_compose, etc.)

**Root Cause:** The `scripts/ci-preflight.sh` script catches 5 classes of compile-time errors that break CI. Running it as the **first step** in the workflow (before Gradle setup) fails fast in ~10s instead of waiting 5-10 minutes for Gradle.

**Workflow Order:**
```yaml
steps:
  - uses: actions/checkout@v4
  - name: Run CI preflight
    run: bash scripts/ci-preflight.sh    # MUST BE FIRST
  - name: Set up JDK 17
    uses: actions/setup-java@v5
    ...
```

**Lesson:** Never skip or reorder the preflight step. It catches:
1. `android.R.drawable.ic_menu_compose` (doesn't exist)
2. `return` in default parameter values (illegal in Kotlin)
3. Missing imports for `ToolResult`/`BaseTool`/`ToolParameter` in Java sub-packages
4. Kotlin `Result` value class called from Java (mangled name)
5. Brace/paren/bracket balance in Kotlin files

---

## Pitfall 3: Duplicate Tags Cause Workflow Confusion

**Symptom:** Multiple workflow runs for same commit, or "tag already exists" errors.

**Root Cause:** Creating new tags pointing to the same commit (e.g., `v3.0.13` and `v3.0.14` both pointing to `db8af88`) triggers duplicate workflow runs.

**Fix:** Only create a new tag when the commit SHA actually changes. Use `git tag -d <tag>` locally before recreating, then push.

**Lesson:** Check existing tags with `git ls-remote --tags origin | grep v3.0` before creating new ones. Each tag should represent a unique commit.

---

## Pitfall 4: Release Workflow Only Triggers on Tags

**Symptom:** Pushing to `fix/compilation-errors` branch doesn't trigger release build.

**Root Cause:** The `release.yml` workflow is configured with:
```yaml
on:
  push:
    tags:
      - 'v*'
```

It only runs on **tag pushes**, not branch pushes.

**Fix:** To test release build, push a tag: `git tag v3.0.x && git push origin v3.0.x`

**Lesson:** The release pipeline is tag-gated. Branch pushes run other workflows (auto_build_and_test.yml, emulator-matrix.yml) but not the signed release build.

---

## Pitfall 5: Iterative Fixes Require New Tags

**Symptom:** Fix pushed to branch, but release build still fails on old tag.

**Root Cause:** Each tag is immutable. A fix requires a **new tag** (new version) to trigger a fresh release build.

**Workflow:**
1. Fix code on branch
2. Merge to main (or push fixed commit to main)
3. Create new tag: `git tag v3.0.x <commit-sha>`
4. Push tag: `git push origin v3.0.x`

**Lesson:** Don't force-push existing tags (breaks reproducibility). Create incremented version tags instead.

---

## Pitfall 6: ChatScreen.kt DropdownMenu Structure

**Symptom:** Compilation errors in `DropdownMenu` — `else` branches outside `if` block, type mismatches.

**Root Cause:** Compose `DropdownMenu` requires the `expanded` parameter to be a `MutableState<Boolean>`, and the content lambda must be properly structured. The `else` branch for `DropdownMenuItem` must be inside the `if (expanded)` block.

**Fix Pattern:**
```kotlin
var expanded by remember { mutableStateOf(false) }
DropdownMenu(
    expanded = expanded,
    onDismissRequest = { expanded = false }
) {
    // All menu items here
    DropdownMenuItem(onClick = { ... }) { Text("Item 1") }
    DropdownMenuItem(onClick = { ... }) { Text("Item 2") }
}
```

**Lesson:** `DropdownMenu` is a single composable block — don't split it across `if/else` outside the content lambda.

---

## Pitfall 7: Dp Division Returns Float

**Symptom:** Type mismatch: `Float` cannot be assigned to `Dp`

**Root Cause:** Dividing `Dp` by `Int` returns `Float`, not `Dp`.

**Fix:**
```kotlin
// Wrong
val horizontalPad = 16.dp / 2  // Returns Float

// Correct
val horizontalPad = (16.dp / 2).dp  // Or use extension
// Or: val horizontalPad = 8.dp
```

**Lesson:** `Dp` arithmetic with scalars returns `Float`. Use `.dp` extension or calculate as `Int` then `.dp`.

---

## Pitfall 8: Unreachable Catch Blocks

**Symptom:** Detekt/lint warning: `Unreachable catch block for InterruptedException`

**Root Cause:** Code that cannot throw `InterruptedException` wrapped in try/catch for it.

**Fix:** Remove the unreachable catch block or change to a reachable exception type.

**Preflight Guard:** Added check in `scripts/ci-preflight.sh`:
```bash
grep -rn "catch.*InterruptedException" --include="*.kt" app/src/main/java/ && \
  echo "ERROR: Unreachable InterruptedException catch" && exit 1
```

---

## Successful Release Checklist

Before tagging a release, verify locally:
- [ ] `bash scripts/ci-preflight.sh` passes (all 17 checks)
- [ ] `git diff main` shows only intended changes
- [ ] Version in `app/build.gradle.kts` matches tag (or workflow derives correctly)
- [ ] No duplicate tags exist for target commit
- [ ] Create tag from main branch commit: `git tag vX.Y.Z <main-sha>`
- [ ] Push tag: `git push origin vX.Y.Z`
- [ ] Monitor workflow: `gh run watch` or GitHub Actions UI

## Quick Reference: Common Fix Commands

```bash
# Run preflight locally (no SDK needed)
bash scripts/ci-preflight.sh

# Check for @file:OptIn placement
grep -rn "@OptIn(" app/src/main/java/ --include="*.kt" | grep -v "@file:"

# Verify no ic_menu_compose
grep -rn "ic_menu_compose" app/src/main/

# Check Dp divisions
grep -rn "\.dp /" app/src/main/java/ --include="*.kt"

# Find unreachable catches
grep -rn "catch.*InterruptedException" app/src/main/java/ --include="*.kt"
```

---

**Last Updated:** 2026-09-06 (v3.0.14 release)
**Related:** `QA_CHECKLIST.md` section R (Release Build), `RELEASING.md`
