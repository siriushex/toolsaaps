# Combo Client Transfer

## Runtime

Copilot's existing native AAPS channel owns a separate read-only transfer cycle.
It starts only with active pairing, a fresh signed session probe and the existing
native-storage subscription. It does not use the target publication lease or
change clinical admission. The lazy private database stays under noBackupFilesDir,
separate from clinical data and AI export.

The first authenticated, encrypted inventory response confirms extension support
for that exchange. No version string, unsigned setting or successful Ping is
treated as proof. A stock/unavailable peer can refuse or time out; no fragments
follow and the target connection remains usable. No Pong format, native capability
advertisement or AAPS source changes are added in this stage.

One worker owns a non-queuing permit until actual completion, including cancelled
work returning late from storage. Stop, runtime generation change, storage loss,
revocation, failed liveness and target activity cancel or invalidate it. Runtime
admission is checked inside the transaction before/after publication; failure
rolls back the document and shared counter. Once committed, a request may still
reach the peer until its original deadline. It is not reissued.
Connection failures and final state publication are generation-bound under the
runtime monitor; an old session cannot reset a new session or replace stopped state.

## Bounds And Retention

- At most one attempt every 15 minutes per channel instance. No immediate retry on
  refusal, ambiguity, cancellation or storage error. Process restart needs a fresh
  authenticated session and can start a new bounded cycle.
- At most 270 seconds, 192 requests and 2 MiB of whole reads per cycle. Per-request
  deadlines remain 30 seconds; native snapshot validity remains 5 minutes.
- Full paged inventory validation precedes fragment selection. Reuse the existing
  catalog/fragment assemblers, paired journal and private retention store.
- Skip already retained exact reads. Report conflicts without overwriting them.
  Budget exhaustion stops between whole reads; partial buffers are not persisted.
- Preserve source/retained hazards, omitted older reads, unscoped damage and writer
  failure. Recheck runtime and deadlines after private I/O; late completion cannot
  report successful transfer. The final reconciliation also checks the most recent
  authenticated response deadline, including cycles with no missing reads.

Old valid-source reply rows at/before a new request's publication revision are
ignored while awaiting its reply. Newer malformed or incorrectly bound responses
still fail. No stale reply becomes a receipt. History and target ACK slots stay
distinct; private I/O never holds the main journal transaction or target mutex.

Liveness renewal and source changes may interrupt longer cycles. The next
scheduled cycle starts with a fresh inventory and skips exact retained reads;
it never resumes partial buffers or reuses old requests. Actual device throughput
and storage cost remain to be measured before release.

## Meaning Of Results

RETAINED means the declared snapshot matches retained bytes, including adverse
flags. PARTIAL preserves missing/conflicting counts. Neither proves complete
physical insulin delivery, pump-to-UTC mapping, continuous basal coverage or safe
meal timing. An empty catalog can match an empty store without proving no insulin.
The live meal-time and native clinical target gates remain unchanged.

The phase/count flow feeds read-only connection diagnostics, not a clinical
readiness indication. See AAPS4_COMBO_READINESS.md for dated audit presentation.
This source stage does not install AAPS, publish an APK, send therapy commands or
export retained journals to the server. Device acceptance remains required.

## Verification

Run AapsV3ComboTransferTest, AapsV3ComboReadJournalTest,
AapsV3ComboCatalogRetentionTest and AapsV3TargetChannelTest, then full Android
test/lint/compile/build and APK guards. Synthetic peers and native SQLite cover
multi-fragment/page transfer, skip/conflict/budget handling, stale replies, clock
expiry/rewind, cancellation, target priority, slow storage and failed/refused peers.
Native codec interoperability and minimal AAPS patch checks remain required in CI.
Source rollback removes runtime wiring but preserves the private database.
