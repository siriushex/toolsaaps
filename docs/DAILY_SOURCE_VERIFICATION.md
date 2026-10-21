# Frozen Daily Source Verification

Internal source-only HistoryStore.check_daily_glucose_report_source selects an
authenticated archived revision and verifies its source in one protected SQLite
read transaction. No route, model, worker, schedule, database migration, result
publication, phone or therapy operation is added.

## Replay And Actual Check

The original calculator hashes include original as-of and global history sequence
cuts for reproducibility. Recomputing at today's cuts would falsely invalidate
unchanged evidence; echoing the stored hash would miss source changes.

The checker instead reads the latest retained GLUCOSE records and SENSOR_CHANGE
events in the original 21-day comparison plus report-day window, including its
15-minute boundary guards. Archive selection, current high-water and source reads
share one lock, transaction, five-second wall deadline and 50-million-instruction
SQL budget. Maximum scanned rows is the smaller of the store quota and 100000;
authentication, replay and the exit deadline must all succeed. No partial result
is returned on a capacity, integrity, SQL or wall-time failure.

For digest reproduction only, the pure calculator receives the original clocks,
sequence, identity binding and full archived analytical ranges. Those ranges are
not clipped to the report day. The result separately records the actual check
time/current sequence/window, original cuts, exact archive key/revision/full-facts
digest and replayed digests. It never represents the replay cuts as a current
facts snapshot or rewrites the original archive.

The right guard is searched up to min(check time, civil-day end plus 15 minutes),
even if the original report was generated exactly at midnight. A later observation
can complete a segment at the day's end and must invalidate the earlier report,
not be filtered out by its old as-of cut. Its replay digest stays absent rather
than pretending that the observation existed at the earlier time.

## Outcomes

- MATCHED / SOURCE_MATCHED: source and complete deterministic facts reproduce.
  Unrelated uploads outside the consulted window, irrelevant therapy events and
  time/global-sequence advancement alone do not invalidate this result.
- CHANGED / SOURCE_CHANGED: current eligible inputs reproduce a different source
  digest, including late uploads, revisions, quality/context changes and deletions.
  Tombstones are authenticated before their former evidence is treated as absent.
- CHANGED / NEWER_OBSERVATION: relevant live evidence was observed after the old
  cutoff. It cannot be silently omitted or replayed under an invented earlier time.
- UNAVAILABLE / RETENTION_WINDOW: any part of the consulted interval has reached
  retention, even if its archived inputs were empty. Purged absence is not proof.
- UNAVAILABLE / HISTORY_ROLLBACK: current history sequence is behind the frozen cut.
- UNAVAILABLE / FACTS_MISMATCH: source digest reproduces but full facts do not.
  A source hash alone cannot validate archived derived metrics.

Missing, expired or not-yet-visible selected reports return None. Invalid selection
fails before storage; corrupt selected archive/source data yields a storage error,
not a MATCHED/CHANGED assertion. Reads never create a device database or migrate an
existing archive schema. Existing files/manifest/identity/AEAD rules are reused.

## Limits And Integration

This is a trusted point-in-time observation of retained server history, not a
signed transferable certificate or continuous freshness guarantee. Provenance
remains DEVICE_DECLARED, completeness UNKNOWN and scope GLUCOSE_ONLY. It does not
establish whether the phone uploaded everything, whether its CGM was accurate, or
whether absent/deleted/unselected rows in an operator-modified file are trustworthy.

Analytical ranges are frozen report inputs, not current temporary targets or a
verification of today's range settings. Changed intended analysis settings need
an explicitly regenerated report. No independent meal/insulin/controller/alarm/
physiological-domain validation or clinical recommendation admission follows.

The existing prepare_daily_glucose_review/validate_review source assertions remain
caller-supplied; their NOT_INDEPENDENTLY_VERIFIED and NOT_ADMITTED labels remain.
Separate checked preparation/reply helpers now consume this result in the same
protected snapshot as construction, rejecting superseded or non-reproducing work.
See [CHECKED_DAILY_REVIEW.md](CHECKED_DAILY_REVIEW.md). No worker is active yet.
A future worker must bind the exact
report revision/facts/device, perform this protected check before dispatch and
again before publication, and separately enforce provider/stop/quality/admission,
encrypted durable work/results and uncertain-state handling. An earlier MATCHED
object cannot authorize a later result or send therapy commands.

## Verification

Synthetic tests cover moving cuts, irrelevant/late/boundary records, revisions,
tombstones, metadata/ciphertext integrity, exact owner/device/revision, full
historical ranges, retention/purge, sequence rollback, full-facts mismatch,
concurrent writer exclusion, civil-day/DST clocks and deadline/row failure.
No real medical payload, provider/model request or deployment test is performed.
