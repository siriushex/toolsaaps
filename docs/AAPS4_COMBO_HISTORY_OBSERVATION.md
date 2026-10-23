# Combo History Read Observations

Baseline: AAPS4 RC1 `d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`.
This is passive diagnostic transport, not delivery admission or a timer release.

## Existing Reader Only

`combo-history-integrity.patch` adds an immutable observation to the existing
PumpIO history read. No second reader, extra pump command, altered retry count,
clock correction, dose or acknowledgement order is introduced. Flags are captured
after parsing and before confirmation. A confirmation count increments only
after the existing response returns; it does not attest database persistence.

Each read has NOT_READ, READING, RETURNED or FAILED state, validated/confirmed
block counts and parsed/unmapped event counts. Recognized FIFO gaps, unknown
event types, clock-change events, malformed blocks and interrupted calls remain
flagged across later reads of the same reader instance. Counts reset per read.
Cancelled non-cancellable command exchanges must not produce a clean diagnostic
success, even if the command response eventually arrives. PumpIO cancellation
semantics themselves remain unchanged.

Pump forwards the final snapshot through its existing event callback. The plugin
retains the snapshot while Bluetooth is disconnected and exports it only for the
same paired address. Stopping the plugin clears it. A new reader may start with
no observations; prior false flags cannot be read as evidence of absence.

## Wire Contract

The existing native status path carries `pump.extended.ComboHistoryRead`:

- `version: 1`, `scope: reader_session`, `coverage: unverified`.
- `state`, `validatedBlocks`, `confirmedBlocks`, `parsedEvents`, `unmappedEvents`.
- `gapObserved`, `clockChangeObserved`, `unmappedEventObserved`,
  `interruptedReadObserved`, `malformedBlockObserved`.
- Optional `readStartedAt` and `readFinishedAt`: raw device-clock milliseconds.
  Backwards device-clock movement is preserved, not normalized. These are not
  pump event times, UTC mapping epochs or a trusted clinical interval.

No pump address/serial, insulin amount, secret or raw packet is added to this
subtree. Callback logging must redact the internal address. Native status SDK
round-trips this opaque subtree. Copilot's existing bounded immutable status
storage retains it without converting it to therapy, glucose or clinical
readiness. A later status with false flags never rewrites an older stored status.
Unsupported future fields remain lossless opaque data, not trusted assertions.

## Acceptance And Remaining Work

Verify malformed retry, FIFO gap, later clean read, unknown event type, existing
clock-change fixture, cancellation during confirmation, plugin status formatting
and paired-device scope. Exercise the pristine RC1 SDK over the existing TLS
fixture plus Copilot Room import/retry/history. Run the complete native and
Android quality suites and exact-head CI before integration.

This snapshot is not a durable pre-confirmation journal. Process death before
callback or upload can lose it. A new reader resets session-local flags; receipt
time and successful HTTP status cannot establish coverage. No full basal history,
known delivery start, historical clock mapping or prospective meal-time safety
is inferred. Those remain explicit blockers to recommended meal time. No phone,
server, APK publication, Target Manager or treatment behavior changes here.
