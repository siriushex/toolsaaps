# Native Effective Profile Observations

## Boundary

This stage decodes one authenticated, revisioned native effective-profile-switch
(EPS) treatment into an observational snapshot. It is not active-profile
resolution, complete clinical ingestion, Target Manager readiness or a phone
migration. The production `native_clinical_projection_unavailable` guard remains
unchanged. No AAPS source, therapy writer, database schema or worker is changed.

## Native Semantics

Reference: pristine RC1 `d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`, specifically
`EffectiveProfileSwitchExtension.kt`, `TreatmentMapper.kt`, `ProfileSealed.kt`
and `InsulinExtension.kt` in the upstream source tree.

- Only a `Note` with the native EPS fields is effective-profile evidence. A
  requested `Profile Switch`, an ordinary note or cosmetic catalog default is not.
  The native mapper's higher-priority bolus/carbohydrate branches remain separate,
  even when a record also contains EPS-shaped note fields.
- `ProfileSealed.EPS` exports already transformed schedule values. Preserve the
  original percentage and signed millisecond timeshift as provenance; never
  multiply or shift the effective schedule again. Original duration/end do not
  establish an automatic reversion to another profile.
- Preserve native `mmol`/`mg/dl`, timezone, fractional values, all schedule
  boundaries and native hour-only labels with their exact `timeAsSeconds`.
  Catalog transport and EPS projection share this structural decoder. No implicit
  conversion, dynamic ISF substitution or clinical range validation occurs here.
- Record `icfg` is mandatory for a usable observation. Its times are milliseconds;
  concentration is the native ratio (1.0 for U100), not a concentration in U/mL.
  Optional nested `profileJson.iCfg` must agree. Its optional insulin nickname
  is retained separately. Missing, degenerate or conflicting configuration cannot
  fall back to today's insulin or a catalog default.
- Preserve event time, storage identity and created/modified revisions. Invalid
  source, malformed data or tombstones yield an explicit unavailable result,
  without a partial snapshot or a logged personal payload.
- The pinned local EPS DTO declares `originalPsId`, but RemoteTreatment and both
  mapper directions do not transmit it. Preserve the observed wire identity;
  do not invent a link to the originally requested profile switch. Such a link
  remains unavailable without a separately reviewed upstream wire change.
- Signed integer parsing applies only to `utcOffset`, `originalTimeshift` and
  `timeshift`. Timestamps, durations and other existing unsigned fields stay
  unsigned; the existing offset bound remains enforced. The first two new wire
  regressions reproduced HTTP400 for valid negative offsets before the fix.

## Verification

The pure projection suite covers provenance, transformed values, both native
units, historical insulin, missing/conflicting/deleted evidence, exact schedules,
malformed JSON and redacted diagnostics. Existing catalog and treatment tests
remain required. The external pristine SDK fixture uploads and reads two native
EPS records over real TLS, including negative offsets, historical insulin and
both units; the Copilot side projects the stored observations and asserts no
clinical writes or READY transition.

Local checks, exact-head CI and main integration require separate inspected
receipts in AI_NOTES.md and the task checkpoint/PR. Test definitions are not a
claim that those checks have passed. Device trust and JugglucoNG continuity still
require the designated Redmi and are not established by the desktop TLS peer.

## Remaining Clinical Admission

The caller still needs consistent collection coverage, revision/tombstone
reconciliation, effective-profile selection at the requested time, historical
source arbitration, current glucose/IOB/COB/target projection and late invalidation.
An `Observed` result means one row was decoded, never that it is current or that
the profile history is complete. These observations are not yet connected to
clinical calculations, AI context or production target publication. No currently
selected insulin may fill historical gaps.
