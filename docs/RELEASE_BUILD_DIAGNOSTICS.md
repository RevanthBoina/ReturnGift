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

## Pitfall 9: v3.2.0 — `compileReleaseKotlin` Failure Clusters (2026-09-07)

The `v3.2.0` tag (run #34104968181) failed at `:app:compileReleaseKotlin` with SEVERAL independent errors surfacing together. Fix ALL of them before tagging again. They fall into five clusters:

**(a) Kotlin callers cannot use `ClawApplication.Companion.getInstance()`**
`ClawApplication` exposes a Kotlin `companion object` with `lateinit var instance`. In Kotlin, reference it as `ClawApplication.instance` — the `Companion.getInstance()` form is only valid from Java. Files hit: `DefaultAgentService.kt`, `TargetSpecGate.kt`.
```kotlin
// Kotlin:
ClawApplication.instance
// Java:
ClawApplication.Companion.getInstance()
```

**(b) `compareByDescending` receiver/lambda mismatch**
Sorting `allEntries.sortedWith(compareByDescending<AppEntry>(...))` with a trailing `{ it.label }` lambda left the receiver ambiguous and eventually failed with `2 type arguments expected for fun <T,K> compareByDescending(comparator: Comparator<in K>, crossinline selector:(T) -> K)`. Do NOT use that two-lambda arity — chain two comparators instead. Working shape:
```kotlin
val sortedEntries = allEntries.sortedWith(
    compareBy<com.returngift.agent.agent.knowledge.AppCatalog.AppEntry> { entry ->
        !priorityLabels.any { priority -> priority.equals(entry.label, ignoreCase = true) }  // priority apps first
    }.thenBy { entry -> entry.label.lowercase() }                                  // then alphabetical
)
```
**Symptom:** `2 type arguments expected`, `Argument type mismatch`, `Return type mismatch: expected Int, actual Boolean`. Use `compareBy<AppEntry> { … }.thenBy { … }` chain; don't rely on implicit `it` inside nested lambdas; don't use the `<T,K>` overload with two lambdas.

**(b2) `Icons.AutoMirrored.*` does NOT exist in the pinned Compose BOM (2025.05.00)**
The `material-icons-*` artifact published with the pinned BOM omits the `AutoMirrored` icon variants — `Icons.AutoMirrored.Filled.Menu` → `Unresolved reference 'Menu'` at the import site,and `Icons.AutoMirrored.Filled.ArrowBack` likewise fails. Using the plain `Icons.Filled.Menu` / `Icons.Filled.ArrowBack` (import `androidx.compose.material.icons.filled.Menu` / `filled.ArrowBack`) compiles fine. The earlier W1-era guidance to prefer AutoMirrored is no longer valid for this BOM pin.

**(c) Missing `KBManager` import in `AppCatalog.kt`**
`AppCatalog.kt` calls `KBManager.write("apps/app-registry.md", emptyMap(), content)` but forgot `import com.returngift.agent.agent.knowledge.KBManager`. The 3-arg signature is `write(path, frontmatter: Map<String, Any>, content)` — the frontmatter arg is mandatory. Do not assume a 2-arg overload exists.

**(d) `ChatScreen.kt` — Compose icon/-click/sheet API drift with the pinned Compose BOM (2025.05.00)**
1. Bare `Icon(Menu, …)` etc reference unqualified `Menu`/`Visibility`/`Folder`/`Settings` — qualify `Icons.Filled.Menu` (or `Icons.Outlined.Menu`; the `AutoMirrored` variants do NOT exist in this BOM — see b2), `Icons.Outlined.Visibility`, `Icons.Outlined.Folder`, `Icons.Outlined.Settings`, `Icons.Outlined.SmartToy`, `Icons.Outlined.ChatBubbleOutline`. The `Icon` first arg needs the `ImageVector` receiver; unqualified top-level vector objects don't resolve without a matching import.
2. `Modifier.onClick { … }` does NOT exist — use `Modifier.clickable { … }` (needs `import androidx.compose.foundation.clickable`). Also remove any adjacent dual `pointerInput(Unit) { detectTapGestures(...) }` blocks that double-handle the same tap. Five sites in `ModelSheet`:
```kotlin
.clip(RoundedCornerShape(8.dp))
    .clickable { onModelSwitch(model.id, model.displayName); onDismiss() },
```
3. `ModalBottomSheet` (pinned BOM) uses `content = { … }`, `shape = …`, `containerColor = …` — NOT the legacy `sheetContent`/`sheetShape`/`sheetBackgroundColor`. Retit also `showModelSheet` is out-of-scope inside the sheet — use `onDismiss()` instead:
```kotlin
scope.launch { onDismiss(); onSettings() }
```
4. Smart icon ternary: `if (isTaskMode) SmartToy else ChatBubbleOutline` must be `if (isTaskMode) Icons.Outlined.SmartToy else Icons.Outlined.ChatBubbleOutline`.
5. `TextUnit` missing import: `import androidx.compose.ui.unit.TextUnit` when a param type refers to it.

**(e) `GuideActivity.kt` — variable used outside declaration scope**
`accessibilityReady` was declared inside a `.let { }` block but referenced in a sibling block — hoist it to the function body:
```kotlin
val accessibilityReady = snapshot.accessibilityState == com.returngift.agent.ServiceBindingState.READY
```

**Lesson:** `compileReleaseKotlin` failures bundle multiple independent errors from several files. Fix-and-recompile iterates until clean; don't tag until `bash scripts/ci-preflight.sh` AND an actual Kotlin compile both pass. Preflight's `kotlin-structure` check (brace balance + overloads) does NOT catch these — they're semantic/API-resolution errors, only visible to a real `compileReleaseKotlin` (run `./gradlew :app:compileReleaseKotlin` or push-and-watch `Auto Build & Test`).

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

**Last Updated:** 2026-09-07 (v3.2.0 compile-failure lessons; v3.2.1 fix build)
**Related:** `QA_CHECKLIST.md` section R (Release Build), `RELEASING.md`
