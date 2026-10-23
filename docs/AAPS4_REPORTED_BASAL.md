# Native Reported Basal Segments

This read-only source joins retained native insulin observations to historical
effective-profile windows at the same collection revision. It is not pump delivery
confirmation, complete source history, basal IOB or an input admitted to live advice.

## Native Semantics

- Temporary basal preserves its native type, absolute U/hour or percentage
  adjustment, and optional converted rate at the record's start. A contradictory
  absolute/rate pair and unknown type are rejected. A converted start scalar is
  not a constant absolute rate for a percentage interval.
- Absolute zero remains an explicitly reported zero. No record or a zero-duration
  record cannot prove normal basal or suspension. Invalidated observations cannot
  restore old versions or silently create delivery.
- Emulated extended insulin is an additional component above scheduled basal.
  Its reported component is units/duration, not total basal. Do not count both
  the outer fake TBR and its selected extended child. A standalone fake-extended
  TBR or nonzero zero-duration extended dose is unsupported.
- Ordinary extended boluses and normal/SMB boluses remain in the insulin source,
  not the basal component. They must still be accounted for in complete insulin
  modeling. A basal-insulin bolus is unsupported here, not silently ignored.
- Overlapping observations reject without truncation/preemption assumptions or
  automatic addition. End IDs and hashed pump identity remain source provenance;
  an end ID alone cannot invent a stop time. Duplicate pump identities conflict.

The segments are clipped to the requested past window and split at actual EPS
changes, preserving the original source record and each historical insulin/profile.
Gaps before, between and after records are explicit. Numeric absolute/additional
component units remain distinct from percentage adjustments, which have no numeric
units result. They are not combined into a total delivered quantity.

## Clock Context

Pinned RC1 d5e7f5b uses `MidnightUtils.secondsFromMidnight(timestamp)` with
`TimeZone.currentSystemDefault()` in ProfileSealed.getBasal. Its percentage TBR
conversion uses profile.getBasal(time), while EB.toTemporaryBasal adds the EB rate
to profile.getBasal(timestamp). A profile's serialized timezone or original offset
does not attest the device's historical clock changes. Do not expand historical
percentage rates or scheduled basal using those fields alone. This stage retains
the schedules and modifiers without reapplying or interpreting them as a clock
receipt. The inspected source files match the pinned upstream commit.

## Boundaries And Verification

Read failure, unavailable profile/source, mismatched revisions, late evidence,
malformed geometry, nonfinite quantities, conflicts or exceeded48hour/2880segment
budgets cannot yield a partial usable result. Segment/gap lists and default
rendering preserve privacy and immutability. The existing owner publishes status
and counts only, without an additional worker or clinical writer.

Pure native-shape/geometry tests, native Room correction/delete/reopen, owner
lifecycle and the actual pristine SDK TLS fixture exercise the read. Full Android
quality, source hashes, APK retention and exact-head CI remain integration gates.

Source/destination/window upload receipts, independent pump coverage, verified
historical schedule-clock context, full physical/signed accounting and prospective
meal forecast/controller validation remain required. `upstreamCompletenessVerified`
stays false. No target/dose/alarm, AAPS/phone/server or public APK change follows.
