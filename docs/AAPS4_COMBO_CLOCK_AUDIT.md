# Combo Clock Audit

## What Is Checked

The retained event audit preserves the original local timestamps and counters.
For an unambiguous consecutive counter after event type24 (clock setting), it
also compares the next event's local timestamp with the replacement clock in
that event. An event before the replacement is `POST_CLOCK_CHANGE_REWIND`.
This catches a forward setting followed by an impossible earlier local clock,
which comparison against the old timestamp alone would miss.

Within one read, observed contradictions remain adverse even if later bytes
repeat or conflict. Across reads, conflicting counters cannot supply a boundary.
A missing counter, numeric wrap or a conflicting setting is not repaired or used
to infer a clock epoch. Existing rewind and clock-change hazards remain intact,
including a legitimate backward setting. The audit does not decide which clock
is true, apply a timezone, shift events, or turn a request/reader ACK into delivery.

Reader start/end dates later than their containing device-status date produce
`READER_TIME_INCONSISTENT`. These dates originate from the same producer, not
the upload receipt clock. This could reflect a device-clock change or a snapshot
race; it is not proof of fabricated history. Evidence remains retained and adverse
after later clean snapshots or database reopen. Equality is allowed. A backward
read end remains the existing clock-change hazard. Raw stored documents are not
rewritten and ingestion does not infer physical time from the server clock.

## Timeout Investigation

The prior CI double-release timeout has not been reproduced or explained.
`combo-test-watchdog.patch` changes only upstream test sources. Fixed phase
markers in the double-release test identify setup, feed, connect, start, first
wait, second wait, disconnect and packet verification in retained JUnit stdout.
The60-second limit and production driver behavior are unchanged.

An independent, reproduced watchdog bug is corrected: a coroutine body can return
while its children continue. Both helpers previously cancelled their watchdog at
that point. A nested structured scope now waits for those children before
disarming it. Finite regressions verify timeout/cancellation of late children,
successful children and preservation of the original exception. This is not a
claim that the historical release timeout was caused by this bug.

## Clinical Admission Remains Separate

Passing these checks cannot authorize waiting for a glucose threshold or starting
a meal. Still required are an actually connected authenticated raw-history path,
verified pump identity/clock epochs, full delivery and basal/suspension coverage,
and prospective safety validation including stale CGM and missed food. Neither
a complete downloaded catalog nor a synthetic CI run supplies these facts.
The existing live timer and hypo protections are unchanged.

Run the combined native recipe, recipe tests, pristine SDK preparation and full
Android test/lint/compile/build. No phone or real therapy action is part of tests.
