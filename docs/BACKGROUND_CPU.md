# Background CPU: history export

## Confirmed scope

Short Java sampling located expensive per-byte String.format calls in SHA-256
history keys/fingerprints and one export-index query per observation. Sampling
is diagnostic attribution, not an absolute CPU or battery measurement. Private
device traces and payloads are excluded from source control.

The digest now encodes each byte directly into the same lowercase hex string.
Tests compare known SHA-256 vectors and the legacy formatter, covering all 256
byte values, leading zeroes and UTF-8. This preserves existing persisted keys
and fingerprints: no migration, backfill reset or duplicated history.

The synchronizer fetches metadata for one bounded page in one query. A page has
32 source rows and at most 64 observations; empty pages perform no index lookup.
Missing entries remain missing; tombstones are loaded and still create a new
identity when a deleted observation returns. Reconciliation tests assert one
query per nonempty page and no resend of unchanged observations.

## Unchanged guarantees

- Original clinical data and all export streams/retention remain unchanged.
- Encrypted frozen-page journal, device binding, revisions, deletion detection,
  ambiguous-upload recovery and atomic index commit are preserved.
- No changes to alerts, hypoglycemia protections, Target Manager, calculation
  cadence, test mode, thermal protection, wake locks or Android permissions.
- Server authorization, model requests and medical-data transfer are not added
  by performance checks.

## Validation and limits

Run RoomAiDigestTest, RoomAiExportIndexBatchTest, ServerAiRoomHistoryTest and
RoomAiHistoryMaintenanceTest, then the complete Android unit/lint/build checks.
Native AAPS compatibility tests require a freshly prepared pinned SDK driver;
a classpath pointing to a directory does not prove its driver classes exist.

Compare device samples with equal screen/charging/test-mode/background-job
conditions. Backfill can finish between samples; its completion or changing
screen state is not evidence of a speedup. Preserve alarms while measuring.
Query-count reduction is deterministic; battery-life improvement needs longer
on-device validation. The separate AAPS TDD optimization does not form part of
this Copilot source change or APK.
