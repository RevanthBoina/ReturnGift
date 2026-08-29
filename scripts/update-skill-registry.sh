#!/usr/bin/env bash
# Copyright 2026 ReturnGift Project. All rights reserved.
# Licensed under the Apache License, Version 2.0.
#
# Update skill registry: syncs skill_library/skills/ (source of truth for the Python
# lifecycle) into app/src/main/assets/skill_library/skills/ (what ships in the APK).
# This is the ship path for P3.1d — draft skills ship with status: experimental,
# risk_tier = max of contained tools, no auto-merge.
#
# Usage: ./scripts/update-skill-registry.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SRC_DIR="skill_library/skills"
DST_DIR="app/src/main/assets/skill_library/skills"

if [ ! -d "$SRC_DIR" ]; then
    echo "ERROR: source directory $SRC_DIR does not exist"
    exit 1
fi

# Create destination if it doesn't exist
mkdir -p "$DST_DIR"

# Sync all YAML files from source to destination
# The four-signal discipline: we only sync .yaml files from the skill_library
copied=0
for f in "$SRC_DIR"/*.yaml; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$DST_DIR/$base"
    echo "synced: $base"
    copied=$((copied + 1))
done

# Also sync any subdirectories recursively
if [ -d "$SRC_DIR" ]; then
    while IFS= read -r -d '' f; do
        [ -e "$f" ] || continue
        rel="${f#$SRC_DIR/}"
        mkdir -p "$(dirname "$DST_DIR/$rel")"
        cp "$f" "$DST_DIR/$rel"
        echo "synced: $rel"
        copied=$((copied + 1))
    done < <(find "$SRC_DIR" -type f -name "*.yaml" -print0)
fi

echo "Synced $copied skill YAML file(s) from $SRC_DIR to $DST_DIR"
echo "Next: run ./gradlew :app:assembleDebug to build with updated skills"
echo "Note: draft skills (status: experimental) require human PR approval per AGENTS.md"