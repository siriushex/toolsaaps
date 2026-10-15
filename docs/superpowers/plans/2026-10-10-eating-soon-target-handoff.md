# Eating Soon Target Handoff

## Scope

Correct existing manual Eating Soon propagation to Target Manager. Do not change
the 4.1 mmol/L / 30-minute request, safety eligibility, uncertain-delivery policy,
Copilot-priority setting, forecast cadence or backend. Do not press clinical
controls on the connected phone for a synthetic test.

## Evidence And Implementation

1. Reproduce ordinary overwrite, protective external-writer delay and missing
   Room handoff with failing regressions.
2. Bind confirmed intent to unique canonical SENT command plus active observed
   AAPS target. Preserve wire rounding, exact timing and current causal evidence.
3. Retain Eating Soon against ordinary proposals; allow existing qualified
   protective increases under unchanged opt-in and all existing safety guards.
4. Recheck intent identity at dispatch. Clear old receipt holds only after
   observed newer manager target and its unique SENT command actually agree.
   A newer independently confirmed Eating Soon can replace older canonical SENT
   holds as well; the current request and all pending/unknown holds remain.
5. Verify missing/duplicate/unknown receipts, expiry, cancellation/replacement,
   proof loss, opt-in, preflight, Room, UI status and delivery regressions; run
   full Android gates, review diff and exact-source Verify after publication.
6. Separately verify authorized phone update with fresh coherent backup, signer
   and migration checks. Installation is not a clinical Eating Soon test.

## Acceptance Boundaries

No new timer, polling, schema, setting, sound, DND or delivery channel. No replay
or automatic Eating Soon keepalive. Backend/LLM remains advisory. Private phone
data stays local; unit/CI success and natural device evidence are reported
separately, including any incomplete clinical verification.
