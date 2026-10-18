# Compact Meal And Therapy Evidence

## Scope

Continue the approved model-context-only design. Preserve every selected latest
MEAL/THERAPY event in the requested window, not just twelve recent examples.
Raw uploads/archive, authenticated selection, retention, default host, model
settings, native threads and clinical actions remain unchanged. No deployment.

## Representation

Use versioned tables with shared payload columns and a source/quality/context
codebook. Each row retains its original record UUID/revision, exact event,
observation and receive times, sequence and unrounded payload values. Relative
millisecond times compress representation only. No duration, dose, absorption,
GI or food-bolus linkage is inferred. Null versus absent fields remain distinct.

Keep focus records for all domains as an explicitly overlapping view, not new
events. MEAL and CARBS records may overlap and must not be added together without
independent linkage. Metadata flags apply to selected uploaded data, not complete
device activity or confirmed physical insulin delivery.

The existing64KiB supplied-section and96KiB frame limits still apply. Build the
tables with bounded incremental byte accounting; saturation refuses preparation
without inference or a partial receipt. No silent event truncation or new route.

## Steps

- [x] Add RED regressions for >12 events, exact round-trip/provenance and limits.
- [x] Implement a bounded pure table builder and connect authenticated selection.
- [x] Preserve focus references, unknown delivery, approximate grams and GI sources.
- [x] Verify revision/deletion/device isolation, immutable receipts and no partial success.
- [x] Reproduce repeated cohort preparation; reuse source cohorts with fresh moments per layout.
- [x] Inspect combined 3/7/14-day packet sizes and unchanged trusted store limits.
- [x] Pass scoped lint/format and explicit dense-history refusal checks.
- [x] Confirm full backend checks (1886 passed/27 skipped) and source compilation.
- [ ] Publish reviewed source only and inspect exact-head Verify CI.

Host activation, real token accounting, complete typed profile/sensitivity/activity
coverage, operational acceptance and deferred APK publication remain separate.
