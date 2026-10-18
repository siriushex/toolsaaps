# Local Nightscout V3 Authentication

## Scope

Implement the stock RC1 `NsAuth` exchange in Copilot, without modifying AAPS or
advertising collection/command compatibility. The reference is upstream commit
d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0, `core/nssdk` networking/NsAuth.kt and
remotemodel/RemoteAuthResponse.kt. Existing private loopback TLS and v1/socket
authorization remain unchanged.

## Contract

- GET `/api/v2/authorization/request/<access-token>` accepts the existing local
  identity's raw generated secret, not its legacy SHA-1 representation. No remote
  Nightscout secret or incoming header can substitute for this credential.
- Return `token`, `iat`, `exp`, with epoch seconds as expected by the SDK. The
  bearer is an opaque 256-bit random URL-safe value, not a claimed JWT. RC1 only
  forwards this value. Store its SHA-256 digest in memory, never its plaintext.
- Expire sessions after 15 minutes using both monotonic elapsed time and declared
  wall-clock expiry. Reject wall-clock rollback before issuance. Stop, restart
  and identity replacement revoke all sessions. Authentication alone must not
  mark the server READY or acknowledge a treatment.
- Bound refresh requests to 60 per minute and sessions to 32. Refuse excess
  issuance without evicting an active session. Invalid refreshes consume budget;
  established bearer reads and the legacy protocol do not depend on that budget.
- Every v3 path requires a bearer. Do not accept v1 api-secret, query tokens,
  refresh credentials or anonymous requests. Supplied Date must be epoch
  milliseconds within five minutes, matching the SDK's clock-skew error contract.
- Until collections exist, authenticated v3 requests explicitly return 501,
  `v3_collections_not_implemented`. No empty results or fake synchronization.
- Credential responses/errors are no-store. Credential-bearing refresh paths,
  request headers and response tokens must not enter access/audit logs.

## Verification And Rollout

Use synthetic real-loopback TLS tests for initial refusal, refresh shape,
v1/v3 credential separation, restart revocation, no clinical writes and log
redaction. Pure tests cover expiry, clock changes, concurrent refresh, limits,
malformed input and closure. Then run full Android test/lint/build and exact-head
CI. Collection revisions, stock-client TLS trust, authenticated storage events
and native paired command acknowledgement remain separate stages. Do not change
phone settings or recommend migration based on authentication tests alone.
