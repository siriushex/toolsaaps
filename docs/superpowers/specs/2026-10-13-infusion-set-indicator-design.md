# Infusion Set Indicator: First Stage

Status: the user approved this first-stage concept and specified a three-day
interval. This written specification awaits review before implementation.
It is separate from the pending Lenta Estimate and dashboard IOB presentation.

## Approved Scope

Add one compact primary-screen indicator with independent replacement age and
delivery-attention information. Reminder becomes due after72 elapsed hours from
a verified recorded change: start of the fourth24-hour period, not96 hours and
not the fourth calendar midnight. The user-specified interval is not a verified
manufacturer instruction; no automatic extension or learned wear limit.

Reuse existing accepted delivery diagnostic episodes without lowering their
thresholds or adding a new glucose/insulin detector. Insulin usage and ISF may be
existing explainable context only; no new historical percentages or failure
score in this stage. Later new comparison logic needs separate validation.

No actual COB, insulin/ISF computation, dose, target, Eating Soon priority,
hypo guard, AAPS protocol changes or phone activation. No new audio escalation,
automatic volume adjustment or vibration test. Building/publishing the feature
does not prove notification delivery or authorize installation.

## Presentation

Use the existing infusion-event image and a48dp minimum target in the existing
chart-header status area, alongside but distinct from Bluetooth/PumpLink. Do not
add a second large card, duplicate overflow or permanent explanatory paragraph.
Reflow the header at narrow sizes/large font without obscuring CGM/sync age,
chart details or expand controls.

Represent two separate fields, not one false healthy/unhealthy conclusion:

- Recorded age below72h: neutral icon and compact age; only a timing fact.
- Verified age at/above72h: amber clock/attention, replacement due.
- Current accepted delivery suspicion: attention plus Check delivery, not a
  diagnosis of a blocked cannula. May occur before72h.
- Independently established explicit device fault or accepted urgent high/delivery
  state: retain its existing urgency and help, not a new severity from wear age.
- Missing/invalid/conflicting source: unknown indication; no green healthy claim.

Color is not the only distinction. Tapping opens compact Age and Delivery details.
Age shows recorded occurrence, source, interval, due time and synchronization
quality. Delivery shows the accepted reason/time and existing evidence limitations.
If history is retained but current confirmation unavailable, identify it as a
recorded episode, not a newly detected fault. No invented confidence percent.

Existing help/history routes remain available. Missing dates lead to help for
recording/correcting the actual Site Change in AAPS, never to a silent reset.
Show the approved72h interval in Details; a new interval-editing screen is outside
this first stage. Opening/cancelling details sends nothing and writes nothing.
No new Record replacement shortcut, user-entry form or event writer is added;
existing independently confirmed records retain their original provenance.

## Replacement Evidence

Prefer canonical valid Site Change/Cannula Change/infusion_set_change events from
already authorized inputs. Resolve their original date/mills/createdAt with the
existing structured time helpers, but no received-at-now fallback. Reject future,
invalid, contradictory, deleted or duplicate/tombstoned evidence. Keep occurrence
time separate from receipt and source-update time. Repeated/imported old markers
do not renew the wear period. A legacy row timestamp alone is not sufficient when
the original source date is absent or known to be a receipt-time fallback.

Use a bounded query of relevant change types and explicit overflow/ambiguity
handling, not a full-history scan each minute. Corrected/deleted canonical records
invalidate the old generation; chronological source identity and original dates
determine the next state. A copied/echoed Copilot metric is not independent proof.

Explicit day/hour age fields may be shown as source-qualified context after unit
validation. Bare cage without a known unit contract is unavailable for this
indicator: Nightscout CAGE uses hours, while the existing mapper accepts a bare
alias into cage_days. Do not rewrite old metrics or change current clinical use
as a side effect of this UI stage. Add specific unit regressions for the new
reader, and track broader ingestion repair as a separate reviewed change.

Age-only packets cannot silently create a physical replacement event or reset a
scheduled reminder. First-stage scheduling requires a verified original occurrence
or explicitly confirmed user record. Lack of one is shown, not replaced with age0.
Do not reuse IsfCrContextModel's neutral0-hour fallback as recorded age.

Reservoir refill, prime/fill, insulin change, battery or sensor change do not
reset cannula age. Patch-pump replacement requires its explicit canonical marker,
not deduction from reservoir level. A known marker with disconnected sync may
remain visible as age of the last record, with uncertainty about newer changes.

## Pure Policy And Persistence

Use a small pure policy taking explicit now, verified change evidence and interval.
Return typed unknown/not-due/due, generation identity, occurrence/due time and
reason, with safe arithmetic. No system clock or database/action dependency inside
the policy.72h is an elapsed duration, unaffected by timezone/DST/calendar midnight.

A focused repository reads evidence and persists only reminder generation,
occurrence/due time and typed attempt/receipt state in a small independent
DataStore record. No Room schema migration or rewrite of clinical history. It
cannot write dose/target, infer a replacement from dismissing a notification or
refresh an old event's original date. Invalid stored state remains explicit.

State transitions serialize. Commit the new source generation before scheduling;
worker execution independently rereads canonical generation and deadline. Thus
an old worker cannot post for a replaced/corrected set even if cancellation races.
No failure may repeat a clinical command or change arming. A user replacement
resets only timing, not an unresolved delivery/high-glucose episode.

## Existing Delivery Evidence

Consume existing policy-authorized diagnostics through a narrow read-only adapter.
Source identity, accepted timestamps, active/resolved state and freshness must
agree. An arbitrary OPEN database row, label, UI history or posted notification
does not establish a fresh fault. If the currently saved diagnostic cannot be
bound to current accepted evidence, display retained history as history only.

Do not loosen DeliveryDiagnosticObservationMapper checks for trusted CGM,
calibration/model generation, current insulin activity, source/coverage/confidence,
accepted forecasts or low-risk inhibition. Preserve foodConfounded explanation
and existing recovery behavior. PumpLink remains technical connectivity only.

The first stage does not count a context-adjusted ISF fall as independent evidence:
Copilot already applies set_factor and AAPS DynamicISF depends on glucose/TDD.
If existing usage/ISF context is unavailable or stale, omit it or show unavailable,
not zero, an invented normal state or a fabricated historical comparison.

## Scheduled Reminder

Use one uniquely named delayed WorkManager request per current reminder generation,
with cancellation/replacement on a valid change. Do not reuse clinical sync work:
the age reminder must not require internet, fresh CGM or therapy arming. The worker
only checks canonical evidence and posts an informational maintenance reminder.
No clinical run, forecast refresh, network or persistent keepalive is needed.

WorkManager delay is not an exact alarm guarantee. Recompute UI deadline on resume
and existing relevant source updates; restore one current reminder after restart,
without replaying old generations. Do not promise minute-exact delivery in Doze
or add exact-alarm permissions in this stage.

Persist notification claim/receipt separately from due state. Permission/channel
blocks must remain visible in help. A known failed attempt may be retried only
under a reviewed bounded state transition after capability restoration; ambiguous
posting is not blindly retried. No periodic nagging or automatic loudness ramp.
The due badge remains until a verified replacement regardless of notification
dismissal. Existing global OFF/mute intent is respected; mute does not extend72h.

Maintenance notification uses a separate low-urgency identity and does not compete
with the existing glucose/delivery player. Existing urgent source priority and
hypo protection stay unchanged. Any future sound/escalation hookup is separately
reviewed and verified on-device, not authorized by this spec.

## Resource And Release Gates

No repeated21-day scans, new real-time history model, network polling or continuous
service. Reuse source invalidation after committed event updates and a bounded
startup/resume reconciliation. Cached age state is source/version scoped; own
telemetry echo cannot renew it. No measured CPU/RAM benefit is claimed.

Test-first unit and native regressions must cover missing/invalid/original-vs-receipt dates,
71:59:59 and72:00:00, future/overflow, DST/timezone, duplicates/corrections/deletions,
unit ambiguity, reservoir/sensor/prime non-resets, unknown-age fallback and echo.
Integration checks cover persistent restart, generation/cancel races, stale worker,
blocked notifications, known failure versus ambiguous outcome, OFF intent and
replacement not resolving an independent delivery episode.

Verify no-CGM/offline/disarmed timing behavior, retained-versus-current diagnostic
presentation and zero therapy/source writes on passive UI actions. Inspect native
320x640/taller, light/dark, landscape and large font for header/chart/control bounds.
Synthetic checks do not prove physical pump delivery, clinical accuracy or audibility.

Full Android unit/lint/Kotlin/APK, scoped source/privacy review and exact-SHA CI
follow implementation. Any phone update needs separate current authorization and
backup/device acceptance, with no test therapy or sound commands inferred.

## Verified References

- TelemetryMetricMapper.kt:336,524; SyncRepository.kt:1830; LocalNightscoutServer.kt:1754.
- IsfCrContextModel.kt:49,274; DeliveryDiagnosticPolicy.kt; DeliveryDiagnosticObservationMapper.kt.
- DeliveryDiagnosticRepository.kt; DeliveryDiagnosticNotifier.kt; LocalAlarmModels.kt.
- ClarityGlucoseChart.kt:88; OverviewScreen.kt:787; EventIconResources.kt; WorkScheduler.kt.
- [AAPS age presentation](https://wiki.aaps.app/en/latest/DailyLifeWithAaps/AapsScreens.html).
- [Nightscout CAGE units](https://nightscout.github.io/nightscout/setup_variables/).
- [AAPS DynamicISF](https://wiki.aaps.app/en/latest/DailyLifeWithAaps/DynamicISF.html).
- [Android work timing](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
