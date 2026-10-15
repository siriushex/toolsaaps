# Approved Owl Navigation: Native Stage

Owner approved the owl-navigation-motion prototype on2026-10-16. This stage
implements that presentation, not one of the separate AI-analysis layout choices.

## Scope

- One38dp owl inside a stable48dp Material menu button on every root route.
  Remove the separate decorative title owl and hamburger. Existing menu owner,
  actions, badges, back handling and drawer lifetime remain unchanged.
- Preserve compact overview action space; its intentionally empty title does
  not become another logo. Other routes retain their current text titles.
- Use the existing fresh CGM owl band, never forecast, report value, current
  navigation time or a new clinical threshold. Missing/stale data stay unknown.
- Explicit route visits request a finite greeting, forward gaze, nod or listening
  tilt. Header route accents are at most720ms, menu glance480ms. Revisiting via
  a route transition may play; recomposition/same-route tap/color changes do not.
- Actual AI chat/clinical-report busy state requests existing finite waiting
  artwork. Motion stops within its existing2400ms cap; real busy semantics stay
  until completion/cancel/failure. No request is generated or completed by motion.
- Rapid transitions replace only presentation state. Suppressed gestures remain
  consumed when motion is reenabled; background/resume does not replay them.
- One existing root motion policy owns lifecycle/reduced motion/power/urgent
  suppression. No new per-icon settings observer, permanent frame clock, network,
  Room observer, therapy writer, event acknowledgement or data migration.
- Accessible open/close menu description and expanded/collapsed state; return
  keyboard focus to the owl on drawer closure. Artwork semantics do not duplicate
  the menu button. Clinical values remain separately accessible as before.
- Remove the second chat waiting owl; keep a static hourglass with real busy
  semantics, without introducing another indefinite animation.
- Root route fades use180ms enter/120ms exit, or no transition when the shared
  motion policy suppresses decoration. Data arrival and actions are not delayed.

## Notifications

The current glucose notifier already provides one optional static colored owl
as its large icon. It uses the decision's fresh current CGM, not the forecast.
Retain the mandatory risk smallIcon, existing channels, text, intents, mute row,
episode claims/receipts, priority, sound/vibration and delivery conditions.
There are no additional decorative images in that custom content. Do not replace
the real smallIcon with a transparent image or create a color-refresh scheduler.

Public/lockscreen content stays gray without clinical values or glucose colors.
Bitmap failure must not prevent the alert. Extend tests to prove the existing
single-image design and action/channel preservation instead of rewriting delivery.
Other diagnostic/foreground notifications do not gain invented current CGM or
new data polling. Their existing system status/risk symbols remain meaningful.

Android controls its system template and may show the app icon independently:
[Builder](https://developer.android.com/reference/android/app/Notification.Builder),
[Android12 custom templates](https://developer.android.com/about/versions/12/behavior-changes-12#custom-notifications).
This stage does not promise identical Redmi/SystemUI icon layout or recoloring
an already-posted alert without an existing notification delivery/update event.

## Verification

First observe a red native header test for the missing owl menu. Cover route,
same-route/recomposition/color invariance, waiting edges, fast menu/navigation,
reduced/urgent/power suppression without replay, fixed48dp target and exactly-once
actions. Native raster evidence must show changed/settled frames, not only tags.
Retain existing compact320dp/large-font/dark/RTL/drawer tests. Add notification
tests for fresh/stale large icons, one bitmap/no big picture, redacted public
content, stable small risk icon/actions/category/priority/channel and graceful
optional-artwork behavior.

Run focused native tests, then complete Android unit/lint/assemble/Kotlin gate.
Review/publish only source/tests/docs under the standing feature-branch rule,
verify exact-head CI and retain a signed APK locally. No installation, notification
delivery/sound test, backend activation or therapy operation follows from approval.
Real Redmi visual/CPU/RAM/notification/TalkBack acceptance remains separate.

## Local Result

Expected missing-menu regression was observed before source edits. Corrected
focused native gate passed51 tests; final full Android run completed5682 tests:
5676 passed,6 existing private-data opt-in skips,zero failures/errors. Complete
unit/lint/assemble/Kotlin gate passed in11m07s. Lint retains364warnings/8hints,
zero errors; no warning in the new menu component. Actual native320dp light/
font2-dark header and moving/settled owl rasters were inspected. Debug APK v2
signature verified. These are local synthetic/native results, not phone evidence.
