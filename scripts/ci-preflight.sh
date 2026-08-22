#!/usr/bin/env bash
# Copyright 2026 ReturnGift Project. All rights reserved.
# Licensed under the Apache License, Version 2.0.
#
# CI pre-flight: fast, dependency-free grep guards for known compile pitfalls that
# previously broke the build. Runs BEFORE Gradle so we fail in seconds, not minutes,
# and so the repo "remembers" these mistakes for any future contributor — including
# code changes submitted from inside ReturnGift via the embedded GitHub code engine.
#
# Run locally:   ./scripts/ci-preflight.sh
# Run in CI:     called as the first build step in auto_build_and_test.yml
#
# Only patterns reliably detectable by grep are enforced here. Pitfalls needing
# scope/AST analysis (e.g. bare `this` inside a coroutine lambda) are documented in
# AGENTS.md -> "Known CI compile pitfalls" instead.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0
red() { printf '\033[31m%s\033[0m\n' "$*"; }
grn() { printf '\033[32m%s\033[0m\n' "$*"; }

run_check() {
  local label="$1" pattern="$2" why="$3" includes="${4:---include=*.kt --include=*.java}"
  local hits
  hits="$(grep -rnE "$pattern" $includes app/src 2>/dev/null || true)"
  if [ -n "$hits" ]; then
    red "PREFLIGHT FAIL [$label]: $why"
    printf '%s\n' "$hits" | sed 's/^/    /'
    fail=1
  else
    grn "OK $label"
  fi
}

run_check "no-ic_menu_compose" \
  'android\.R\.drawable\.ic_menu_compose' \
  "android.R.drawable.ic_menu_compose does not exist in the platform — use ic_menu_add/ic_menu_edit/ic_menu_save."

run_check "no-return-in-default-arg" \
  '\?: return[),]' \
  "return is prohibited in a default parameter value — move the null check into the function body."

# Pitfall 6 (broke v2.3.0-20260820065431 on 2026-08-20): Java code calling a Kotlin
# function whose signature contains kotlin.Result. Result is a value class, so the
# JVM method name is mangled (write-xxxxx) and javac fails with "cannot find symbol".
# Java must call a *FromJava / Boolean-returning wrapper instead.
run_check "no-kotlin-result-from-java" \
  'kotlin\.Result<' \
  "kotlin.Result is a value class — Java cannot call Kotlin functions returning it (JVM name mangling). Use a Boolean/*FromJava wrapper (see KBManager.writeFromJava)." "--include=*.java"

# Pitfall 3 (pre-existing on main, surfaced 2026-08-18): Java tool files that use a type
# from com.returngift.agent.tool without importing it. The tv/* tools live in a sub-package
# (com.returngift.agent.tool.impl.tv), so same-package rules do NOT apply — they must import
# ToolResult/BaseTool/ToolParameter explicitly. This caught VolumeUpTool/VolumeDownTool.

audit_missing_import() {
  local symbol="$1" fsym="$2"
  local hits=""
  while IFS= read -r f; do
    # Skip the definition file itself and anything in the declaring package
    # (same-package references need no import).
    if grep -qE "^package com\.returngift\.agent\.tool;" "$f"; then
      continue
    fi
    if grep -qE "\b${fsym}\b" "$f" && ! grep -q "import com.returngift.agent.tool.${fsym};" "$f"; then
      hits="${hits}    ${f} (uses ${fsym}, no import)\n"
    fi
  done < <(grep -rln --include='*.java' -E "\b${fsym}\b" app/src/main/java 2>/dev/null || true)
  if [ -n "$hits" ]; then
    red "PREFLIGHT FAIL [missing-import-${fsym}]: Java file uses ${symbol} without importing it."
    printf '%b' "$hits"
    fail=1
  else
    grn "OK missing-import-${fsym}"
  fi
}
audit_missing_import "com.returngift.agent.tool.ToolResult" "ToolResult"
audit_missing_import "com.returngift.agent.tool.BaseTool" "BaseTool"
audit_missing_import "com.returngift.agent.tool.ToolParameter" "ToolParameter"

# Skill-source drift guard: root skill_library/skills/ is the source of truth for the
# Python lifecycle; app/src/main/assets/skill_library/skills/ is what ships in the APK.
# scripts/update-skill-registry.sh syncs them — fail fast if they drift apart.
if [ -d skill_library/skills ] && [ -d app/src/main/assets/skill_library/skills ]; then
  drift="$(diff -rq skill_library/skills app/src/main/assets/skill_library/skills 2>&1 || true)"
  if [ -n "$drift" ]; then
    red "PREFLIGHT FAIL [skill-assets-in-sync]: bundled skill assets differ from skill_library/skills/ — run scripts/update-skill-registry.sh."
    printf '%s\n' "$drift" | sed 's/^/    /'
    fail=1
  else
    grn "OK skill-assets-in-sync"
  fi
fi

if [ "$fail" -ne 0 ]; then
  red ""
  red "CI pre-flight found known-pitfall patterns. Fix them before Gradle runs."
  red "See AGENTS.md -> 'Known CI compile pitfalls'."
  exit 1
fi
grn ""
grn "CI pre-flight: no known pitfalls detected."
