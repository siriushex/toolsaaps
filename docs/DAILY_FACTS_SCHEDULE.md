# Opt-In Daily Facts Preparation

Source-only continuation of the approved daily review pipeline. The owner later
specified05:00 instead of the original proposed08:00. This stage prepares
deterministic facts only. It installs no timer, service, HTTP route, device
setting, model request or clinical publication.

## Trusted Contract

The existing HistoryStore now exposes three internal operations:

- `set_daily_facts_schedule(owner, device, enabled, zone_id, now_ms, expected_generation)`:
  explicit actual-boolean opt-in, a valid IANA reporting zone and generation CAS.
  Generation0 means absent. Stale concurrent edits fail409; an identical edit
  with the current generation preserves the original setting/date.
- `get_daily_facts_schedule(owner, device, now_ms)`: read only; absent means
  disabled. Reading missing/old stores does not create or migrate them.
- `produce_scheduled_daily_facts(owner, device, ranges, now_ms)`: one bounded
  synchronous transaction, not an event loop or admission authority.

These methods assume trusted identity just like the existing archive methods.
They are not public endpoints and do not validate an active enrollment by
themselves. Future host composition must independently verify current owner,
device, subscription and consent before calling them. A facts opt-in cannot
admit a model, enable daily_reads or enroll a recurring inference job.

## Storage And Time

Explicit settings writes add schema6 and one singleton encrypted row. Its
separate AES-GCM purpose binds owner/device, generation and update time. The
additive migrations from versions1-5 and the setting share one commit; failed
quota/deadline checks cannot leave schema6 without a setting. A newly created
empty base history database can remain after an unsuccessful first edit, but
cannot count as consent. Setting ciphertext counts against existing quotas.

Settings hold no report/patient text. They survive ordinary365-day medical
history purge until explicitly changed; this is not an extension of any report
or source lifetime. Reports still expire at original civil-day end+365days.
Unknown/corrupt/foreign settings fail closed, not an enabled default.

The producer uses the saved reporting zone, not the host date. Before local05:00
it returns NOT_DUE; after05:00 it considers only the previous completed local
civil day. A restart later that day catches up that single day, never all missed
days. Before05:00 it does not replay an older day whose inference dispatch
window already closed. A skipped/unrepresentable civil day is refused, without
silently substituting UTC. DST uses actual civil bounds; days can have23/25hours.
Repeated local clock hours share the same report key. Future setting times and
archive/source regression fail rather than fabricate a current success.

## Producer Results

| State | Meaning |
| --- | --- |
| DISABLED | No opt-in or explicitly disabled; no clinical archive write |
| NOT_DUE | Enabled but local time is before05:00 |
| NO_DATA | No current-day glucose and no previous report; no normal report is invented |
| FACTS_READY | A bounded, complete facts revision was committed; not an AI interpretation |
| UNCHANGED | Retained source and analytical range still match; original report/date retained |

Sparse, suspect and unknown-quality data keep the existing limited-data/coverage
labels, not a medical validity badge. Empty analytical ranges leave range metrics
unavailable; no default personal target is invented. Caller-supplied analytical
ranges are validated and frozen before source work. They never set AAPS targets.

The schedule check, retained source verification, bounded22-day history read,
facts build and report commit share the existing exclusive lock, SQLite
transaction and five-second SQL/wall budget. At most the existing review row
limit is read. A failed/over-limit call returns no partial new report. An async
host must run this synchronous operation off its event loop; cancellation does
not stop a reader thread, so it must drain that owned call before claiming stop.
Do not poll repeatedly or create internal model retries from an error.

UNCHANGED includes a separate dated source check; the archived report itself
keeps its original as-of/snapshot/expiry. A late upload, revision, deletion or
analytical-range change can commit a facts-only revision. Deletion of all prior
evidence produces explicit empty/limited facts rather than leaving old facts
current. Previous report revisions and frozen queue/model records remain intact.
Existing source/work checks reject a superseded report; no new inference is
created or replayed. Source checking establishes retained consistency only,
not completeness or clinical accuracy.

## Verification And Remaining Admission

Synthetic tests cover explicit/missing/disabled opt-in, invalid fields, CAS,
restart, timezone/DST, late edit/delete/range, original retention, queue
supersession, schema/quota rollback, foreign/tampered ciphertext, source limits,
mutable-range freeze and concurrent producer/disable serialization. Existing
history/report/queue/result/proof suites and the full backend suite remain gates.

Still separate: authenticated owner configuration surface, actual persistent
host scheduling with bounded retries, provisioned data/device consent, missing
domain adapters, AI policy/provider/profile admission, dispatcher invocation,
ordinary clinical publication and intended-device acceptance. No live05:00
execution, provider quality or UI readback is claimed by these source tests.

## Operational Caution

No database has been migrated outside synthetic tests. A future rollout must
use source that supports schema6 on every writer before opting in. Old code
rejects schema6 deliberately: rolling back the binary alone is not a database
rollback. Disable the new producer and preserve existing encrypted history;
never downgrade, delete the settings table, reset user_version or restore an old
medical database to make an older process start. Restore compatible source or
perform a separately reviewed forward repair. Unknown consent/storage state must
not be reconstructed as enabled.
