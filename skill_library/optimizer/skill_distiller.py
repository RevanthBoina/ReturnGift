"""
skill_library/optimizer/skill_distiller.py

Distills repeated successful trajectories into a DRAFT skill YAML conforming to
schema v2.1.  Input is a list of trajectory JSONL files (produced by the
DebugReportManager P3.1a export).  The output is a single schema-valid DRAFT yaml
written to stdout (or a given output path) — human PR is the approval gate.

Security gate: a canary substring check rejects any candidate whose slots or
params contain instruction-shaped text before a draft is ever written.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import argparse
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any

# --------------------------------------------------------------------------- #
# Security: canary substrings that indicate instruction-shaped content in
# user-supplied text.  Reuses the same pattern family as P1.2's canary check.
# --------------------------------------------------------------------------- #
_CANARY_PATTERN = re.compile(
    r"(?i)\b(ignore\s+previous|disregard\s+rules|you\s+are\s+an|"
    r"override\s+(?:the\s+)?(?:system|safety)|prompt\s+injection|"
    r"jailbreak|danny\s+testing|system\s+prompt|developer\s+mode|"
    r"hypothetical\s+scenario|as\s+an?\s+ai|pretend\s+to\s+be)\b"
)

_CANARY_FIELDS = ["body", "recipient", "recipient_name", "app"]


def has_canary(text: str) -> bool:
    """Return True if *text* contains an instruction-shaped substring."""
    if not text:
        return False
    return bool(_CANARY_PATTERN.search(str(text)))


# --------------------------------------------------------------------------- #
# Data types
# --------------------------------------------------------------------------- #

class TrajectoryStep:
    def __init__(self, event: dict):
        self.task_id = event.get("taskId", "")
        self.step_index = event.get("stepIndex", 0)
        self.event_type = event.get("eventType", "")
        self.timestamp = event.get("timestamp", 0)
        self.screen_hash = event.get("screenHash")
        self.action_tool = event.get("actionTool")
        self.action_params = event.get("actionParams")
        self.app_package = event.get("appPackage")
        self.source = event.get("source")


class SequenceSignature:
    """Key used to group similar selector-chain shapes across trajectories."""

    def __init__(self, app_package: str, tool_names: tuple, param_keys: tuple):
        self.app_package = app_package
        self.tool_names = tool_names
        self.param_keys = param_keys

    def __hash__(self) -> int:
        return hash((self.app_package, self.tool_names, self.param_keys))

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, SequenceSignature):
            return False
        return (
            self.app_package == other.app_package
            and self.tool_names == other.tool_names
            and self.param_keys == other.param_keys
        )


class RepeatedSequence:
    def __init__(self, sig: SequenceSignature):
        self.sig = sig
        self.occurrences: list[list[TrajectoryStep]] = []
        self.tool_calls: list[dict] = []


# --------------------------------------------------------------------------- #
# Core distillation logic
# --------------------------------------------------------------------------- #

def load_trajectory_jsonl(path: str) -> list[dict]:
    """Load a trajectory JSONL file and return the event dicts."""
    events: list[dict] = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return events


def build_step(event: dict) -> TrajectoryStep:
    return TrajectoryStep(event)


def extract_signature(step: TrajectoryStep) -> tuple:
    """Extract a signature from a step for grouping similar chains."""
    tool = step.action_tool or "unknown"
    params = step.action_params or "{}"
    try:
        param_dict = json.loads(params) if isinstance(params, str) else params
        keys = tuple(sorted(param_dict.keys())) if isinstance(param_dict, dict) else ()
    except (json.JSONDecodeError, TypeError):
        keys = ()
    return (step.app_package, tool, keys)


def find_repeated_sequences(
    events_list: list[list[dict]], min_occurrences: int = 2, min_steps: int = 3
) -> list[RepeatedSequence]:
    """Find tool-call sequences that repeat >= min_occurrences times.

    Two sequences are considered the same shape if they share the same
    (app_package, tool_names_tuple, param_keys_tuple) — i.e. the same
    selector-chain SHAPE, regardless of the concrete values.
    """
    sequences: dict[SequenceSignature, RepeatedSequence] = {}

    for events in events_list:
        steps = [build_step(e) for e in events if e.get("action_tool")]
        if len(steps) < min_steps:
            continue

        # Build contiguous windows of tool calls
        for i in range(len(steps) - min_steps + 1):
            window = steps[i : i + min_steps]
            sig = SequenceSignature(
                app_package=window[0].app_package or "unknown",
                tool_names=tuple(s.action_tool or "unknown" for s in window),
                param_keys=tuple(sorted(json.loads(s.action_params or "{}").keys())
                                 if isinstance(s.action_params, str) and s.action_params
                                 else ()
                                 for s in window),
            )

            # Normalize param values into slots
            normalized_params = _normalize_params(window)

            if sig not in sequences:
                sequences[sig] = RepeatedSequence(sig)
            sequences[sig].occurrences.append(window)
            sequences[sig].tool_calls.append(normalized_params)

    return [seq for seq in sequences.values() if len(seq.occurrences) >= min_occurrences]


def _normalize_params(window: list[TrajectoryStep]) -> dict:
    """Replace concrete values with slot references."""
    normalized: dict[str, Any] = {}
    for step in window:
        if not step.action_params:
            continue
        try:
            params = json.loads(step.action_params) if isinstance(step.action_params, str) else step.action_params
        except (json.JSONDecodeError, TypeError):
            continue
        for key, value in params.items():
            if isinstance(value, str):
                slot_name = re.sub(r"[^a-zA-Z0-9_]", "_", key.lower())
                normalized[slot_name] = f"{{{slot_name}}}"
            else:
                normalized[key] = f"{{{key}}}" if key not in normalized else normalized[key]
    return normalized


def canary_check(fields: dict[str, Any]) -> bool:
    """Return True if ALL field values pass the canary check (no instruction text)."""
    for field_name, value in fields.items():
        if field_name in _CANARY_FIELDS and has_canary(str(value)):
            return False
    return True


# --------------------------------------------------------------------------- #
# DRAFT YAML builder
# --------------------------------------------------------------------------- #

def build_draft_yaml(
    sequence: RepeatedSequence,
    trajectory_hashes: list[str],
    risk_tier: int = 0,
) -> str:
    """Build a schema v2.1 DRAFT yaml string from a repeated sequence."""
    tool_names = list(sequence.sig.tool_names)
    primary_tool = tool_names[0] if tool_names else "unknown"

    # Derive execution.route from the observed selector chain
    route_id = f"{primary_tool}_route"
    params = sequence.tool_calls[0] if sequence.tool_calls else {}
    slots_lines = []
    for key, value in params.items():
        slots_lines.append(f"  {key}:")
        slots_lines.append(f"    type: string")
        slots_lines.append(f"    required: true")
        slots_lines.append(f"    prompt_if_missing: \"Enter {key.replace('_', ' ')}\"")

    when_conditions = " and ".join(f"slots.{k} != null" for k in params.keys())

    yaml_lines = [
        "---",
        f"schema_version: \"2.1\"",
        f"skill_id: {route_id}",
        f'version: "0.1.0"',
        f"status: experimental",
        f"owner: \"auto-distilled\"",
        f'last_validated_utc: "1970-01-01T00:00:00Z"',
        f"staleness_sla_days: 7",
        "",
        "taxonomy:",
        f"  domain: auto",
        f"  capability: {primary_tool}",
        f"  risk_tier: {risk_tier}",
        "  reversible: false",
        "  idempotent: false",
        "  est_duration_ms: 5000",
        "  est_steps: 3",
        "",
        "safety:",
        "  requires_confirmation: true",
        "  confirmation_mode: user_approval",
        "  auto_send: false",
        "  never_retry_after: []",
        "  blocklist_patterns: []",
        "  requires_permissions: []",
        "",
        "compatibility:",
        "  target_app:",
        f"    package: \"{sequence.sig.app_package}\"",
        "    tested_packages: []",
        "  android: { min_sdk: 29, max_sdk: 35 }",
        "  device_profiles: [samsung_oneui_6_phone, pixel_stock_14]",
        "  form_factors: [phone]",
        "  locales: [en-US]",
        "  requires_auth: false",
        "",
        "grounding:",
        "  fixtures: []",
        "  screen_signatures: {}",
        "",
        "slots:",
    ]
    if slots_lines:
        yaml_lines.extend(slots_lines)
    else:
        yaml_lines.append("  (no parameters detected)")

    yaml_lines.extend([
        "",
        "execution:",
        "  strategy: first_success",
        "  routes:",
        f"    - id: {route_id}",
        f"      when: \"{when_conditions}\"",
        "      handle:",
        f"        op: {primary_tool}",
    ])

    for key, value in params.items():
        yaml_lines.append(f"        {key}: {{{{ {key} }}}}")

    yaml_lines.extend([
        "",
        "provenance:",
        f"  trajectories: {trajectory_hashes}",
        f"  generated: {datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}",
        "",
    ])

    return "\n".join(yaml_lines)


# --------------------------------------------------------------------------- #
# CLI entry point
# --------------------------------------------------------------------------- #

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Distill repeated trajectories into a DRAFT skill YAML."
    )
    parser.add_argument(
        "inputs",
        nargs="+",
        help="Trajectory JSONL file(s) to distill.",
    )
    parser.add_argument(
        "--output", "-o",
        default=None,
        help="Output file path (default: stdout).",
    )
    parser.add_argument(
        "--min-occurrences",
        type=int,
        default=2,
        help="Minimum occurrences to form a draft (default: 2).",
    )
    parser.add_argument(
        "--min-steps",
        type=int,
        default=3,
        help="Minimum steps per sequence (default: 3).",
    )
    parser.add_argument(
        "--risk-tier",
        type=int,
        default=0,
        help="risk_tier for the draft — uses max of contained tools (default: 0).",
    )
    args = parser.parse_args()

    # Load all trajectory files
    all_events: list[list[dict]] = []
    trajectory_hashes: list[str] = []
    for path in args.inputs:
        if not os.path.isfile(path):
            print(f"warning: skipping missing file: {path}", file=sys.stderr)
            continue
        events = load_trajectory_jsonl(path)
        if events:
            h = hashlib.sha256(open(path, "rb").read()).hexdigest()[:12]
            trajectory_hashes.append(h)
            all_events.append(events)

    if not all_events:
        print("error: no trajectory data found in input files", file=sys.stderr)
        sys.exit(1)

    # Find repeated sequences
    sequences = find_repeated_sequences(all_events, args.min_occurrences, args.min_steps)
    if not sequences:
        print("info: no repeated sequences found — no draft generated", file=sys.stderr)
        sys.exit(0)

    # Pick the highest-frequency sequence for this run
    best = max(sequences, key=lambda s: len(s.occurrences))

    # Security: canary check on all detected parameters
    all_params: dict[str, Any] = {}
    for call in best.tool_calls:
        all_params.update(call)

    if not canary_check(all_params):
        print(
            "error: draft rejected — instruction-shaped text detected in slots/params "
            "(canary substring check failed).  A poisoned trajectory must not become a skill.",
            file=sys.stderr,
        )
        sys.exit(1)

    # Build the draft YAML
    draft = build_draft_yaml(best, trajectory_hashes, args.risk_tier)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(draft)
        print(f"Draft written to {args.output}", file=sys.stderr)
    else:
        print(draft)


if __name__ == "__main__":
    main()