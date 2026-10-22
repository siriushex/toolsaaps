# Native Insulin Observations

Continue from verified PR22, merged as6d5a4fa8 with the same tested tree.

1. Decode retained native insulin using the RC1 mapper's branch order, preserving
   bolus/SMB/priming, basal insulin, temporary basal, extended/emulated forms and
   each bolus's own ICfg. Keep revisions and tombstones, distinguish unknown fields.
2. Build a bounded immutable time-window inventory from one retained snapshot.
   Include overlapping intervals, reject duplicates/malformed/late evidence
   without partial success, and leave upstream completeness explicitly unavailable.
3. Share snapshot loading/cache with observed profiles and expose status through
   the existing native connection owner. No additional worker or AAPS source patch.
4. Test pure decoding, Room correction/delete/reopen/cache, owner lifecycle and
   actual pristine RC1 SDK; run full Android quality and feature CI before main.

This stage supplies real typed wire observations. AAPS's private uploader queue
is not in treatment payloads; it cannot be invented from local row counts.
Live meal-time admission also requires source coverage and prospective forecast
validation. No new live therapeutic behavior or APK/server/phone deployment.

Implementation and100focused checks passed, including native Room reopen and
actual stock SDK TLS. Full test/lint/compile/assemble passed in9m23s:6751passed,
six existing skips, zero failures/errors, SDK TLS2.496s, lint zero errors and
382existing warnings/eight hints. Source hashes were unchanged. Feature source CI
and final main integration are checked separately.
