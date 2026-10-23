# Insulin History Upload Integrity

Pinned upstream: AAPS4.0.0-rc1 at
`d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`.

## Failure

The native uploader converts temporary basal and extended bolus records using
the profile at the record timestamp. Without that profile, the stock transport
returns success before calling the network client. The extended-bolus selector
also counts a missing-profile record as ignored and advances its synchronization
cursor. An apparently drained queue therefore can omit insulin history.

## Minimal Patch

`tools/aaps4_compat/upload-profile-retry.patch` changes only the two uploader
classes, the native SDK, and synthetic tests in their existing suites:

- A missing historical profile returns false for both CREATE and UPDATE of a
  temporary basal or extended bolus, including an emulated basal and invalidation.
- The extended-bolus selector stops that collection at the unconverted record
  without advancing its cursor. Its next normal upload retries the same record.
- Existing imported-record and identifier-only skip rules remain unchanged.
- No current-profile fallback, clinical record rewrite, cursor reset, new worker,
  endpoint, credential, dose calculation or pump command is added.

Missing profile is not an HTTP error to ignore. Even when the upstream
IGNORE_NS_V3_ERRORS option is enabled, it cannot turn an unsent conversion into
an acknowledgement.

## Insulin Receipt Admission

The same patch now closes another silent-gap path in the native treatment
uploader. Stock RC1 acknowledges a400 failure, a successful CREATE without a
returned identifier, or a valid-record404 after its retry budget is exhausted.
The selector then advances its cursor although the destination has not confirmed
the record. An error response carrying an identifier can also mutate that binding.

For bolus, temporary basal and extended bolus (including emulated basal):

- CREATE requires200/201 and a nonblank bounded identifier without surrounding
  whitespace or control characters. A deduplication200 with a usable identifier
  remains an acknowledgement, not another insulin administration.
- UPDATE requires an existing usable identifier and200/201. The native SDK
  normally returns no identifier on UPDATE; that remains valid. A returned
  identifier must match the existing one and cannot rebind a treatment.
- A404 acknowledges only an UPDATE of an invalidated record, preserving the
  native idempotent-delete contract. It does not reconstruct historical delivery.
- Other responses return false before identifier staging or cursor advancement,
  even when IGNORE_NS_V3_ERRORS is enabled. Repeated404 for a valid insulin
  record never becomes success after an arbitrary retry count or owner restart.
- There is no fallback CREATE after a failed UPDATE, no new retry loop and no
  therapy command. Retry occurs only through the existing upload scheduler.

Other treatment types and their existing HTTP-error policies are unchanged.
The new refusal log contains fixed text only, with no identifier, payload or
server error text. The existing transport-privacy patch remains required.

## Limits

The native SDK's CREATE fallback must not invent an insulin clock offset. For
NSBolus, NSTemporaryBasal (including an emulated extended child) and
NSExtendedBolus, a400 naming utcOffset is returned unchanged, with no second
POST and no mutation to0. Signed, missing and zero original offsets remain
unchanged. The uploader's receipt guard above then keeps the record pending.
Other native treatment and glucose fallback behavior is unchanged. This can
leave an incompatible destination record blocked; do not silently change its
clock merely to drain the queue. It does not repair previously rewritten offsets.

Native MockWebServer tests cover the actual serialized request and refusal,
successful insulin uploads, unchanged non-insulin fallback, and client cleanup.
UPDATE's native immutable-clock wire policy remains unchanged; this stage does
not attest the original pump clock, interval continuity or historical start time.

If the historical profile or valid upload receipt never becomes available, that
collection remains blocked. Newer records cannot bypass the gap; other collections
keep their existing upload behavior. This is deliberate retention, not an automatic
repair. Do not clear synchronization IDs or substitute today's profile to advance
the queue. An eventual explicit gap-aware transfer protocol is a separate stage.

This does not recover records already skipped by an older build, verify foreign
Nightscout identifiers at a new destination, attest a stable full-history window,
or confirm physical pump delivery. Copilot still keeps
`upstreamCompletenessVerified=false` and the live meal recommendation gate closed.
No AAPS APK or phone update is part of this source stage.

## Checks

Three native failures were reproduced before the fix. Tests exercise real selector
and plugin methods against a mocked SDK client: missing-profile CREATE, recovery,
HTTP failure without cursor advancement, successful retry, then missing-profile
invalidation and recovery. Ordinary extended bolus, emulated basal and temporary
basal use separate cases. Imported and identifier-only records remain skipped.

The receipt extension reproduced six additional failing tests before the fix.
Real selector/plugin tests cover Combo bolus, temporary basal, ordinary extended
bolus and emulated basal:400, missing CREATE identifier, repeated404, uploader
recreation with a retained preference cursor, later success, rejected deletion
and idempotent deletion. Direct tests cover error-ignore mode, malformed or
rebound identifiers, no unintended identifier staging and no fallback CREATE.
The preference store and HTTP peer are test doubles; this is not an on-device
process-death or independent persistence proof.

The existing verification recipe applies all reviewed patches to the exact clean
pin, runs the native suites, and reverses only its unchanged owned diff. Missing
patch files and concurrent changes fail without overwriting unknown work. CI
then rebuilds the pristine SDK before Copilot TLS tests. Passing these tests is
software evidence, not clinical recommendation admission.
