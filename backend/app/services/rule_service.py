from __future__ import annotations

from typing import List

from ..models import ActionProposal, GlucosePoint, RuleDecisionOut


def evaluate_post_hypo_rebound(glucose: List[GlucosePoint]) -> RuleDecisionOut:
    if len(glucose) < 4:
        return RuleDecisionOut(
            ruleId="PostHypoReboundGuard.v1",
            state="NO_MATCH",
            reasons=["insufficient_points"],
        )

    sorted_points = sorted(glucose, key=lambda p: p.ts)
    hypo_points = [p for p in sorted_points if p.valueMmol <= 3.0]
    if not hypo_points:
        return RuleDecisionOut(
            ruleId="PostHypoReboundGuard.v1",
            state="NO_MATCH",
            reasons=["no_hypo"],
        )

    hypo = hypo_points[-1]
    after = [p for p in sorted_points if p.ts > hypo.ts]
    if len(after) < 3:
        return RuleDecisionOut(
            ruleId="PostHypoReboundGuard.v1",
            state="NO_MATCH",
            reasons=["insufficient_post_hypo_points"],
        )

    rising = (after[1].valueMmol - after[0].valueMmol > 0.2) and (after[2].valueMmol - after[1].valueMmol > 0.2)
    if not rising:
        return RuleDecisionOut(
            ruleId="PostHypoReboundGuard.v1",
            state="NO_MATCH",
            reasons=["no_rebound"],
        )

    return RuleDecisionOut(
        ruleId="PostHypoReboundGuard.v1",
        state="TRIGGERED",
        reasons=["hypo_plus_rising_trend"],
        actionProposal=ActionProposal(
            type="temp_target",
            targetMmol=4.4,
            durationMinutes=60,
            reason="post_hypo_rebound",
        ),
    )
