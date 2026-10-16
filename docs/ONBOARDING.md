# First-Start Setup

## Delivered Scope

The five-step native checklist covers equipment, data entry/connections, optional
profile, notification settings and review. Glucose equipment and insulin source
are independent: manual/sensor combined with none/injections/pump/AAPS. These are
checklist choices, not operating modes or clinical authority. No selected device
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
AAPS users may explicitly review the same two-step confirmation from the final
step. Choosing manual/no-pump does not revoke previously authorized therapy;
the final step explicitly shows that actual state and links to Safety.
Automatic opening yields to a pending/active critical alert and other navigation;
a late saved close cannot pop a different screen the user has since opened.

## Actual Capability Matrix

| Configuration | Available now | Not established by setup |
| --- | --- | --- |
| Manual / no sensor | Stored blood checks, local meal/injection diary, context notes, optional profile | Continuous alerts, glucose trend or continuous forecast |
| Sensor only | Existing source configuration and displayed imported readings | Independent background analysis without the current arming gate |
| Injections or pump without AAPS | Blood checks/context, local meal/injection diary and declared equipment | Validated IOB/COB or independent background analysis |
| AAPS | Existing source configuration and guarded treatment workflows | Connectivity, successful commands, prediction/alert readiness |

Blood measurements remain BloodGlucoseCheck records; they are not inserted as
CGM points. Missing IOB/COB is not filled with zero. The current foreground
service/workers and several ingestion/forecast paths still use therapy arming
as a runtime gate. This checklist deliberately does not bypass those gates.

## Remaining Independent-Use Work

1. Separate observation/ingestion lifecycle authorization from treatment writer
   authorization, auditing application, boot, broadcast, scheduler, local server
   and automation entry points. Preserve every transport-side treatment guard.
2. Local structured meal/injection diary is implemented: drawer or setup Data
   step, explicit gram/unit fields and time, correction/history, deleted records
   and restore. It is independent of AAPS submission and therapy authorization.
   These entries are not yet connected to forecasts, IOB/COB or AI history.
3. Provide sparse/manual-data presentation and quality-aware advisory analytics;
   never infer continuous coverage or delivery confirmation from manual entries.
4. Validate each actual hardware/source combination and background behavior on
   devices before advertising autonomous no-AAPS monitoring or forecasts.

Independent runtime and analytical consumers remain open capabilities.

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
