# First-Start Setup

## Delivered Scope

The five-step native checklist covers equipment, data entry/connections, optional
profile, notification settings and review. Glucose equipment and insulin source
are independent: manual/sensor combined with none/injections/pump/AAPS. These are
checklist choices, not clinical authority. No selected device
is presented as connected and completion is not a readiness certificate.

On a confirmed fresh Android installation the checklist opens automatically.
Unknown package metadata and in-place updates keep the existing dashboard. Any
user can reopen setup from Settings. Step/choices persist atomically in the
existing preferences store; interrupted setup resumes, explicit Later/Complete
does not automatically reopen. A late write compares the complete prior setup
state before replacing it. Clinical settings and credentials are not rewritten.

Each editor opens the existing authoritative screen. Back and the return-to-setup
action retain progress. The footer remains outside the scrollable content;
small screens and large fonts may scroll rather than clip inputs. English and
Russian resources are provided. Permission requests are explicit and no sound
preview, auto-connect scan, AI upload or therapy command runs on tab/step visits.

## Startup Boundary

New setup does not require therapy arming to finish. Legacy foreground arming
migration remains unchanged. Legacy undecided users retain the existing two-step
confirmation; a recorded refusal is no longer re-prompted on every foreground
entry. Already-armed runtime startup is not blocked by an unfinished checklist.
A separate explicit Background observation switch on Data permits configured
local sources and silent local recalculation without therapy permission. It is
install-bound, defaults off, and does not enable independent alarms or daily AI.
The review distinguishes permission from actual reception. Existing armed users
see the actual shared runtime and its Safety link, not a misleading off switch.
AAPS users may explicitly review the same two-step confirmation from the final
step. Choosing manual/no-pump does not revoke previously authorized therapy;
the final step explicitly shows that actual state and links to Safety.
Automatic opening yields to a pending/active critical alert and other navigation;
a late saved close cannot pop a different screen the user has since opened.

## Actual Capability Matrix

| Configuration | Available now | Not established by setup |
| --- | --- | --- |
| Manual / no sensor | Stored blood checks, local meal/injection diary, context notes, optional profile | Continuous alerts, glucose trend or continuous forecast |
| Sensor only | Configured local source ingestion and explicit independent silent background recalculation | Remote polling, independent alarms, device-validated forecasts |
| Injections or pump without AAPS | Blood checks/context, local diary and explicit local observation | Validated diary-derived IOB/COB, independent alarms |
| AAPS | Existing source configuration and guarded treatment workflows | Connectivity, successful commands, prediction/alert readiness |

Blood measurements remain BloodGlucoseCheck records; they are not inserted as
CGM points. Missing IOB/COB is not filled with zero. Background observation and
therapy have different permissions; treatment transports retain every arming
guard. Local recalculation can still reject incomplete data. An enabled switch
does not certify connection, forecast validity or physical alert delivery.

## Remaining Independent-Use Work

1. Local lifecycle authorization is separated from treatment writers. Validate
   real source/foreground/boot/power-save behavior on a phone before advertising
   autonomous monitoring. Remote polling and independent alert publication remain
   separate work; no configured sources or user permissions are auto-enabled.
2. Local structured meal/injection diary is implemented: drawer or setup Data
   step, explicit gram/unit fields and time, correction/history, deleted records
   and restore. It is independent of AAPS submission and therapy authorization.
   These entries are not yet connected to forecasts, IOB/COB or AI history.
3. Provide sparse/manual-data presentation and quality-aware advisory analytics;
   never infer continuous coverage or delivery confirmation from manual entries.
4. Validate each actual hardware/source combination and background behavior on
   devices before advertising autonomous no-AAPS monitoring or forecasts.

Independent alarms, remote polling and diary analytical consumers remain open.

## Diary Acceptance

With therapy unarmed and no sources, open Diary from the drawer or setup. Record
food and an already-taken injection, then reopen, correct, inspect revisions,
delete and restore. Check comma decimal input, long names/notes, large text and
process recreation with an unfinished draft. Nothing is sent to AAPS. Date/time
uses the stored zone and actual event time, not the time of a later correction.
The 1000-entry display bound is disclosed; older entries are retained, not erased.

## Verification

Run SetupProgressTest, SetupScreenTest and TherapyActionBootstrapArchitectureTest,
then the complete Android unit/lint/Kotlin/debug-build suite. Synthetic native
rasters cover Russian 320x640 and enlarged text. Actual-phone onboarding and
permission/settings roundtrips remain device acceptance, not inferred from CI.
