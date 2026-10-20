# Local V3 Treatment Wire Exchange

## Scope

Copilot implements authenticated RC1 treatment create/read/patch/delete and
modification history in the existing loopback TLS listener and Room37 wire store.
This removes one unsupported collection. Profile/food remain unimplemented;
clinical projection and native master admission are still separate prerequisites.
The production `native_clinical_projection_unavailable` lock remains closed.
An uploaded bolus or target is a source record, never proof that a newly issued
command was applied. This adapter cannot invoke a therapy writer or mark READY.

## Contract

- SDK first-load uses `sort=created_at`, `created_at$gt` as an explicit-zone ISO
  instant and a bounded limit. Finish the final equal-time group, without issuing
  a modification watermark that would skip later edits. History is revision-based,
  includes tombstones and advances only through returned records. Response limits
  include metadata bytes; overflow is an explicit failure, never a partial success.
- Acquisition timestamps are milliseconds. Supplied date/ISO/legacy millisecond
  aliases must agree; fractional submilliseconds and ambiguous local ISO times
  are refused. `created_at` is normalized to the exact UTC millisecond instant.
- The generated identity binds owner, app/device, type, acquisition time and pump
  identity, not quantities, notes or timezone offset. A conflicting repeated POST
  cannot create another delivery. Explicit identifiers retain the existing v3
  identifier semantics. PATCH preserves omitted immutable acquisition/producer
  fields; changing them is refused. A stale changed patch conflicts while the
  identical retry retains its revision. Read-only rows cannot be unlocked/deleted.
- Validate the actual RC1 primitive field types and supported mapper shapes,
  including 32-bit fields, millisecond/minute duration agreement, target units,
  signed carb corrections, extended/emulated basal records and per-record ICfg.
  Extended child metadata remains separately scoped historical provenance; native
  flags are typed and child units are normalized without granting root authority.
  Unknown fields, malformed nested objects and data silently dropped by the native
  mapper are refused. A combined nonzero insulin/carbs record is not accepted as
  two treatments: the native mapper would retain only insulin.
- The native glucose unit is `mmol`, not `mmol/l`. Normalize this known alias
  without changing its value; unsupported units fail rather than default to mg/dl.
  Nonglycemic records use the native reader's explicit mg/dl default, so a
  read/create retry stays idempotent. Glucose values and nonzero-duration targets
  still require declared units; none are inferred from their numeric magnitude.
  Insulin configuration retains its label, end/peak in milliseconds and declared
  concentration. It is not replaced with the current global insulin profile.
- All writes, revisions and identity decisions share the existing transaction.
  There is no schema change, purge, replay, new background owner or clinical
  invalidation. Receipt/ACK semantics in AAPS4_PAIRING.md are unchanged.

## Verification

The new adapter tests first failed on the missing501 boundary. The actual pinned
SDK fixture exposed its `mmol` spelling and nonglycemic default on round trip;
both failures were retained before correction.
It exercises bolus/ICfg, carbs and target uploads, chronological/revision reads,
patch/deletion and final native pairing/target wire regressions over real TLS.
Fixed diagnostic stage/class labels contain no request, URL, token or payload.

Prepare with Java21 and `tools/aaps4_compat/prepare.sh`; run Copilot with Java17,
`AAPS_INTEROP_CLASSPATH` and `AAPS_INTEROP_JAVA_HOME`. Run
`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` and the existing
APK retention/size gate. Local focused checks are not full migration admission;
exact-source CI and independent intended-device acceptance remain mandatory.

Final local full test/lint/build passed on2026-10-20 in15m30s:6495 passed,six
prior private-input skips,zero failures/errors; all12 new adapter cases passed.
Actual pinned SDK fixture ran3.710s,not skipped. Lint has zero errors with378
existing warnings/eight hints. APK48175941bytes retains all7003 compiled app
classes; icon-only/size guards pass. Main/test/driver hashes remained unchanged
during this final gate. Source CI is the next gate, not device admission.

## Device Boundaries

On2026-10-20 read-only checks on the intended Redmi found a live TLS listener with
localhost/127.0.0.1 SANs and current AAPS receipt of `BgEstimate` each minute.
Public certificate inspection through a temporary USB tunnel is not Android trust
or evidence that RC1 is installed. The tunnel was removed. Stock RC1 does not opt
in to user CA trust; any eventual trust change must be loopback-scoped, preserving
normal certificate/hostname verification. No trust-all or HTTP downgrade is allowed.
Actual RC1 post-update CGM continuity is still a separate check. No phone history,
credentials or device-specific certificate is published by this source stage.
