# Retained Combo History Diagnostics

## Scope

Copilot reads the already retained `pump.extended.ComboHistoryRead` and
`ComboHistoryJournal` subtrees through the existing clinical-evidence owner.
No additional pump reader, upload route, database schema or background worker is
introduced. This is a diagnostic projection, not raw journal ingestion or a
physical insulin-delivery timeline. The minimal AAPS patches remain unchanged.

The private pairing binding is deliberately absent from these wire snapshots.
Therefore diagnostics describe retained uploader observations, not proof about
the currently paired pump. `pumpBindingVerified` and
`deliveryCompletenessVerified` are always false. No recommendation, treatment,
target, onset-clock or clinical-admission behavior changes.

## Validation And Retention

The bounded Room snapshot includes all retained status identities and its own
collection revision. It is separate from the treatment/profile revision; the two
reads do not claim an atomic cross-collection clinical snapshot. Cache invalidation
follows the device-status revision independently of treatment changes. Limits
remain 10,000 rows and 4 MiB, with refusal rather than a truncated projection.

Strict version, coverage, state, counter and boolean decoding rejects unknown
or contradictory evidence. Row ownership, clock aliases, payload byte counts,
tombstones and revisions are checked before temporal filtering. An unparsed
future record cannot disappear from the integrity audit. As-known queries refuse
a newer collection revision. Unrelated valid status snapshots need no Combo data.

Existing native failure snapshots may contain only version, unverified coverage
and FAILED state. They retain a storage failure, not missing-zero counters.
Unknown-event counts are independent of decoded-event counts. Raw backwards
read clocks are retained as a clock-change hazard, never converted into a
trusted pump-event time.

Hazards are a set, not a sum of cumulative journal counters or a count of unique
clinical events. Earlier known gaps, failures and corruption remain visible
after newer clean statuses and Room reopening. They are not assigned to another
pairing or presented as current-pump faults. Unscoped corruption is distinct.
One active unclosed read in WRITING, or an unconfirmed in-flight block in READING,
is not latched as a historical failure. Additional unclosed reads or terminal
unconfirmed blocks remain adverse. Clean or in-progress evidence still cannot
prove coverage or authorize meal advice.

## Verification And Remaining Gates

Synthetic tests cover accepted v3 wire data, malformed/late/over-limit evidence,
cache refresh with no treatment change, database reopening and owner shutdown.
Full Android test/lint/build and real pristine SDK TLS are required, then exact
source CI and review before main integration.

Raw journal transfer, independent pump/delivery binding, initial coverage,
physical basal continuity, historical clock epochs and prospective meal-time
validation remain open. Source tests are not device or clinical validation.
No server, phone, APK publication, authentication or therapy change is included.
