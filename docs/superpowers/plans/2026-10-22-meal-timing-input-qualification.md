# Meal Timing Input Qualification Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for this source-only continuation of PR18. Native subagent tools are unavailable.

**Goal:** Expose conserved insulin accounting and connect the existing cohort-qualified personal shape to complete no-button research scenarios.

**Architecture:** Extend the existing frozen engine projection, not the production controller. Compare exactly aligned, profile-matched AAPS bolus IOB to the bolus-only modeled curve and retain basal IOB as unsupported coverage. Reuse PersonalMealCandidateBuilder qualification before applying a historical shape; no coefficient or scalar IOB rescaling, new worker, confirmation button, live recommendation or therapy.

**Tech Stack:** Kotlin, existing prediction/research types, coroutines, JUnit, Gradle and Verify CI.

## 1. Insulin Accounting
- [x] Write and run failing tests for active insulin mass, inferred contribution, exact-time/profile matching, bolus versus signed net and missing basal coverage.
- [x] Add active/inferred-at-anchor fields to MealInsulinProjection using the same cumulative kernel as its steps/tail. Preserve the uncapped mass identity rather than the display IOB clamp.
- [x] Add a small research accounting result: comparison reason, bolus delta, separate basal values and full-coverage=false. Missing kernel/unaligned/scalar-only/inferred input never becomes a numeric agreement. Integrate it into MealTimingResearchAssessment without changing any trajectory or enabling notifications.
- [x] Verify focused projection/scenario/planner/onset tests.

## 2. Qualified Personal Scenarios
- [x] Write failing tests against the existing synthetic historical fitting fixtures.
- [x] Return the qualified shape only with a successful existing PersonalMealCandidateBuilder receipt, then reuse that path from the research evaluator. Bind exact meal/source revision; reject unsupported scenario profiles/durations and late completion without silently rewriting the belief.
- [x] Verify profile/DIA, sensor/calibration, time/portion/GI and unavailable-history gates. A historical response fit remains research, not clinical calibration.

## 3. Delivery
- [x] Update architecture/invariants/PLAN/AI_NOTES with actual behavior and remaining full-coverage/uncertainty/control gates.
- [x] Freeze source; run full Android test/lint/compile/assemble with the prepared pristine RC1 SDK classpath, inspect actual TLS and APK retention/size guard. Result:6676passed/six existing skips, zero failures/errors; SDK TLS2.815s, lint zero errors, all7136 app classes retained.
- [ ] Fetch/review explicit paths, commit/push to PR18 and inspect exact-head CI plus synthetic JUnit artifact. No main merge, APK publication, phone/server/AAPS changes or live therapeutic activation.
