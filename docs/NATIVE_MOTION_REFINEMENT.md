# Native Motion Refinement

Date: 2026-10-15. Presentation-only follow-up to Feather 01.

The2026-10-16 approved navigation follow-up moves the owl into the existing48dp
menu action. Route accents use720ms maximum, menu glance480ms and actual AI wait
the existing2400ms maximum. A saved request captures explicit visits and consumes
suppressed gestures; color changes never restart it. Keyboard focus returns on
drawer closure. The compact overview no longer has a duplicate title owl; the
AI chat's second decorative owl becomes a static accessible hourglass. Route
fades use180ms enter/120ms exit, or none under the shared suppression gate. See the
native navigation specification and AI_NOTES.md for final verification results.

## Motion Language

- Existing Material IconButton still owns ripple, accessibility, enabled state,
  gesture cancellation and immediate click dispatch. Only the icon's graphics
  layer scales to 94%, with critically damped Compose springs (press 1100,
  release 650). There is no new pointer handler, haptic or delayed action.
- Connected surfaces: menu, event notebook, alert menu/health and the existing
  Clarity toolbar helper (overview refresh/blood check and food-curve tools).
  Touch targets, badges, tooltip text and clinical values do not scale.
- Drawer scrim and panel share one AnimatedVisibility lifetime. Independent
  scrim fades (180/120ms) and a critically damped spring (600) replace nested
  visibility blocks. Rapid reversal retargets the current transition; logical
  start direction supports both LTR and RTL. No overshoot is intended.
- Owl gestures last 480-960ms. Waiting uses one small parallel glance, a subtle
  tilt and one blink, settles to neutral within 2400ms and never loops. It keeps
  indeterminate progress semantics until the actual busy state ends.
- All 52 existing glyph recipes retain their geometry/meaning. A shared
  asymmetric envelope reaches its accent at 32% then settles over 68%, instead
  of symmetric repeated-looking sine motion. Urgent/manual glyphs remain static.

## Accessibility And Cost

The existing root motion policy now also includes RESUMED lifecycle state.
System animator scale zero, power saving and urgent presentation disable custom
motion; decorative clocks are still consumed once and do not replay on resume.
Reduced motion snaps press/drawer presentation without waiting for animation.
No perpetual transition, additional listener per icon, animation dependency,
bitmap, network request, clinical/model writer or database migration is added.
Icon frame reads happen in graphicsLayer; glyph frame reads remain draw-only.

These are bounded implementation choices, not measured physical-device frame
time, RAM or CPU improvements. Standard Material animations elsewhere in the
application are not globally replaced by this stage.

## Verification

Native Robolectric interaction tests cover interrupted/rapid presses, cancellation,
fixed 48dp targets, exactly-once existing actions, disabled controls, real press
pixel changes, LTR/RTL drawer entry/reversal/disposal and animator-scale disable.
Existing finite-clock, waiting semantics, native raster, header, event-detail and
notification tests remain applicable. Optional PNGs use FEATHER_UI_EVIDENCE_DIR.
Run Android unit tests, lint and assembleDebug on frozen sources before source
publication. Exact-head Verify is a separate gate; actual phone checks, installation
and Git main merge remain separate actions.

Local result: 23 targeted motion tests and complete 5,549-test Android suite,
zero failures/errors and 6 existing private-fixture skips. Kotlin compile,
lint and debug APK passed on unchanged sources. Lint remains 355 warnings,
8 hints, zero errors; no checks disabled. Native tests also verify that disabling
an in-flight entry preserves content state and a closed exit leaves no overlay.
