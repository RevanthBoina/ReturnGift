"""
skill_library/evaluator/skill_evaluator.py

Evaluates whether a skill execution met its postconditions/verify assertions.
Returns a confidence score and pass/fail verdict.
"""

from __future__ import annotations

import re
from typing import Any


class SkillEvaluator:
    """
    Checks execution results against a skill's verify/postconditions blocks.
    Confidence is the fraction of assertions that passed.
    """

    def evaluate(
        self,
        skill: dict,
        ctx: Any,  # ExecutionContext
        exec_result: dict,
    ) -> dict:
        assertions = self._collect_assertions(skill)
        if not assertions:
            # No assertions defined — treat as passed with neutral confidence
            return {"passed": True, "confidence": 0.5, "failures": []}

        passed_count = 0
        failures = []

        for assertion in assertions:
            ok, reason = self._check(assertion, exec_result)
            if ok:
                passed_count += 1
            else:
                failures.append(reason)

        confidence = passed_count / len(assertions)
        passed = confidence >= 0.5 and not any(self._is_critical(a) for a in failures)
        return {"passed": passed, "confidence": confidence, "failures": failures}

    # ------------------------------------------------------------------ #

    def _collect_assertions(self, skill: dict) -> list[dict]:
        assertions: list[dict] = []
        for route in skill.get("execution", {}).get("routes", []):
            assertions.extend(route.get("verify", []))
            assertions.extend(route.get("postconditions", []))
        return assertions

    def _check(self, assertion: dict, exec_result: dict) -> tuple[bool, str]:
        kind = assertion.get("assert")
        observed = exec_result.get("observed", {})

        if kind == "foreground_package":
            expected = assertion.get("equals", "")
            actual = observed.get("foreground_package", "")
            ok = actual == expected or actual.startswith(expected)
            return ok, f"foreground_package: expected={expected} actual={actual}"

        if kind == "node_exists":
            # Check if exec_result signals the node was found
            ok = exec_result.get("node_found", False)
            return ok, "node_exists: node not found in result"

        if kind == "not_exists":
            ok = not exec_result.get("node_found", False)
            return ok, "not_exists: node unexpectedly present"

        if kind == "on_screen":
            expected = assertion.get("value", "")
            actual = observed.get("screen_signature", "")
            ok = actual == expected
            return ok, f"on_screen: expected={expected} actual={actual}"

        if kind == "toast_or_snackbar":
            contains = assertion.get("contains", "")
            text = observed.get("toast_text", "")
            ok = contains.lower() in text.lower()
            return ok, f"toast_or_snackbar: '{contains}' not in '{text}'"

        if kind == "extracted":
            field = assertion.get("field", "")
            min_count = assertion.get("min_count", 0)
            items = exec_result.get("extracted", {}).get(field, [])
            ok = len(items) >= min_count
            return ok, f"extracted.{field}: count={len(items)} < min={min_count}"

        # Unknown assertion type — pass through
        return True, ""

    def _is_critical(self, failure_reason: str) -> bool:
        critical_keywords = ["foreground_package", "on_screen"]
        return any(k in failure_reason for k in critical_keywords)
