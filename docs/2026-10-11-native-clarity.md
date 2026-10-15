# Native Clarity Interface

## Scope

The selected Clarity design is implemented in the native Compose Overview, not
an embedded HTML prototype. It is a selectable, flat light/dark theme. One
UI-only migration selects it once; subsequent user style choices are retained.
The old styles remain available. Clinical settings and sensitivity revisions
are not changed by this migration.

Overview groups glucose and the Target Manager together. Observed target,
proposal, manager mode, CGM age and synchronization age are distinct. A
proposal or HTTP acknowledgement is never presented as confirmed AAPS receipt.
Fresh accepted predictions have separate 5/30/60-minute details. IOB, COB,
blood checks and the existing actionable profile/pump warnings stay reachable.
Large fonts stack the target and prediction controls instead of shrinking text.
Daily Analytics and safety navigation are retained.

After a newer CGM point, the old accepted clinical forecast is not moved to the
new point's clock. Clarity horizons, Overview forecast/CI and the Forecast view wait for
the matching accepted generation. This presentation rule does not alter
clinical prediction, sensitivity or target policies. Retained food remains on
its original historical clock with its independent updating/outdated label,
including when a previous visual style is selected.

## Eating Soon

The fixed action opens an explicit confirmation for the existing canonical
request. It uses `AppContainer.submitManualEatingSoon`, not a generic temporary
target or a fabricated carbohydrate entry. Reservation, idempotency, priority,
freshness, chronology and hypoglycemia guards are unchanged.

The presentation distinguishes local queue, sent-awaiting-observation,
confirmed, blocked and unknown. A restored pending record is unknown, not a
claim that a request is still in flight. Confirmation requires the existing
exact active-target proof, including canonical receipt identity, ownership,
duration, value and chronology. UI readback uses a bounded Room transaction
and existing clock authority without writing clock markers or telemetry.
An unknown result does not offer automatic retry. A known sent intent ages out
of its canonical 30-minute window without implying historical confirmation;
another explicit intent gets a new identity and the same delivery guards.
Expiry uses one scheduled UI boundary, not polling.

## Accepted Food Display

Versioned food payloads resolve their exact historical CGM origin. The parser
still enforces that timestamp; a new sample cannot move the accepted curve.
Legacy payloads cannot be reconstructed on a sample newer than their accepted
generation. They remain partial compatibility projections.

One Overview pipeline writer retains at most one validated display snapshot
(maximum 145 points / 12 hours). It survives UI resubscription while the
ViewModel exists and is separate from accepted clinical forecast authority.
A new accepted generation replaces it atomically; older generations cannot
roll it back. Settings/source/calibration identity changes, acceptance scope
reset or missing origin invalidate it.

COB is still the fresh external AAPS reference. It is not used to scale or shift
the line. The UI marks current, updating, outdated, unavailable or inactive
display. Valid zero COB alone does not erase active modeled food influence.
Positive COB with no valid tail or after its endpoint has an unknown absorption
end. Existing GI shaping remains display-only and explicitly estimated.

The full-period detail has start, fit and end controls and existing pan/zoom
accessibility. Food-only viewports are supported even without visible observed
glucose or clinical prediction points. This remains the accepted food-only
influence projection; no remaining grams or absorption rate are invented from
it. Retained food display cannot populate clinical horizons, ISF/CR or target
decisions.

## Verification And Rollback

Regression checks cover canonical delivery presentation, exact readback,
unknown/pending results, expiry, migration isolation, retained origin/scope,
zero/positive/unknown COB, full 12-hour domain and narrow native light/dark UI.
Native screenshot evidence is private and synthetic. Device verification is a
separate gate; it uses no test targets or boluses.

A current-data copy exposed an old committed tuple whose CGM origin was later
than its publication marker. The reader correctly rejects it; migration and
integrity checks are independent of this freshness failure. Publication now
allocates its marker from the completion clock, not the earlier input cut,
checking generation freshness before allocation and again after its bounded
sequence wait. A clock rollback is rejected before persistence. Future/stale
inputs and existing exact tuple/readback guards are not relaxed, and old
historical records are not rewritten.
Runtime telemetry and maintenance retain their frozen input-cut timestamps;
publication does not relabel old inputs or move therapy query bounds forward.

UI rollback is selection of a previous style in Settings. APK rollback requires
the verified previous signed APK and compatible schema; never uninstall or
clear phone data as a substitute. No database migration, backend change,
clinical coefficient change, alarm activation or therapy arming is included.
