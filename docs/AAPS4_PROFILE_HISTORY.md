# Native Historical Effective Profiles

The existing retained-treatment source now resolves half-open effective-profile
intervals for a requested window of at most48hours and2880intervals. It includes
the last observed EPS at/before the start and every change strictly before the
end. Missing start evidence, an invalid/deleted change, a same-time conflict or
capacity failure returns an explicit status and no partial intervals.

Each interval retains the actual EPS identity/revisions, schedules, native units,
timezone, original modifiers and its own insulin configuration. Original duration
does not invent reversion. Already effective schedules are not shifted or scaled
again. Exact schedule seconds are preserved, including non-hour boundaries.
This stage does not expand those schedules into pump delivery rates.

## Availability And Immutability

`readEvidence(fromMs, atMs, knownAtMs)` shares one retained snapshot for current
profile, profile intervals and insulin observations. The profile query rejects a
collection revision newer than knownAtMs. This intentionally includes revisions
outside the requested event window: a moved/deleted latest record cannot prove
what an earlier database contained. A later unrelated treatment write can cause
conservative abstention too. A captured version archive is needed for exact
historical replay; the latest-version store is not such an archive.

The compatibility `read(atMs)` remains a retrospective latest-retained view by
event time, not an as-of knowledge claim. It cannot grant clinical readiness.
Cached schedule lists and returned interval lists are immutable; callers cannot
change the evidence used by subsequent readers. Default rendering is redacted.

The existing native owner publishes history status/count and clears them on
stop/offline/revocation through its current lifecycle. No new worker, schema,
network route, credential or clinical writer is added.

## Remaining Admission

`upstreamCompletenessVerified` stays false. These intervals are observed profile
evidence, not basal delivery, full source upload, pump coverage or insulin action.
The source/destination/window initial-history receipt, actual historical delivery
reconstruction and prospective forecast/controller validation remain separate.
No meal recommendation, target/dose change, AAPS source patch, device migration or
APK publication follows from this read-only stage.

## Checks

Failing-first regressions reproduce late profile reuse and cache mutation.
Pure interval and native Room/source/owner tests cover boundaries, changes,
deletions, conflicts, corrections, availability, reopen and bounded failure.
Full Android test/lint/build and actual pinned pristine SDK TLS are required
before source publication and verified main integration.
