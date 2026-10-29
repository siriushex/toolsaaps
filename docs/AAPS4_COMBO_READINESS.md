# Combo Readiness Diagnostics

The AAPS4 connection section now exposes a compact, expandable journal audit.
It is read-only. Opening details never starts a new transfer, pairs a device,
issues a pump command or changes therapy.

## What The Screen Shows

- Current transfer phase, separately from the last completed comparison.
- Last check date/time; matched, missing and conflicting retained reads.
- Distinct event counters and counters lacking an acknowledged history-block copy.
- Missing interior counter values, conflicting events, local-clock reversals,
  clock-change events and reversals after a clock change.
- Inconsistent reader timestamps, unknown events and warning-category count.

The audit contains only scalar observations, not raw events, pump bindings,
request IDs, credentials or payloads. No database schema, export or AI context
changes are made. Counts describe the retained snapshot, not a clinical interval.
An empty matched archive is not proof that no insulin was delivered. A reader
acknowledgement confirms a history block, not insulin delivery to the body.

The warning-category count includes locally detected contradictory remaining-event
headers. `DECLARED_HISTORY_TAIL` means a terminal flag still declares more entries
than the block carries. `REMAINING_COUNT_DISCONTINUITY` means the next block cannot
account for the prior declared remainder, even counting unknown raw events. These
are source inconsistencies, not a measured amount of missing insulin. A clean
later catalog cannot erase the retained observations. Original bytes stay intact.

Ordinary signed-probe renewal and target preemption may retain the dated last
audit. A pending or failed attempt has its own status, never a fresh success label.
Stop/restart, revocation, storage/connection failure and control disable clear the
audit. Publication stays generation-bound; late work cannot restore stale state.
All diagnostic actions remain outside the clinical-admission path.

## Required Before Meal-Time Activation

1. Identify the actual Redmi, installed AAPS build and active Combo driver. The
   source tests do not establish which implementation runs on the phone.
2. Verify actual transfer and source identity, retained journal edges and gaps,
   confirmed bolus semantics, basal/suspension continuity and missing intervals.
3. Establish historical pump-to-UTC epochs with evidence. Current phone timezone,
   receipt time or later pump-clock correction cannot supply missing old epochs.
4. Validate forecast uncertainty and the proposed meal-time policy separately,
   including missed food, delayed user response, fresh CGM and hypoglycemia guards.

Pinned RC1 `Pump.connectInternal` loads one current UTC offset, and
`scanHistoryDeltaForBolusToEmit` uses it for event conversion. The present raw
journal retains local pump times and separate reader times, not a dated series
of verified UTC epochs. Do not infer old epochs from the current phone timezone
or from the time a file was downloaded. Complete ordinary/suspended basal history
also cannot be established by bolus/clock events alone.

`MealTimingDecision.notificationAllowed` remains false; diagnostic fields cannot
enable it. A successful unit test, matching catalog or user observation cannot
replace these evidence requirements. No new APK publication follows from this
diagnostic-only stage. AAPS patches, doses and targets are unchanged.

## Verification

Run scalar audit, transfer, target-channel and native-connection Compose tests,
then full Android test/lint/compile/build and APK guards. Review Russian320dp
large-font rasters, hidden/expanded states and failed-latest/previous-audit labels.
Check exact-head CI/review before integration. Rollback removes UI/diagnostic
wiring while preserving both existing clinical and retained-history databases.
