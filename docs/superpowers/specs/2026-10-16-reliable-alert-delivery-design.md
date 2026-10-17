# Reliable Local Alert Delivery

Status: explicitly approved by the owner's 2026-10-16 answer, including configured
critical repeats, scoped ACK, noncritical backoff, global OFF and genuine visible
AlarmClock wake. Staged source implementation; not implemented or armed as a whole.
Scope: delivery, confirmation, repeat timing, operational readiness and audit.
Keep accepted clinical detection, thresholds, freshness/sensor guards, prediction,
therapy/targets and existing global OFF semantics unchanged. No live phone,
audio, volume, battery/DND, installation, server or therapy action is authorized.

## Source Audit

At source d153812c5622269795f82eeaac28197b0273aeaf:

- AppContainer constructs GlucoseAlertNotifier with EpisodeAlertDeliveryStateMachine.
  LocalAlarmCoordinator has no production constructor; guarded cycle execution,
  volume leases and durable repeat state exist but are not live integration.
- Episode delivery kinds are INITIAL, LOW_NOW_ESCALATION and SUPPRESSED_SNOOZE,
  not recurring local deliveries. SUPPRESSED_SNOOZE is terminal for that episode
  in the legacy path, including after its original OFF deadline.
- Repeat settings default to strong2/soft5 minutes, with1-10/5-30 bounds. They
  are consumed by the inactive local cycle path, not an independent live timer.
- GlucoseAlertActionReceiver handles only global OFF30/60 actions. There is no
  separately scoped visible alarm acknowledgement action in that receiver.
- Manifest declares dataSync foreground service, not the dedicated alarm owner.
  Notification permission/global enable checks do not by themselves prove that
  the selected channel is visible/audible or that a person heard anything.
- GlucoseAlertNotifier reports a successful notify call without checking the
  selected channel's IMPORTANCE_NONE. This is a source-level reporting risk,
  not evidence that a particular historical phone alert was blocked.
- Accepted clinical fan-out calls the therapy stage before publishAlerts without
  a local exception boundary. If an earlier stage throws, that helper cannot
  publish the alert. This is a failure-isolation risk, not proof of a past miss.
- Pump-link connectivity cannot establish that the infusion set is worn or that
  insulin reaches the body. Do not claim reliable off-body detection.
- ADB inventory is empty in this audit; no phone/OEM/locked-screen result exists.

## Alternatives

1. Recommended: integrate the existing episode-aware local runtime with separate
   critical delivery, noncritical backoff, explicit ACK and readiness checks.
   More integration/device verification, but solves lifecycle and noise together.
2. Increase repeats in the legacy notifier. Smaller change, but retains weak
   background ownership and creates fatigue without reliable confirmation.
3. Add remote duplicates first. Helps only with an independently reachable
   recipient/channel; depends on network and consent, does not repair local sound.
   Keep existing optional Telegram behavior; no new recipient or upload here.

## Delivery Policy

One accepted source episode/generation, one visible notification per source and
one audio/vibration owner. Highest currently eligible critical risk preempts
lower-priority audio after exact cancellation/cleanup. Coexisting sources remain
visible; duplicate CGM/model evaluations never reset repeat deadlines or create
additional signals. No AI request is needed to admit a local alert.

Critical LOW_NOW / CRITICAL_5:

- Existing accepted current risk only. Start immediately; never wait for a soft
  confirmation or a noncritical cooldown once the current engine admits it.
- Preserve existing LOW_NOW volume floor/gain; no gentle ramp below its existing
  protection. Other critical clips use a bounded ramp and strong vibration.
- Repeat at the configured strong interval (current default2 minutes), without
  restarting from quiet after the first completed ramp. No overdue burst/backlog.
- Do not silence a continuing accepted critical risk just because its forecast
  looks better, notification was swiped or a details screen was opened.

WARNING_30 / SOFT_HIGH_RISK / accepted technical attention:

- One short initial sound, not four separate escalating soft clips. Technical
  unknown/intentional disconnect is not promoted into a new alarm condition.
- While the same risk level persists, reminder spacing grows from the configured
  soft interval: base,2x,4x, capped at30 minutes (default5/10/20/30). A changed
  evidence timestamp alone does not reset this progression.
- WATCH and informational entries remain silent. Summaries and recovery updates
  update the existing notification without an extra recovery sound.
- All noncritical sources share a sound cooldown of the configured soft interval.
  Coalesce concurrent eligible reminders into one short clip and retain each
  source visibly. Suppression is neither acknowledgement nor proof of hearing.
  Critical admission bypasses this noncritical cooldown immediately.
- New genuine episode/direction or higher accepted severity resets appropriate
  progression; noncritical sounds still obey the shared cooldown. No decrease in
  clinical sensitivity or postponement of critical admission.

These are engineering delivery defaults, not clinically calibrated treatment or
measurement intervals. No numerical insulin, meal-timing or target advice is added.

## Confirmation Versus OFF

Add an explicit `Seen` / Russian `Vizhu` action, separate from global OFF30/60.
It stops current local audio/vibration immediately and pauses only the exact
source/generation for its configured repeat interval. It does not resolve the
clinical event, acknowledge another source or suppress a higher accepted severity.
Stale notification actions cannot acknowledge a new generation.

Opening a screen, swiping or posting a notification is not ACK. Existing global
OFF remains a deliberate complete automatic-alert pause until its exact deadline.
Show that state prominently with its remaining time. Do not silently override or
extend it. On expiry/resume re-evaluate fresh accepted evidence, not replay an
old initial receipt or run therapy/forecast solely to resume local delivery.

## Platform Owner and Wake Delivery

Use a dedicated user-enabled non-exported foreground alarm service with an honest
operational notification and appropriate declared specialUse purpose, not an
extended dataSync job or invented playback. Enabling is a visible user action,
default remains off, existing urgent initial fallback stays independently usable.
Catch refused starts and expose unavailable; requested service is not armed.

Critical repeat scheduling must not depend solely on an in-process delay or
WorkManager. Preferred wake path is a genuine user-visible AlarmManager alarm for
an already active accepted critical episode, with explicit user-granted exact
alarm access where required. Android may show that next critical signal as an
alarm-clock entry. Never disguise forecasting, scans or idle polling as alarms.
One pending deadline only. Recovery/replacement cancels the old identity. ACK/OFF
cancel its delivery deadline and replace it with one pause-expiry reassessment
deadline only while the accepted critical source remains current. That wake never
overrides the pause or proves that the old risk still exists: recheck the exact
source/generation, pause deadline and freshness before admitting any sound. No
sound from stale evidence; expose unavailable source data instead. Delayed delivery
admits at most one current cycle, not every missed interval.
The exact alarm/service composition requires platform tests before activation.

Use a bounded partial wake lock only during admitted delivery/cleanup, not an
always-held idle lock. The clock/boot/source guards cancel old waits. Denied exact
access, Doze or an OEM stop cannot be hidden behind a green readiness badge.
No automatic exemption/DND/volume-policy grant or fullscreen privilege is added.

Android limits and rationale, verified2026-10-16:

- Doze defers ordinary alarms/jobs/network and ignores ordinary wake locks;
  allow-while-idle alarms are rate-limited. A foreground service alone is not a
  short-repeat timing guarantee. Actual alarm-clock delivery is a distinct API.
  [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby).
- specialUse needs the declared permission/subtype and separately reviewed Play
  use case; it is not a blanket background-start exemption.
  [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types).
- Users control existing channel behavior; inspect channels and link to settings,
  never recreate channels to bypass choices.
  [Channels](https://developer.android.com/develop/ui/compose/notifications/channels).
- Denied Android13+ notification permission can hide foreground-service notices
  from the drawer even while they remain in Task Manager.
  [Permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Readiness and Truthful Audit

Show distinct operational states: running, deliberately paused, limited/blocked,
and no current source data. Check permission, actual selected channels, service
state, audio/vibrator availability, exact-alarm access and readable power/DND/
route state. Unknown is not healthy. Link a problem to its actual repair screen.
Do not label stale CGM or an inactive service as all-clear. No guarantee that
Android will deliver after user force-stop, shutdown, dead battery or hardware loss.

Record detection, claim, posted/channel-blocked, audio start/error/fallback,
vibration request/error, user volume override, scheduled/missed repeat, ACK/OFF
and cancellation independently. API success is not human hearing/response.
No duplicate clinical INITIAL, Telegram or AI submissions on local repeat ticks.
No raw private source or logs in public code/reports.

## Implementation and Acceptance Sequence

1. Approved policy/backoff and exact ACK generation rules in pure deterministic
   tests; duplicate/new evidence, risk escalation, recovery, clocks and mute expiry.
2. Complete accepted glucose/diagnostic/pump source adapters and OFF-intent
   revocation; use existing journal/single-player/volume cleanup ownership.
   Verify that a fresh currently accepted urgent low is not held behind a failed
   target writer, optional forecast/model work or AI availability. Preserve source
   provenance/freshness/sensor guards, not a second unguarded clinical engine.
3. Foreground owner, channel readiness, bounded vibration/wake/audio and genuine
   current-critical wake scheduling. Keep legacy fallback without double playback.
4. Enable/status/ACK repair UX, blocked/degraded diagnostics and explicit test
   control. No hidden automatic opt-in or hardware test.
5. Full unit/lint/compile/build, interruption/migration tests if schema changes,
   stale intents, process kill/restart, source-absence, independent writer failure,
   shared noncritical cooldown and ACK/OFF expiry wake failure scenarios.
6. Separately authorized same-signature update and harmless synthetic sound test
   on the intended phone. Check locked screen, unplugged idle/Doze, user channel
   blocks, DND/route, custom clip failure, reboot and cleanup/volume restoration.
   No fake clinical/pump events, therapy commands, AAPS stop or disabled Copilot.
7. Observe repeat counts and delivery outcomes before calling the mode reliable
   for that phone. No100-percent promise or sole medical-monitor replacement.

Written design review preceded implementation. AlarmClock access/visibility,
noncritical backoff and explicit ACK are material changes from the inactive Oct7
proposal. Publishing this document is not permission to enable or sound alarms.

The first bounded implementation stage hardens accepted initial delivery:
selected-channel visibility, independent per-channel failure reporting and
publication before potentially slow/failing therapy-stage work. It does not activate the
coordinator, alter OFF behavior or claim that repeats/ACK/device delivery work.
Subsequent owner/source/UI integration remains required before those claims.

## Audit Evidence

2026-10-16 baseline diagnostic run: 144 existing tests passed across
GlucoseAlertEngineTest (27), EpisodeAlertDeliveryStateMachineTest (48),
GlucoseAlertConfirmedDeliveryTest (8), GlucoseAlertAudioVolumePolicyTest (2),
LocalAlarmPolicyTest (42) and LocalAlarmCoordinatorTest (17). No failures/errors/
skips; Gradle BUILD SUCCESSFUL in17s. These tests do not establish production
integration, physical delivery or personal acknowledgement. Private task artifact
directory: reliable-alert-delivery-20261016.VRKzpU; no phone data was collected.
