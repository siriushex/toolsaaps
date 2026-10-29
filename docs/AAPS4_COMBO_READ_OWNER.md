# Native Combo Read Owner

## Connected Path

The candidate `combo-history-owner.patch` replaces the reserved family's terminal
refusal with a bounded authenticated handler. The existing MAC, replay and skew
checks still run first. Reserved types never enter Hello, scene, bolus or other
therapy dispatch. Only the master build, an active pairing, enabled client control
and a strict version1 query can read. The own installation ID is read, not created
or taken from the request. No public component, transport permission or new key.

One non-queuing worker runs on a background dispatcher. It uses the active pump's
existing `RetainedPumpHistorySource`, never a pump connection or command. Neither
archive I/O, encryption nor the HTTP reply holds the command mutex. Successful
work has a cooperative30s timeout; terminal failure publication is separately
bounded to10s. Blocking filesystem work may outlive cancellation until it returns,
but keeps its one permit, rejects new work and cannot publish a late success.

## Admission And Ownership

Before/after I/O and encryption, recheck the original request deadline, coroutine,
receiver generation, current credential/creation time/counter, control setting,
master identity and exact transport instance. Recheck the source instance and its
private reader epoch. A later authenticated command supersedes pending read work.
Busy reads consume their authenticated counter but do not queue or write another
concurrent history reply; they remain unconfirmed and require a fresh request.
Invalid signatures, stale counters and response echoes still have no effect.

An initial inventory replaces the one retained snapshot. A continuation must match
client, master, credential and pairing creation time plus the codec's snapshot,
cursor and fragment contract. A snapshot is usable for at most five minutes with
wall-clock rewind and monotonic elapsed-time checks. No partial raw buffer is
retained between calls; the existing4096read/32MiB/2MiB limits still apply. Preserve
all adverse/unscoped counts. No sorting, pruning, clock conversion or delivery claim.

Owned fragments and per-job secrets are cleared on exit. Invalid source, failed
I/O, cancellation or uncertain publication closes only the reader, not the journal
writer. No source deletion/retention ACK, therapy state or pump command is added.

## Reply Boundary

Only native encrypted frames enter the signed `aaps_clientcontrol_ack_history_`
slot. Ordinary target/delivery replies are unchanged. History writes propagate
cancellation, reject non-2xx responses and never log raw transport/parser causes.
Once publication starts, neither an exception nor a late admission failure sends
a second response or retries the uncertain operation.

An HTTP request already started cannot be retracted by later revocation. Copilot
must still require the exact original counter/request, scope, deadline, MAC and
AEAD checks. Current-value checks are not a persistent authorization or pump UTC
epoch; unobserved external state restoration is not resolved by this worker.

## Verification And Remaining Work

Run the combined reversible native recipe with `ComboHistoryReadOwnerTest` and
`ClientControlReceiverTest`, then pristine SDK preparation and full Copilot quality
checks. Synthetic tests decrypt original bytes, preserve adverse evidence, reject
expiry/rewind, missing or changed source and credentials, cancel reads and exercise
busy work and priority while file/network operations are held. Real-thread latch
tests use real-time guards, not a virtual scheduler racing a real dispatcher.

This connects the candidate native request handler, not automatic transfer on the
phone. Capability negotiation and the Copilot background lifecycle remain separate;
no capability is advertised by this patch. Device I/O, complete recorded delivery,
basal/suspension coverage, pump clock epochs and prospective meal-time safety still
require validation. No app install, clinical timer activation or APK publication.
