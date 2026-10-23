# Native Insulin Time Evidence

Date: 2026-10-23. Observational source checks; no meal recommendation admission.

## Recorded Clock Integrity

The retained insulin index preserves each selected record's original optional
`utcOffset` in minutes. Missing is unknown, not UTC or the current phone zone.
The UTC `date` is never shifted by that offset. Signed integer/range validation
matches the wire contract. Retained `mills`, `timestamp` and millisecond-precision
`created_at`, when present, must describe exactly the same instant as `date` and
the store's event timestamp. These checks also cover older stored payloads.

An emulated extended-bolus child must carry its own date, matching its wrapper.
Both offsets must agree if both are present. The selected child's missing offset
is not filled from the wrapper. Its amount, duration and pump identity remain
child-owned; no planned amount is reclassified as delivered insulin.

A contradictory/missing insulin time anchor invalidates the retained inventory
before temporal filtering. A future-dated wrapper or alias cannot hide an earlier
child from a queried window. No partial basal projection follows from the invalid
inventory. Storage stays lossless; correction at a new revision refreshes the
existing cache, including after native SQLite reopen.

The same whole-inventory rejection applies when payload or delivery-form parsing
fails before the clock is validated. Its retained event date alone cannot prove
that delivery is outside the query. This includes future-dated malformed records
and invalidations; neither may disappear as absent insulin. Transport retains the
original payload. A valid correction refreshes the cache and restores ordinary
window selection, without creating a source-completeness or delivery receipt.

## Accu-Chek Combo Semantics

The pinned AAPS RC1 source is revision
`d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`. Its ComboV2 plugin calls
`syncBolusWithPumpId` for QuickBolusInfused/StandardBolusInfused. The native
parser describes standard infused events as finished; these are not delivery
start receipts. The native uploader serializes the stored timestamp and pump
type, but does not include both requested/infused events or a clock-quality receipt.

Accordingly, a retained instantaneous record labelled `ACCU_CHEK_COMBO` receives
`COMBO_BOLUS_START_UNVERIFIED`, not a fabricated beginning or a confirmed meal.
Other boluses retain unspecified timestamp semantics. Intervals carry a reported
start, not proof of physical delivery. Pump metadata is uploader provenance, not
independent attestation of the installed driver. NORMAL/SMB/PRIMING and original
units stay unchanged. A valid timestamp or pump ID does not prove completeness.

The existing native owner publishes only counts of active Combo records with
unverified starts and active insulin records missing UTC offsets. Invalidated
records do not contribute; unavailable reads, stopped ownership or lost eligible
connection clear these diagnostics. No second reader, worker, transport, schema,
clinical write or permission is introduced. Counts do not admit dispatch.

## Remaining Evidence

- The driver consumes and acknowledges pump-history delta blocks. Copilot must
  not independently call that reader; an empty delta is not complete history.
- Reconnect TBR endings can be inferred from stored state and the current screen.
  The current basal profile cannot repair offline delivery or suspension gaps.
- Current/stored UTC offset and later clock correction do not validate every
  historical clock epoch or the time precision of delivery start/completion.
- Source-to-destination completeness, actual pump/driver identification, complete
  basal accounting, forecast uncertainty and missed-food scenarios remain required.
- Prospective clinical review/validation is separate from these synthetic tests.
  Existing onset UI, glucose protections, meal notification gate and therapy
  admission remain unchanged. No ideal eating interval is inferred here.

## Verification

Failing-first cases cover malformed offsets, inconsistent aliases, missing or
contradictory child clocks and accepted wire data in native SQLite. Additional
cases cover signed offsets without UTC shifts, equivalent timezone spellings,
submillisecond conflicts, outside-window contradictions, Combo reason retention,
cache/reopen/correction, redaction and owner diagnostic cleanup. Run focused
inventory/source/basal/owner/wire tests, the real pinned SDK TLS test, full Android
test/lint/build, APK retention and exact-source CI before main integration.
