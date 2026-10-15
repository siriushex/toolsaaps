# Informative Chart Event Popup

Date: 2026-10-13.
Status: expanded existing popup approved; implementation authorized on 2026-10-13.

## Scope And Layout

Keep ClinicalForecastChart's current AlertDialog and marker/cluster selection.
Do not replace it with a sheet or a new screen. Each selected event is an
unframed compact section, separated from the next by a divider. Show a meaningful
localized heading once, exact event date/time, type-specific facts, source/status
and a nonempty human note. Do not repeat identical raw title/type labels or dump
raw attributes, JSON, internal IDs, marker strings or policy codes.

Only Close/dismiss/back controls are present. Opening, scrolling, selecting a
cluster and closing are passive. No target, carbs, insulin, calibration, profile,
event deletion or retry command is attached to the popup.

## Meaning And Available Data

Use original event start time, never receipt/current time. Point occurrences
such as blood checks are not displayed as six-hour procedures. For continuous
context events, show start/end and lifecycle. For modeled meal/context envelopes,
an end is a chart-context window, not confirmed full absorption or insulin
delivery. Do not label an expired envelope as food fully absorbed.

Data come from the selected event and its known existing source projection,
not another event, current total COB or an unqualified nearest downsampled chart
point. Missing numbers are unavailable, never zero or guessed. Quantities need
finite values, known units, canonical/recognized aliases and conflict handling.
Known physiological/clinical parsers should be reused, not a new competing amount
or insulin-delivery interpretation.

- Meal: recorded grams, explicitly recorded food profile and optional recorded
  insulin only when the selected source supplies them. Do not invent GI, a
  per-meal remaining COB or a confirmed uptake endpoint.
- Activity: explicit intensity/duration/steps where supplied; distinguish planned
  from detected/actual source. Default severity is not a measured intensity.
- Blood check/calibration: exact measurement time, stored canonical blood value,
  matched CGM value if supplied and original check status. VALID means accepted
  reference, not proof that a calibration model was applied. Missing pairing or
  application remains unknown. The current calibration source has these values
  in BloodGlucoseCheck but drops them from graph details; optional display-only
  projection may preserve them without a new query or changing trustPenalty.
- Sensor/delivery diagnostics: show reported or suspected issue and available
  note/status, not a newly diagnosed failure, recovered delivery or confirmed
  physical infusion-set wear.
- Stress/sleep/illness/hormonal/medication/alcohol/custom: user title/note and
  explicit context interval; no unsupported diagnosis or percent effect.
- UAM: clearly marked model context, not confirmed eaten food or a carb command.

Source labels separate manual input, AAPS record and automatic detection/model
context. A record/command is not proof of physical delivery. Do not claim an
event influenced the accepted clinical forecast without that exact evidence.

## Architecture

Add a pure typed presentation projection next to the existing event UI models:
localized labels/rows and typed status descriptors, with formatting in Compose.
Replace only the current three-text block with a shared event-detail component.
Unknown subtype/attribute falls back to bounded human title/note and source/time.

If existing source projection gains optional display values, preserve all
existing clinical fields, attributes, severity, duration, identity and revision.
Never evict clinical keys to fit an attribute cap. LocalEventImpactAnalyzer's
known inputs (including trustPenalty) and all controller inputs stay unchanged;
regression tests must verify identical impact before/after display enrichment.
No historical query, worker, schema, permissions or source inference is added.

Selected IDs resolve against current event state, preserving cluster behavior.
Missing/deleted selections must not reopen a popup later from stale IDs. Updates
must not silently relabel the old event with another record's values.

## Verification

Pure cases cover all event types, canonical aliases, conflicting/nonfinite/missing
numbers, point versus context duration, lifecycle, automatic/UAM labels, accepted
blood check versus applied calibration, unknown subtype and meaningful notes.
Source tests verify any optional display enrichment does not change clinical
impact or drop existing keys.

Native synthetic tests tap single and clustered markers, inspect meaningful
rows and dismiss/back. Assert no action callbacks. Check light/dark,320px compact
portrait, landscape and large system fonts; values/units/notes fit and scrolling
is available only when needed. Avoid a nested card layout or hero-scale text.
Use existing Material icons/resources and typography. User data/screenshots
remain private. Full local Android test/lint/compile/assemble precedes publication
under the user's current verification preference.

## Non-Goals

No new graph event categories, automatic food/GI inference, clinical provenance
capture, therapy tuning, editable forms or changes to the familiar Food menu.
The independently approved personal meal-source stage continues separately.
