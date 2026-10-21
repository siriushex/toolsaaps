# Android Server Daily Archive

## Purpose

Continue the approved daily-review and Focus+ design with retained audit reads.
The Reports tab has a distinct server section. It does not replace the primary
local report, enter Recommendations, upload context, start a model or change
therapy. Server results remain NOT_ADMITTED / UNREVIEWED_AI_INTERPRETATION /
REFERENCES_ONLY / GLUCOSE_ONLY, regardless of a matched retained source.

## Transport And Display

HistoryClient uses only the signed daily-results/daily-result POST routes,
1 KiB requests and128 KiB responses. Connection-owned proofs, expected-device
checks and refresh behavior are reused; no caller credentials/headers or job
request IDs are accepted. A missing route is unavailable, not proof of missing
credentials. The protected owner server flag still defaults off.

Closed top-level/rendering contracts reject duplicate keys, invalid UTF-8,
unknown admission/scope states, wrong job/date/zone/revision/hash bindings,
invalid pagination and expired results. Dates use actual civil-day zone bounds.
Original reply evidence and independent selected-read checks stay distinct.
The native view shows qualitative plain text and dated source status; unused
cohort metrics remain inert JSON, not independently validated medical numbers.
There are no HTML links, automatic chat sends, treatment/action deserialization
or clinical-validity badges. Partial previews open complete text in the dialog.

## Cache And Resource Bounds

- One complete frozen summary index, at most512 rows/26 pages/1 MiB normalized
  summary payload/45 seconds, with each response independently <=128 KiB.
  This reads archived summaries, never the year of source data.
  A failed continuation cannot replace the previous complete index.
- One selected result with its own retrieval clock. Cache is a separate1 MiB
  Keystore AES-GCM AtomicFile slot in noBackupFilesDir, distinct alias/AAD/file
  from credentials, upload checkpoints and clinical Room tables.
- Cache-first display; one automatic complete refresh per device-local05:00 cutoff
  when the archive is visible. Failed attempts have5-minute in-memory backoff.
  Explicit refresh only repeats reads. No new background worker, persistent
  timer/service or hidden polling. The STARTED lifecycle owns a cancellable
  cutoff/expiry wait, not a system alarm; an already-owned read can finish within
  its bounded deadline after navigation. Later cutoffs survive a failed read.
- Local rows are sorted by report date/storage/sequence and shown20 at a time.
  Search/pagination/selection never start inference. Cached selected checks retain
  their original timestamp; manual recheck obtains a separate source check.
- Cache loading additionally requires a locally unexpired subscription/refresh
  session and no unfinished new activation. This is not online revocation proof.
  Changed device or known access refusal hides old data; an access-refusal marker
  removes cached medical text when storage is writable. A storage failure is
  visible and cannot be claimed as durable revocation/cleanup success.
- Expired rows/details are hidden and pruned on the next archive use/visible
  expiry wake. The original server expiry is never extended. There is no claim
  of physical deletion while the app cannot run. Corrupt/future-clock cache is
  refused; an explicit complete read can repair only this cache.
- Expiry wakes are best-effort lifecycle work. An in-flight owned operation or
  OS scheduling can defer a wake; re-entering the archive reruns validation.
  Visible content is a dated snapshot, not a continuous wall-clock retention
  enforcement service or a guarantee of instant erasure.
- Clock and expiry checks cover already loaded memory as well as disk restoration
  and explicit reads. Cache adoption must not transiently expose expired text,
  and a selected response crossing expiry must not reintroduce an expired row.

## Remaining Gates

This is source integration, not live server/phone acceptance. Persisted daily
opt-in/facts production, provider/policy admission, actual05:00 server dispatch,
advisory publication and clinical-domain adapters are separate. The ordinary
daily primary report is unchanged. Green source CI does not prove actual report
availability, medical correctness, current revocation while offline or therapy
safety. No remote configuration, enrollment, profile, medical upload or phone
operation is required to verify this stage.

The38 focused tests cover signed transport/strict codecs, actual synthetic
backend projection, retention/device/cache faults and native Russian compact/
actual1.8x long-text rendering. Full6168-case Android execution passed locally:
6162 successful, six existing skips, zero failures/errors. Lint/compile/build
passed without new lint issues (existing376 warnings/eight hints), with identical
reviewed source inputs before/after. Exact-head Verify is a separate post-commit
gate recorded in the PR receipt, not inferred from the local result.
