# Source-Checked Daily Review Boundaries

Trusted internal preparation and result checking now consume independent frozen
source verification. No caller-supplied source hash or old check object authorizes
either operation. This is not a queue, provider, scheduler or publication gate.

## Preparation

HistoryStore.prepare_checked_daily_glucose_review takes owner/device, logical
report date/zone, trusted current time and ru-RU/en-US. It selects the latest
visible authenticated archive revision and replays the latest retained source.
Only MATCHED source and complete deterministic facts can enter preparation.

Selection, source authentication/replay and canonical work construction share one
protected read transaction and its existing row/SQL/five-second wall budgets.
Another writer cannot change the source between verification and construction.
The final deadline includes preparation, not just the database scan.

SourceCheckedReviewWork contains the existing immutable DailyReviewWork and a
separate actual point-in-time DailySourceCheck. The original archive is unchanged.
No provider, model, tools, route or action settings are accepted or chosen.

## Result Boundary

HistoryStore.validate_checked_daily_glucose_review takes owner/device, original
DailyReviewWork, bounded response bytes and trusted current time. It does not take
the earlier check object or a claimed current hash. It validates canonical work,
owner/device, expiry and bounded response syntax before expensive storage access.

Then it independently selects the current latest archive and rechecks source.
A newer report revision supersedes old work, even if its original glucose source
would still reproduce. This includes changed intended analytical inputs. Missing,
changed, expired, unavailable or corrupt evidence never returns a checked result.

The checker reconstructs the exact canonical work from the authenticated report
under the same transaction and compares the complete work object. Rehashing an
altered canonical packet cannot substitute invented metrics or candidate inputs
for the actual archive. Existing strict schema/reference/priority checks then
validate the reply, with the final deadline covering result construction too.

SourceCheckedDailyReview contains GroundedDailyReview and the new source check.
It remains REFERENCES_ONLY / NOT_ADMITTED / UNREVIEWED_AI_INTERPRETATION. The
unchanged inner input/result sourceFreshness field describes the legacy pure
contract; the separate source_check is the actual retained-history observation.
It is not a medical-quality, provider success, stop/IO or publication receipt.
The source check's NOT_REQUESTED means the source checker launched no model.

## Errors And Non-Mutation

- Missing selected archive: internal HistoryError404 daily_review_report_unavailable.
- Changed/unavailable source or superseded report: distinct internal409 codes.
- Rehashed work differing from archive:409 daily_review_work_archive_mismatch.
- Input projection unavailable/capacity:422 daily_review_input_unavailable.
- Syntactically valid but reference-invalid reply:422 invalid_daily_review_result.
- Invalid time/language/work/foreign binding, expired work and malformed/oversized
  reply: ValueError before storage. These are not storage-corruption assertions.
- Actual storage/integrity/SQL/deadline failures retain existing unavailable errors.

The status numbers classify internal errors, not new public HTTP routes. Bounded
contract failures inside the protected read are translated explicitly; they must
not be misreported as storage corruption by the shared lock's error boundary.
Reads do not migrate, reserve jobs, persist results or change report metadata.

## Future Worker Consumption

The existing generic queue admits only its dedicated CHAT/MEAL_PHOTO contracts.
These review helpers are not registered there and cannot be routed through CHAT.
A future daily worker must use the checked preparation immediately before actual
dispatch, not treat earlier queued preparation as continuing permission. It must
use checked result evaluation after response, then coordinate a fresh check with
any durable publication commit. Releasing this read lock cannot promise that
history will not change next; a returned object is not an enduring certificate.

Still separate: daily provider/policy/contained stop and shared-profile ownership,
encrypted durable work/results, logical-day reservation, uncertain-outcome handling,
interactive-first opt-in schedule, clinical advisory-quality admission and protected
device/admin read surfaces. No live model, server, APK or therapy change is made.

Synthetic tests exercise accepted preparation/reply, old-hash and cached-proof
rejection, late/revised/deleted/new-observation source changes, unrelated uploads,
supersession, retention/expiry, forged canonical work, source isolation, corruption,
error classification, non-mutation, concurrency and complete-operation deadlines.
