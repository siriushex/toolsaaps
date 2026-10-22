# Native Effective Profile Observations

## Boundary

Native effective-profile-switch (EPS) treatments are decoded into observational
snapshots. A bounded retained-history reader now resolves the last observed EPS
at a requested event time and feeds the pairing diagnostic. This is not verified
upstream coverage, complete clinical ingestion, Target Manager readiness or a
phone migration. The production `native_clinical_projection_unavailable` guard
remains unchanged. No AAPS source, therapy writer, database schema or worker is
changed.

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

## Retained-History Resolution

`LocalNightscoutV3Store.retainedSnapshot` captures the treatment head, positions
and payloads in one Room transaction, including tombstones. It refuses an
over-budget read before loading payloads; it never returns a plausible prefix.
The source limits are 10,000 latest retained identities and 4 MiB of payload.
Ordinary treatments count toward these bounds. Limit/error is a visible diagnostic,
not a missing profile, and does not authorize a fallback or target dispatch.

`AapsV3EffectiveProfileSource` caches only typed EPS evidence (not raw meals or
boluses) by treatment collection revision. Settings/entry revisions and the
existing 60-second connection refresh do not reparse unchanged treatments.
Future EPS become eligible by their event time even without a new revision.
Corrections rebuild the retained view; upload revision cannot override a later
effective event time. A latest tombstone or malformed EPS blocks fallback. Two
EPS identities at the same latest time are ambiguous, including identical
schedules or a valid/deleted pair. A later unambiguous EPS may supersede that
earlier conflict. Original temporary end never invents a reversion.

The store retains only the latest version of each identity, not a full historical
version archive. An as-of read applies those retained corrections/deletions; it
does not reconstruct what was known at an old revision. A shape correction that
no longer maps as EPS is not evidence of a complete historical profile timeline.
Source upload/backfill completion and durable cross-source reconciliation remain
separate admission requirements. Results carry the local revision, not a READY
token. No negative record or local head may establish upstream completeness.

The existing foreground connection owner publishes only the non-sensitive profile
status in Settings, separately from signed connectivity. It uses no second worker
or timer. Suspended profile IO precedes the final pairing read so it cannot turn
an already observed revocation back into connected status. Observed means
received; UI explicitly says history completeness is unconfirmed. No profile
names, numeric schedules or payloads are put in diagnostics/log strings.

Room regressions cover out-of-order uploads, exact event boundaries, original
temporary expiry, corrected values/time, cache invalidation, deletion/reopen,
conflicts, invalid evidence, bounds and atomic concurrent reads. Native Compose
tests cover the status and readable Russian 320dp/large-font layout. The actual
pristine SDK TLS fixture resolves its latest uploaded EPS without clinical writes.

Local checks, exact-head CI and main integration require separate inspected
receipts in AI_NOTES.md and the task checkpoint/PR. Test definitions are not a
claim that those checks have passed. Device trust and JugglucoNG continuity still
require the designated Redmi and are not established by the desktop TLS peer.

## Remaining Clinical Admission

The caller still needs complete upstream collection coverage, durable
revision/tombstone reconciliation, historical source arbitration, current
glucose/IOB/COB/target projection and late invalidation. A resolved `Observed`
result is only the last EPS in a bounded retained local snapshot at the requested
time, never proof that the true current profile or its history is complete.
These observations are not yet connected to
clinical calculations, AI context or production target publication. No currently
selected insulin may fill historical gaps.
