# Insulin Inventory And Timer Safety Release

## Scope
Continue from verified PR20/main7e693fe8. Audit the research inventory before
simulating meal scenarios and publish a tested0.1.10/code11 internet update.
Do not change AAPS, live glucose/insulin estimation, targets, doses or alarms.

## Implementation
- Inspect every current/replay-relevant insulin record, including rows omitted by the
  existing kernel parser. Missing/invalid/unsupported dose, conflicting aliases,
  cancellation, priming/local-command provenance and repeated non-null source IDs
  must remain explicit unsupported inventory. Do not invent or silently fix doses.
- Zero dose is not an omitted positive dose. Recorded SMB remains an insulin
  contribution, although it cannot serve as a meal-intent/timer anchor.
- Reject incomplete inventory before returning a partial research scenario batch.
  Numeric IOB comparisons remain separate from upstream completeness. Existing
  fullIobCoverage and live recommendation permissions remain false.
- Preserve the first low grid point as a diagnostic; use the beginning of its
  preceding interval for conservative waiting/response checks. This does not
  validate continuous trajectory uncertainty between sampled points.
- Require regression tests, full local Android quality, exact-head CI, unchanged
  application class retention and signer, reviewed main integration and independent
  public APK/download/Range/metadata verification before announcing availability.

## Remaining Clinical Admission
No current source attests a full delivery backfill. Complete pump delivery,
historical profile/sensitivity, simultaneous prospective uncertainty and future
controller behavior remain required before a live recommended meal-start message.
The existing durable insulin-onset clock remains available and unchanged.

## Publication
Only content-addressed APK, public page and latest.json are updated on the existing
static server. Preserve prior metadata privately and retain old APKs. No API
restart, profile copy, database export, model request or phone action is needed.

## Local Verification
Final130focused tests passed. Full frozen quality passed6724tests with six existing
private-input skips, zero failures/errors. Real pristine RC1 SDK TLS ran3.038s;
lint has zero errors,382existing warnings/eight hints. Build9m3s. Source hashes
unchanged; all7147compiled app classes retained. Release helpers14tests passed.
Original main7e693fe8 CI39839374032 passed all three jobs. Exact new-head CI and
public delivery remain separate gates. Initial historical fixture error and
cancelled first full run are retained in private execution artifacts.
