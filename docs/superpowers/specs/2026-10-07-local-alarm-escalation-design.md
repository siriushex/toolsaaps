# Local Alarm Escalation Design - 2026-10-07

## Status and scope

Proposed design for written human review. Continuing the investigation authorizes
design preparation, not live activation, phone audio tests or therapy changes.
The phone is still unavailable through ADB. The original requested historical
window remains fixed; a later current snapshot does not replace that incident.
Private clinical timelines and database exports stay outside the repository.

Change local delivery, repeat scheduling and acknowledgement only. Preserve
clinical prediction, accepted provenance, sensor/freshness guards, alert
thresholds, target management and all insulin/therapy writers. No backend,
Telegram recipient, remote retry or pump-control change.

This profile is an engineering proposal, not a clinically calibrated warning
policy or a guarantee that a person will wake. Device acceptance is mandatory.

## Observed source constraints

- Glucose episode receipts permit INITIAL and one LOW_NOW_ESCALATION. A
  SUPPRESSED_SNOOZE receipt is terminal even after the global OFF deadline.
- Saved soft/strong repeat intervals do not schedule sound in that coordinator.
- The existing alarm-stream floor applies only to fresh LOW_NOW below4.0.
  Preserve this protection, including its legacy fallback when new delivery
  infrastructure is unavailable.
- Pump link describes technical connectivity, not body attachment or actual
  insulin entry. UNKNOWN and intentional disconnect are not new alarm triggers.
- Navigation acknowledgement is not acknowledgement of an alarm.
- The current local Nightscout service uses dataSync; it is not the new alarm
  lifecycle owner. Android restrictions must be tested, not inferred away.

## Delivery ownership and admission

Introduce one local AlarmEscalationCoordinator with typed source adapters:
GLUCOSE, PUMP_LINK and DELIVERY_DIAGNOSTIC. Source keys identify the current
clinical episode or technical boot/episode generation, never just a screen.

Glucose adapters receive the same accepted alert decision and authority used by
the current notifier. Pump adapters re-evaluate the existing health policy against
current elapsed time and boot. Diagnostic adapters use the existing accepted
diagnostic state and freshness guards. Do not loosen any detection rule to make
a sound test pass.

The coordinator owns one audio/vibration executor. Priority is LOW_NOW,
CRITICAL_5, WARNING_30, DELIVERY_DIAGNOSTIC, PUMP_LINK, SOFT_HIGH_RISK; WATCH
does not claim the executor. Other active sources remain visible. A higher
priority valid source preempts a lower one; equal priority retains its current
owner until the bounded cycle ends, then oldest-due order prevents starvation.

Admission before every notification, vibration, audio start or volume step checks:
feature/runtime armed state, exact global OFF, current source generation,
accepted authority/freshness and the relevant Android capability. Duplicate
CGM evaluations refresh evidence but never restart a cycle or advance its ordinal.

NONE, SAFE_PENDING, stale/invalid clinical authority, source recovery, an old boot
or disabled source cancels that source's local cycle immediately. Do not retain
sound simply because clinical episode resolution has a longer hysteresis.
Missing data is unavailable, not evidence that therapy or pump attachment is safe.

## Opt-in and backward compatibility

Add alarm_escalation_enabled, default false. No migration automatically arms it.
Enable from visible Settings after an explicit human action and capability check.
Global OFF retains its existing meaning for every automatic local/remote source.

When disabled, preserve current initial sound, notification, mute and Telegram
behavior. When enabled and operational, route local sound through the new owner
so the legacy player and escalation player cannot overlap. Keep INITIAL receipt
meaning and its existing post-commit observers.

If new service/storage/capability admission fails, retain the existing initial
urgent-low path, show local escalation unavailable and record the actual cause.
Never label a requested but absent service as armed.

## Proposed audible profiles

Percentages are STREAM_ALARM hardware targets, rounded up to valid integer
steps, not dB or a measured perceived loudness. Never reduce the user's current
stream volume. Retain existing player gain and selected clip duration.

| Source | Cycle profile | Earliest next cycle |
| --- | --- | --- |
| WATCH_60 | Existing silent information only | No escalation |
| WARNING_30 / SOFT_HIGH_RISK | Four soft clips at0/15/30/45s, targets25/50/75/100%; each keeps configured1-5s duration | softAlertRepeatMinutes, existing5-30min bounds |
| PUMP_LINK / DELIVERY_DIAGNOSTIC | Same four bounded soft clips, only while existing source policy still requires attention | softAlertRepeatMinutes |
| CRITICAL_5 | One configured15-30s critical clip; targets25/50/75/100% at0/25/50/75% of its duration | strongLowRepeatMinutes, existing1-10min bounds |
| Fresh LOW_NOW | One configured15-30s critical clip; targets70/85/100/100% at the same relative steps, first floor immediate | strongLowRepeatMinutes |

For CRITICAL_5, preserving current stream volume and existing critical player
gain prevents the new first step from reducing the previous audible level.
For fresh LOW_NOW, do not wait for a gentle phase and do not lower its current
70% stream floor or existing critical gain.

Repeat intervals are measured from cycle start, not the last CGM recalculation.
After the first cycle, subsequent eligible cycles start at the reached maximum,
rather than repeatedly returning to a gentle level. No overdue cycle backlog:
wake/recovery admits at most one current cycle and rebases the next deadline.

Vibration uses existing soft/strong waveforms at each admitted clip start.
No endless repeating waveform. A cycle has at most four starts and ends within
55 seconds including bounded player preparation; strong clips keep their
existing maximum30s length. Enforce an absolute elapsed-time cycle deadline;
late preparation or callbacks never extend it or replay missed soft steps.

Selected/custom clip failure uses the existing bounded built-in fallback.
Failed playback is recorded independently even when notification posting worked.

One admitted attempt uses either app audio or native channel audio, never both.
Post the urgent visual notification without waiting for player preparation; keep
it silent while app audio owns the attempt. On confirmed app-audio failure use
one explicit native fallback update, honoring the existing channel settings,
and skip the remaining volume steps for that attempt. Record both outcomes.

## Volume and route ownership

Use USAGE_ALARM for the opt-in escalation executor only; disabled soft delivery
keeps its current attributes. Do not change call/media volume, force speaker
routing, toggle ringer/DND or silently replace a muted notification channel.

Own an alarm-volume lease for the audio cycle. Before each raise compare current
volume with the last owned value. A differing observed user/system change stops
further automatic raises in that cycle. Record that override.

On cancellation/end, restore the pre-cycle value only if the observed volume
still matches the owned value and no override was recorded. Otherwise leave
the current value untouched. Public platform observations cannot identify every
possible user gesture; do not claim stronger guarantees than this comparison.

A later newly admitted urgent-low signal retains the existing safety floor.
Channel-level blocks stop additional escalation, not the pre-existing initial
urgent-low fallback. Readable DND/route states are logged; unknown remains unknown.
Arming checks and the device test must disclose capability/route limitations.

## OFF, resume and acknowledgement

OFF30/60 stops all current local audio, vibration and scheduled steps immediately.
Its exact deadline and global remote-send suppression stay authoritative.

At expiry or explicit resume, run an alert-only reassessment of current accepted
evidence. Never call the full automation cycle or a therapy writer solely to
resume sound. If evidence is stale, wait for a fresh accepted source signal.

A currently valid continuing risk can start a new RESUME_CURRENT local cycle
even if its original clinical receipt is SUPPRESSED_SNOOZE. Keep the historical
suppression receipt unchanged; do not relabel or replay its old INITIAL delivery.
This change is opt-in and replaces permanent local silence after OFF only.

Add an explicit alarm acknowledgement action in notification and alert detail.
Opening a screen or swiping a notification is not acknowledgement.
Validate source key and generation; a stale action cannot acknowledge a new risk.

Acknowledgement cancels the current cycle and pauses that source for one current
repeat interval (strong1-10min, soft5-30min), without resolving the clinical event
or muting other sources. A higher-severity valid signal invalidates the pause.
After the pause, freshly re-evaluate; unresolved risk remains eligible.
Global OFF is the distinct control for deliberate30/60min silence.

## Foreground runtime and power bounds

Use a dedicated non-exported AlarmMonitoringService, user-started while enabling
the mode from visible UI, with an ongoing operational-status notification.
On Android14+ propose specialUse with its normal permission and an explicit
manifest subtype describing user-enabled local clinical advisory alert monitoring.
Do not pretend that idle monitoring is continuous media playback or extend the
local Nightscout dataSync service to evade its timeout.

specialUse is not an exemption from foreground-start restrictions. Catch denied
starts and expose unavailable; no fake exact alarms, overlays, permission grants
or battery-policy changes. Google Play use-case approval is a separate release
gate if that distribution is pursued.

While armed, use conflated accepted-source events and one nearest-deadline job.
No continuous database polling, full-history reads, AI requests or forecasting
on repeat ticks. Keep at most one player, one vibrator owner and four scheduled
volume/clip steps. Release resources at cycle end and cancel stale callbacks
by generation. No always-held wake lock.

An active admitted audio cycle may acquire one PARTIAL_WAKE_LOCK with a55s
timeout and the normal WAKE_LOCK permission. Release it on completion,
cancellation, OFF, acknowledgement, preemption and failure; never hold it
between cycles, during an acknowledgement pause or while armed but idle.
Foreground status alone does not keep the CPU awake, and a timed wake lock
does not bypass Doze. Record unavailable admission and missed deadlines;
revalidate current authority instead of replaying overdue steps. Doze and
manufacturer power behavior remain real-device acceptance gates. Do not
automatically change battery exemptions or claim locked-screen reliability.

Process recovery rechecks enabled state, service admission, OFF, source identity,
boot and fresh evidence. It does not resume stale playing state or grant itself
permission after force-stop. Reboot/force-stop recovery is a device acceptance
case; inability to restart must be shown when observable, not called reliable.
An independent powered-off-phone monitor is outside this local feature.

Persist wall-clock OFF/ack deadlines consistently with existing settings, but
schedule waits against elapsed time. Clock-change and boot events invalidate
old waits and reassess current authority/deadlines; no overdue backlog or reuse
of a previous boot's technical source. Arithmetic overflow fails admission.

## Persistence and observability

Keep clinical alert_events and the unique episode/kind initial receipts intact.
Do not overload those receipts with numbered repeats or resend Telegram/AI events.

Propose an additive Room31->32 migration with alert_local_state and
alert_local_cycles. State is keyed by typed source key; cycles have a unique
(sourceKey, generation, ordinal) and bounded four-step result payload.
Persist generation, latest validated evidence identity, next due, acknowledgement
deadline, progression and claim/result before side effects. Pump source state
does not require inventing a glucose clinical episode.

Clinical links are optional verified identifiers. Bound a saved source envelope
to4KiB and cycle outcome to2KiB; reject corrupt, future/non-finite or unknown
versions. CAS/transactions and the existing global-mute coordination order
prevent concurrent workers from claiming the same cycle.

On uncertain restart, require fresh authority and use one current generation;
never loop an unconfirmed old audio claim. A delivered channel result is not
human acknowledgement. Existing initial urgent notification remains independent
of failure to decode new escalation storage.

Record separate posted/channel_blocked, audio_started/audio_failed,
vibration_requested/vibration_failed, focus/service capability, readable route
and volume, local cycle/step, mute and acknowledgement. Do not report API-start
success as heard. History must distinguish clinical detection from local delivery.

Use indexed current-source/next-due lookups. Match existing30-day alert-history
retention without shortening medical history. Existing housekeeping deletes
expired local-cycle rows in bounded batches; source ordinals remain monotonic
so retention cannot reopen old claims.

## Planned verification and release gates

1. Pure policy regressions: profile rounding, urgent floor, exact OFF boundaries,
   fresh resume of suppressed risk, stale/no-data, severity preemption,
   acknowledgement pause/invalidation, duplicate CGM and no backlog.
2. Coordinator/storage tests: claim crash, concurrency, mute/ack during queued
   Main-thread start or volume step, corrupt/newer version, changed boot,
   cancellation, user-volume override, indexed bounded current-state access,
   absolute cycle timeout and wake-lock release on every terminal path.
3. Migration on synthetic31 data and a fresh disposable backup copy: integrity,
   foreign keys, old clinical counts and protected settings retained. Never use
   the live database or restore an old medical snapshot for this test.
4. UI/receiver tests: current/stale acknowledgement actions, global OFF, permission
   denial, degraded status and operational notification. No remote acknowledgement.
5. Verify no therapy/forecast/Telegram writer is called by local-repeat,
   acknowledgement or OFF-expiry work. Legacy default-off behavior remains tested.
6. Full Android unit suite, compile, lint and debug build, plus exact-source CI.
   Do not infer these results from earlier existing-rule tests.
7. Fresh read-only phone audit first, including the original incident window,
   OS/channels/DND/volume/route, app/service/boot and battery policy.
8. Separately authorized same-signature APK update after fresh coherent backup.
   Controlled nonclinical sound/vibration test only after separate consent;
   locked-screen, Doze/idle, custom-clip failure, foreground denial, restart and volume
   restoration need real acceptance. No fake BG/pump failure or therapy cycle.
9. Matched idle/active CPU/PSS measurements before/after. No resource savings or
   successful real-night delivery claimed without measured evidence.

## Explicit exclusions

No new pump-worn sensor, automatic reminder for intentional disconnect, new
clinical threshold, dosing/target change, channel/DND bypass, caregiver enrollment
or delivery guarantee. A connected pump left off-body is still not directly
detectable by the technical link monitor. Detection extensions require their
own evidence and review, not silent changes inside this sound feature.

## Primary Android references

- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use):
  specialUse needs a declared legitimate use case; Play review is separate.
- [Foreground start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start):
  user-initiated admission does not authorize arbitrary later background starts.
- [Foreground timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout):
  Android15+ restricts dataSync background duration.
- [Audio focus](https://developer.android.com/media/optimize/audio-focus):
  Android15+ target35+ requires top Activity or an active foreground service.
- [Notification channels](https://developer.android.com/develop/ui/compose/notifications/channels):
  channel auditory settings remain under user control.
- [Timed wake locks](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set):
  explicit release and a timeout bound CPU-awake ownership during active work.
- [Doze restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby):
  normal wake locks and timers are not a guarantee of delivery during deep idle.
