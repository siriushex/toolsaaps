# Native Insulin Observations

Implemented source projection for retained RC1 treatments. This does not reconstruct
delivered basal intervals or attest initial upload/pump history coverage.

## Data

One bounded transaction reads the treatments collection, including tombstones,
under the existing owner and revision rules. A single cache builds profile and
insulin indexes for the same retained revision and then drops raw JSON.

- Bolus: units, NORMAL/SMB/PRIMING, basal-insulin flag and the record's own ICfg.
- Temporary basal: duration and either absolute U/hour or percentage adjustment.
  A Nightscout adjustment of-100 is retained as-100, not interpreted as100percent.
- Extended delivery: entered insulin, duration and emulation flag. For native
  emulated basal, select only the extended child and its pump/end metadata.
- Every observation: source identity/revisions, event and availability timestamps,
  invalidation, optional hashed pump key and end ID. Hash keys include native record
  kind because separate pump record types can reuse numerical IDs.

Unknown bolus ICfg stays unknown. Contradictory bolus duration, malformed amounts,
invalid source metadata or duplicate pump keys reject without partial success.
Late retained revisions cannot be used as data available to an earlier prediction.
This check applies to the entire collection revision before filtering the window,
including empty or unrelated snapshots. A later edit can move, shorten or delete
the only relevant dose, so checking the returned rows alone cannot establish
earlier absence. Latest retained versions are not a historical version archive.
Queries include intervals that start before the requested window and overlap it.
Malformed records cannot be declared expired using an unknown duration.

Native mapper precedence is not completeness evidence. A carbohydrate branch or
unknown event cannot hide entered insulin, basal rate/percentage or an emulated
child. An instantaneous bolus cannot silently discard interval fields. Conflicting
forms reject the whole observational inventory, including old records with unknown
support. Null fields, ordinary carbohydrate/exercise durations and glucose/profile
configuration alone are not delivery. Emulated wrappers still select one child,
which must explicitly be emulating basal and cannot contain another delivery form.
Transport retention stays lossless; successful storage is separate from admission.

The existing native connection owner publishes observation status and record count.
The count includes invalidated retained records and is not delivered insulin or IOB.
Stop/revocation/offline ownership clears the published observation status. No new
worker, schema migration, therapeutic table writes or AAPS patch is introduced.

## Coverage Still Required

Pinned RC1 DataSyncSelectorV3.processChangedBoluses processes local synchronization
IDs and advances its private BolusLastSyncedId after successful or ignored records.
It deliberately ignores an unchanged record already containing a Nightscout ID,
and ignores changes that only add that ID. These counters are not in treatment
payloads. A zero queue therefore cannot prove that a new destination received the
old history. Similar collection-specific selectors must be considered separately.

Initial history admission needs a source/destination-bound generation and complete
window with explicit revisions, deletions, record kinds and acknowledgements.
Existing foreign Nightscout IDs cannot simply be assumed present in the local
destination. Never solve this by silently clearing clinical rows or inventing a
watermark from row count, oldest timestamp, connection status or IOB agreement.
Pump synchronization coverage is separate from a complete retained AAPS database
window. Until those receipts exist, upstreamCompletenessVerified remains false.

Historical bolus/basal reconstruction must additionally bind recorded insulin and
effective profile at delivery time, including suspension, preemption and end IDs.
Prospective personal forecast and future-control/uncertainty validation remain
required before a recommended meal-time notification can be admitted.

## Verification

Pure decoding/window/conflict tests, native Room correction/delete/reopen, owner
lifecycle and the actual pristine RC1 SDK TLS fixture exercise this source. The
SDK fixture verifies the edited and invalidated bolus and single emulated dose.
Full Android quality and source CI are recorded in the stage delivery receipt.
