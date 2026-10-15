# Daily Facts Report Storage

Source-only implementation of stage 2 of the approved daily-review design.
No host changes, medical export, provider request, public route or therapy action.

## Contract

- Internal HistoryStore save/get/list methods reuse private per-device storage,
  ownership checks, authenticated encryption, transaction lock and shared quotas.
- The opt-in additive archive schema is version 3. Ordinary history/chat reads
  do not migrate storage. Version 3 contains the existing chat archive as well.
- Preserve the complete bounded DailyGlucoseReview, immutable revisions and exact
  retries. Reject inconsistent device/source/window bindings and clock regressions.
- Report expiry is fixed at the original civil-day end plus 365 days, a stricter
  upper bound than 365 days after late generation. Revisions cannot extend it.
- Separate FACTS_ONLY from STALE_SOURCE and model NOT_REQUESTED. A source digest
  check must be supplied explicitly; navigation does not rescan 21 days. No
  freshness claim is made when the check is absent. Preserve the old evidence.
- Paginated reads freeze archive membership, not continuing source freshness.
  Bounded authenticated summaries do not include full evidence or medical text.
- Each report is limited to 512 KiB, each logical date/zone to 32 revisions, and
  list pages to 20 entries. Exceeding limits fails, never truncates facts.

## Steps

- [x] Observe the missing archive regression fail.
- [x] Implement encrypted versioned storage and bounded internal read methods.
- [x] Verify corruption, retention, isolation, concurrency and chat compatibility.
- [x] Run full backend tests, scoped lint/compilation and source review.
- [ ] Publish reviewed source on the existing feature branch and inspect exact CI.

## Remaining Separate Work

Missing domain adapters, review policy/worker and durable scheduling, private
admin authentication/UI, operational admission and real-device acceptance remain
separate. This archive is not a daily AI feature activation.
