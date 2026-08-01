"""
skill_library/optimizer/skill_optimizer.py

Refines a skill after a failed execution by:
  1. Demoting the failed route's reliability score
  2. Promoting the next-best route
  3. Appending observed selector candidates from the execution log
"""

from __future__ import annotations

import copy
import logging
from typing import Any

logger = logging.getLogger(__name__)

_MIN_RELIABILITY = 0.10
_RELIABILITY_PENALTY = 0.15
_RELIABILITY_BOOST = 0.05


class SkillOptimizer:
    """
    Produces a refined copy of a skill dict based on failure evidence.
    Never mutates the original skill in place.
    """

    def refine(
        self,
        skill: dict,
        ctx: Any,  # ExecutionContext
        exec_result: dict,
        eval_result: dict,
    ) -> dict | None:
        refined = copy.deepcopy(skill)
        routes = refined.get("execution", {}).get("routes", [])
        if not routes:
            return None

        failed_route_id = exec_result.get("failed_route_id")
        log: list[dict] = ctx.execution_log

        changed = False

        for route in routes:
            rid = route.get("id")
            if rid == failed_route_id:
                old_rel = route.get("reliability", 0.5)
                route["reliability"] = max(_MIN_RELIABILITY, old_rel - _RELIABILITY_PENALTY)
                logger.info("Demoted route %s reliability: %.2f → %.2f", rid, old_rel, route["reliability"])
                changed = True
            elif failed_route_id and rid != failed_route_id:
                # Slightly boost alternatives so they're tried first next time
                old_rel = route.get("reliability", 0.5)
                route["reliability"] = min(1.0, old_rel + _RELIABILITY_BOOST)

        # Append any new selector candidates observed in the execution log
        new_selectors = self._extract_selectors_from_log(log)
        if new_selectors:
            existing = refined.setdefault("selectors", {})
            for sel_id, candidates in new_selectors.items():
                if sel_id not in existing:
                    existing[sel_id] = {"candidates": candidates}
                    changed = True
                    logger.info("Added observed selector: %s", sel_id)

        # Re-sort routes by descending reliability so executor tries best first
        routes.sort(key=lambda r: r.get("reliability", 0.0), reverse=True)

        if not changed:
            return None

        # Bump patch version
        refined["version"] = self._bump_patch(refined.get("version", "0.0.0"))
        return refined

    # ------------------------------------------------------------------ #

    def _extract_selectors_from_log(self, log: list[dict]) -> dict[str, list[dict]]:
        """Pull any 'observed_selector' entries from the execution log."""
        result: dict[str, list[dict]] = {}
        for entry in log:
            if entry.get("type") == "observed_selector":
                sel_id = entry.get("id")
                candidate = entry.get("candidate")
                if sel_id and candidate:
                    result.setdefault(sel_id, []).append(candidate)
        return result

    def _bump_patch(self, version: str) -> str:
        parts = version.split(".")
        if len(parts) == 3 and parts[2].isdigit():
            parts[2] = str(int(parts[2]) + 1)
        return ".".join(parts)
