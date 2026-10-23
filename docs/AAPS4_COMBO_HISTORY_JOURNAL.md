# Combo History Checkpoint Journal

Baseline: AAPS4 RC1 `d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`.
This stage retains consuming-reader evidence. It does not enable meal advice.

## Capture And Isolation

The existing reader emits BEGIN, validated BLOCK, CONFIRMED and END checkpoints.
BLOCK retains the exact application history payload, including unknown event
types and original local-clock bytes, before the existing CONFIRM command.
CONFIRMED follows the native response; END distinguishes RETURNED from FAILED.
Cancellation retains the existing non-cancellable command-exchange behavior.
There is no second reader, extra pump command or conversion to UTC/delivery time.

The plugin stores checkpoints synchronously under its private no-backup directory
`combo-history-v1`. A SHA-256 binding to the paired address and pairing material
separates new pairings; keys themselves are never written to the journal. Neither
binding nor payload is included in callback logs, devicestatus or normal settings
export. Restoring old settings is not evidence of delivery continuity.

Each read has a newly created, exclusively named file. Length-delimited JSON
records carry a CRC32 and version. File contents and the containing directory are
flushed before reporting a stored checkpoint. CRC detects accidental damage, not
malicious modification or authenticity. An OS write receipt is not proof of pump
delivery or physical power-loss behavior on a particular phone.

## Failures And Bounds

Only one journal writer can own the directory. A failed append, sync, ownership
check or capacity check latches failure for that plugin lifetime. Native pump
control continues unchanged; `journalWriteFailed` and a FAILED journal status
make the missing evidence explicit. A later successful read cannot erase an
earlier unclosed, corrupt, failed or adverse read.

Files are never automatically repaired, truncated, overwritten or pruned. On
reopening, missing END records and invalid frames remain diagnostic failures.
Limits are 32 MiB total, 4096 reads, 256 records/2 MiB per read, 16 KiB per record
and 4096 payload bytes. Exhaustion stops journaling, not insulin delivery. Export receipts
and safe retention/pruning are separate work; this is not an unlimited archive.

Existing devicestatus carries `ComboHistoryJournal` counts and state only:
version, coverage=`unverified`, state, retainedReads, unclosedReads, corruptReads,
adverseReads and failedReads. Counts are read observations, not unique clinical
events. Missing/unavailable status cannot establish coverage. Final session
diagnostics retain their existing `ComboHistoryRead` subtree.

## Acceptance And Remaining Gates

Synthetic tests cover pre-confirmation ordering, observer failures without new
commands, real file reopen, torn/checksum-corrupt files, exclusive ownership,
capacity refusal, sync failures, paired-device separation, original payloads and
backwards device clocks. Run both native Combo suites, the combined reversible
recipe, pristine SDK TLS interoperability and full Copilot quality checks.

This does not attest initial history, consume journal files into a clinical
timeline, prove bolus start or normal/suspended basal delivery, establish clock
epochs, or validate a prospective meal-time prediction. Recommended meal time
remains disabled. Device latency and filesystem behavior require a separately
authorized real-device check before deployment. No APK/server/phone deployment
or therapeutic command is part of this source stage.
