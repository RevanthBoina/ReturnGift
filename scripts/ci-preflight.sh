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
  local label="$1" pattern="$2" why="$3"
  local hits
  hits="$(grep -rnE "$pattern" --include='*.kt' --include='*.java' app/src 2>/dev/null || true)"
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

if [ "$fail" -ne 0 ]; then
  red ""
  red "CI pre-flight found known-pitfall patterns. Fix them before Gradle runs."
  red "See AGENTS.md -> 'Known CI compile pitfalls'."
  exit 1
fi
grn ""
grn "CI pre-flight: no known pitfalls detected."
