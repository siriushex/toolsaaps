# Historical Profile Upload Retry

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
classes and adds synthetic tests to their existing native suites:

- A missing historical profile returns false for both CREATE and UPDATE of a
  temporary basal or extended bolus, including an emulated basal and invalidation.
- The extended-bolus selector stops that collection at the unconverted record
  without advancing its cursor. Its next normal upload retries the same record.
- Existing imported-record and identifier-only skip rules remain unchanged.
- No current-profile fallback, clinical record rewrite, cursor reset, new worker,
  endpoint, credential, dose calculation or pump command is added.

Missing profile is not an HTTP error to ignore. Even when the upstream
IGNORE_NS_V3_ERRORS option is enabled, it cannot turn an unsent conversion into
an acknowledgement. Other native HTTP-error policies remain unchanged.

## Limits

If the historical profile never becomes available, that collection remains
blocked. Newer records in that collection cannot bypass the gap; other collections
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

The existing verification recipe applies all reviewed patches to the exact clean
pin, runs the native suites, and reverses only its unchanged owned diff. Missing
patch files and concurrent changes fail without overwriting unknown work. CI
then rebuilds the pristine SDK before Copilot TLS tests. Passing these tests is
software evidence, not clinical recommendation admission.
