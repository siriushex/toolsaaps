# Local V3 Device Status

## Boundary

The authenticated loopback TLS listener supports immutable device-status uploads,
get-by-id and modification history alongside entries. No AAPS source changes,
new dependency or Room migration. Status advertises `cr`, not `crud`; PATCH, PUT
and DELETE are not admitted. Other unimplemented domains retain empty permissions
and501. Whole-app upload readiness, clinical projection and commands remain closed.

Stored `openaps.iob`, suggested/enacted data and pump state are transport evidence,
not confirmed insulin delivery or authority to change targets/doses. The local
owner secret does not independently attest the sending Android package.

## Stock RC1 Contract

Reference: official AndroidAPS4.0.0-rc1 at
d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0:

- `NightscoutApi.createDeviceStatus`: POST `/api/v3/devicestatus`, bare receipt.
- `getDeviceStatusModifiedSince`: GET `/api/v3/devicestatus/history/<revision>`,
  result list, no pagination parameter or continuation in the client method.
- `DS.toNSDeviceStatus` sends `date` but no `created_at`. The receiving handler
  requires `created_at`, so the wire representation supplies equivalent canonical
  UTC ISO and millisecond timestamps. If both arrive, they must agree. No server
  wall-time substitution or inferred delivery time is permitted.
- The mapper reconstructs empty uploader/pump-battery/pump-status wrappers.
  Known null/empty optional wrappers normalize consistently for retry identity.
  Opaque `pump.extended` and `openaps` object contents are retained, not interpreted
  as new commands or clinical settings.

## Identity, Completeness And Bounds

SHA-256 identity covers local owner, declared device and exact event time. A retry
with identical normalized content returns the existing identifier/revision.
Changed content at that identity conflicts409; another declared producer/time is
distinct. There is no random-ID or value-based merge. Explicit identifiers and
server metadata are accepted only as matching existing-record retry metadata.

The existing atomic store allocates modification revisions independently of
event time, including late uploads and clock rollback. History reads return all
matching records or refuse507: at most1000 rows and1MiB including wire metadata.
The client has no page-loop contract, so a partial success would silently lose
status. There is no pruning, overwrite or reset on capacity failure. An internal
tombstone is refused503 rather than misrepresented as a live status by a DTO
that cannot express invalidation; get-by-id hides it and POST cannot resurrect it.

Inputs use strict <=64KiB UTF-8 object parsing, duplicate/depth rejection, known
typed fields and recursive finite-number checks, including opaque objects.
Device/time are required; battery percentages must be integer0..100. Signed IOB
is preserved. Unknown filters, repeated parameters, malformed times, forged
metadata and unsupported mutations cannot silently succeed. Payloads and tokens
are not logged. Existing authentication, TLS and v1 behavior remain unchanged.

## Verification And Remaining Admission

`LocalNightscoutV3DeviceStatusApiTest` covers actual date-only uploads, equivalent
time formats, source identity, metadata retries, immutable conflicts, concurrent
retry/restart, late history, invalid fields, row/serialized-byte overflow and
internal deletion. The existing real TLS SDK test also checks stock status
create/retry/read, signed IOB/reservoir preservation and reconstructed-DTO retry.
Preparation and full-check commands are in LOCAL_NS_V3_ENTRIES.md.

The SDK test retains certificate and hostname validation with a temporary
synthetic-CA truststore. It does not prove Android user-CA trust, scheduling,
stock whole-app synchronization or pump compatibility. Treatments, profile,
food/settings, storage subscriptions, native signed Client Control and actual
clinical source arbitration remain separate requirements before migration.
Device installation additionally needs the correct installed signer/package,
fresh AAPS settings export and verified active CGM/pump input route. Do not
uninstall/reset AAPS to bypass an incompatible signer or missing data route.
