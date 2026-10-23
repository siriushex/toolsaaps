# Combo History Block Integrity

Pinned upstream: `d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`.

## Defect And Minimal Correction

The native history parser compared the continuation and FIFO-gap flag bytes with
`0x48`, treating every other value as false. An unknown byte could therefore
mean end-of-history or no lost events. PumpIO confirmed the parsed block before
checking whether to read another block.

The external `combo-history-integrity.patch` accepts only the native flag pair
`0x48` / `0xB7`. Any other value raises the existing payload-corruption exception.
PumpIO's existing bounded retry path requests the same block without confirming
it. A later valid block is processed normally; exhaustion fails rather than
returning an apparently empty history. No new pump operation, background reader,
packet, preference, database or treatment writer is introduced. Existing valid
gap flags and event timestamps are not rewritten.

The six-line production guard is applied only by the local/CI compatibility
recipe. It is not an installation on a phone. The recipe checks the source pin,
owns its exact patch and reverses it only while the diff is unchanged. Unknown
concurrent edits are preserved and cause verification to fail.

## Verification

Four failing-first cases reproduce unknown continuation/gap flags and premature
CONFIRM commands. Pure tests enumerate all254unknown values for each flag and
all four recognized combinations. Synthetic authenticated transport tests prove
that two malformed blocks are read again before a valid block is confirmed, and
that exhausted malformed input is never confirmed or returned as empty history.
The complete native Combo suite includes the existing multi-block history test
with actual-gap-free headers, bolus events and a clock-change event.

`tools/aaps4_compat/verify_master.sh` runs the entire Combo module suite alongside
the existing receiver, uploader, SDK and transaction suites. Recipe tests cover
missing patches, concurrent changes, failure cleanup and the pristine SDK gate.
Full Copilot Android quality and exact-source CI remain required for integration.

## Evidence Still Missing

A valid gap flag was previously only logged. AAPS4_COMBO_HISTORY_OBSERVATION.md
now adds passive session-local status diagnostics, not durable coverage evidence
or a pre-confirmation journal. History reads consume acknowledged deltas; do not
open a second reader on a real pump. Successful reading does not recover FIFO
overflow, events acknowledged before an interrupted call, or skipped event types.
Delivery-start bounds, historical clock epochs, basal continuity and prospective
forecast validation remain independent gates. This patch cannot admit a live
meal-time recommendation or establish complete physical insulin delivery.

AAPS4_COMBO_HISTORY_JOURNAL.md now retains validated application history blocks
locally before native confirmation. It does not yet export those files into a
clinical timeline, prove initial history or supply the missing clock/basal gates.
