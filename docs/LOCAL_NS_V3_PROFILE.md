# Local V3 Profile Catalog

## Boundary

Copilot's existing authenticated loopback TLS listener accepts immutable RC1
profile catalogs. It reuses the revisioned Room store, body budget and bearer
authentication. There is no database migration, clinical writer, active-profile
selection, new worker, therapy permission or change to AAPS source.

`defaultProfile` remains the catalog's default name, not evidence of activation.
Effective-profile switches and per-record insulin configuration are separate
typed treatment evidence; the catalog must not invent DIA, insulin selection,
IOB or an effective profile. The production native target guard remains closed.

## Pinned Protocol

Reference: pristine RC1 d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0.

- `NSAndroidClientImpl.createProfileStore` stamps app=AAPS and posts an immutable
  JSON object to `/api/v3/profile`; the receipt is unwrapped.
- `getLastProfileStore` reads `sort$desc=date&limit=1`, returning a result list.
- `getProfileModifiedSince` reads `/api/v3/profile/history/<revision>?limit=10`.
  History is ascending by modification revision, not catalog date. Each page's
  ETag stops at its last returned revision. Explicit limits 1..1000 are bounded.
- A latest-only read reports only the returned catalog's revision, never the
  collection head: a later-arriving old catalog must not be skipped by history.
- Get-by-identifier is also supported. PATCH/PUT/DELETE are refused with 405. Status
  advertises only `cr` for profile; food remains unsupported with empty permission.
  Whole-client synchronization readiness is not inferred from these permissions.

## Identity And Validation

SHA256 identity covers the authenticated local owner scope and exact catalog
date. Identical normalized retries/readback preserve identity and revision;
different content at the same date conflicts with 409. Explicit server identifiers,
revisions and ownership fields are accepted only as matching retry metadata.
The access secret itself does not independently attest the sending Android app.

Strict UTF-8 objects are limited to 64 KiB, with duplicate keys and excessive
nesting rejected. Fields follow the pinned native exporter, not arbitrary legacy
Nightscout extensions. Unsupported fields fail with 400 rather than being discarded.
Date is mandatory; optional ISO created_at/startDate must agree to milliseconds
and normalize to equivalent UTC timestamps. A genuinely empty catalog stays
empty, without an invented default. Nonempty catalogs require a matching default.

Profiles preserve original native units (`mg/dl` or `mmol`), timezone, names and
all five schedules. Numeric values must be finite; basal may be zero, other
values must be positive. These are structural checks, not clinical validation.
Schedules must start at midnight and have strictly increasing integral seconds
within one day. Low/high targets must share boundaries and remain ordered.

RC1 BlockExtension writes an HH:00 label even for a non-hour start, while its
reader prioritizes nonzero timeAsSeconds. Accept the exact label or this native
hour label and retain both fields unchanged; never round the numeric boundary.
For example, 06:00/23400 remains a 06:30 boundary. Mismatched hours are rejected.

History preserves modification paging across late arrivals and restarts. A page
over 1 MiB including server metadata refuses with 507, never truncates successfully.
An internally deleted catalog is hidden from direct/latest reads and cannot be
recreated by POST. History containing an internal tombstone refuses with 503 because
RC1's profile consumer does not implement invalidation; no public deletion path
or retention purge is introduced.

## Verification

New profile tests first reproduced 13 failures against the previous 501 route.
Fifteen profile tests pass, including database reopen, clock rollback and exact
minute boundaries. Full Android test/lint/compile/assemble passed: 6593 tests,
0 failures/errors, 6 explicit private-input skips. HTTPS profile authorization
and the actual pristine SDK fixture both ran successfully, not as skipped cases.
The pristine SDK fixture exercises profile POST, retry, readback, latest,
modification history, late arrival, exact schedule values and both native units.
It uses synthetic data and its existing validated TLS truststore, not a phone.
APK size/class preservation checks passed; details are recorded in AI_NOTES.md.

Clinical/effective-profile projection, Android CA trust, complete source
arbitration and device migration remain separate acceptance gates described in
AAPS4_CLINICAL_PROFILE_CONTRACT.md. No live target is a test fixture.
