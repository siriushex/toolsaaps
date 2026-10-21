# AAPS4 Clinical Profile Contract

Research baseline: official 4.0.0-rc1 at
`d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`, inspected 2026-10-21.
This is the next implementation contract, not a completed clinical adapter or
permission to enable native target publication.

Catalog transport is now implemented separately in LOCAL_NS_V3_PROFILE.md.
This does not complete effective-profile resolution or clinical projection below.
Single-record typed EPS projection is specified in
AAPS4_EFFECTIVE_PROFILE_PROJECTION.md; it still cannot resolve active state or
claim collection coverage.

## Source Of Truth

- `ProfileRepositoryImpl.createAndStoreConvertedProfile` publishes a catalog:
  `date`, `created_at`, `startDate`, `defaultProfile` and named profiles in `store`.
  Each profile contains `carbratio`, `sens`, `basal`, `target_low`, `target_high`,
  `units` and `timezone`. Its default name is the first catalog item and is
  explicitly cosmetic. It must never be used as evidence of the active profile.
- The RC1 SDK posts the catalog through `/api/v3/profile`. Its reads use
  `sort$desc=date&limit=1` and `/api/v3/profile/history/{from}?limit=10`.
  The upload selector records success only after the native profile call
  succeeds. A stored catalog is not proof of complete historical coverage.
- `EffectiveProfileSwitchExtension.toNSEffectiveProfileSwitch` publishes the
  effective switch as a typed treatment, with `profileJson`, original name,
  customized name, timeshift, percentage, duration, end and original PS identity.
  Its eventType is NOTE; an arbitrary note is not an effective profile switch.
- EPS and bolus records carry their own `iCfg`: insulin label, end/peak times and
  concentration. Historical insulin must not be replaced with today's selection.
  The catalog alone cannot provide that missing insulin context.

Source paths in the pinned upstream tree:
`implementation/src/commonMain/kotlin/app/aaps/implementation/profile/ProfileRepositoryImpl.kt`,
`plugins/sync/src/commonMain/kotlin/app/aaps/plugins/sync/nsclientV3/extensions/EffectiveProfileSwitchExtension.kt`,
`plugins/sync/src/commonMain/kotlin/app/aaps/plugins/sync/nsclientV3/DataSyncSelectorV3.kt`,
`core/nssdk/src/commonMain/kotlin/app/aaps/core/nssdk/networking/NightscoutApi.kt`.

## Required Implementation

1. Add authenticated, bounded profile catalog POST/latest/history handling to
   Copilot's existing v3 wire boundary. Reuse revisioned storage and its quotas;
   refuse unsupported shapes or over-capacity pages rather than silently truncate.
   Preserve identifiers, modification cursors, duplicate semantics and deletion.
2. Parse immutable observational snapshots before projecting them. Retain source
   identifier/revision, source timestamp and receipt time, original units,
   timezone and every schedule boundary. Validate ordering, midnight coverage,
   finite values and matching low/high schedules; reject ambiguous conversion.
3. Resolve the effective profile from typed EPS evidence at the requested time,
   not from catalog ordering or a requested-but-unconfirmed profile switch.
   Preserve timeshift/percentage semantics without applying them twice to the
   effective `profileJson`. A conflicting revision or missing EPS is unavailable,
   not a default profile inferred from the catalog.
4. Project glucose/calibration, treatments, active targets and effective profile
   with explicit per-collection coverage. Missing IOB/COB is not zero. Wire
   acceptance is not delivery confirmation. Correlate corrections and tombstones;
   prevent simultaneous legacy/native ingestion from double counting an event.
5. Publish one consistent readiness snapshot for Target Manager. A partial or
   stale profile, unknown insulin configuration, interrupted backfill, unresolved
   revision, source mismatch or unknown command outcome must keep admission shut.
   Do not clear the production `native_clinical_projection_unavailable` guard
   merely because pairing or a profile POST succeeds.

This stage must not rewrite meal grams, infer laboratory GI from CGM, change the
current model/login, or enable clinical recommendations through a display flag.

## Acceptance Matrix

Planned checks, not claimed as completed:

- Actual pristine RC1 SDK POST/latest/history round trips and authentication.
- Multiple catalog names where default and active differ; EPS changes after a
  catalog snapshot; historical insulin differing from the current insulin.
- mg/dL and mmol/L, timezone and DST boundaries, fractional schedule values,
  malformed units, schedule gaps, mismatched targets and nonfinite data.
- Duplicate/edit/delete, out-of-order backfill, restart and cursor recovery,
  same-time revisions, unsupported fields and explicit capacity failures.
- Atomic clinical readiness, late invalidation and absence of therapy effects
  throughout import. Existing target expiry/revocation/ACK tests remain required.

## Transport And Device Gates

The separate `transport-privacy.patch` removes URLs from the JVM/Android engine
logger and preserves method/status diagnostics. Four synthetic privacy tests
and the full SDK suite verify requests, error responses and token refresh. It
does not alter TLS trust, credentials, endpoints or retry behavior.

Stock Android network security configuration does not opt into the user's CA
store for the loopback identity. Verify the installed build's trust against the
actual Copilot certificate; any required exception must be scoped to loopback.
Never add a trust-all verifier, disable hostname checks or downgrade to HTTP.
Desktop TLS fixtures do not prove Android trust.

Stock RC1 accepts the xDrip `BgEstimate` broadcast and includes JugglucoNG source
labels. This is a candidate compatibility path, not proof of uninterrupted CGM
on the target Redmi. Before migration, establish the live sender, source and
freshness; after migration confirm continuity, timestamps and no duplicate CGM.
ADB was empty on 2026-10-21 during this stage, so no phone acceptance was recorded.

Only after profile/clinical readiness, scoped trust, JugglucoNG continuity,
installed package/signer and target recovery are verified may a separate device
migration proceed. No live target, bolus or alert sound is a test fixture.
