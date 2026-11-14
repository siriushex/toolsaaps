# Native RC1 Pairing And Target Routing

## Scope

The Copilot settings screen can complete the native eight-digit PIN exchange
through the existing local TLS/settings/storage channel. AAPS source is unchanged.
The status "Signed connection confirmed" means communication, not clinical
readiness or applied therapy. Production target publication remains closed by
`native_clinical_projection_unavailable`: typed treatment/profile ingestion and
master-handler admission are still incomplete. This is an explicit safety lock,
not a successful-but-empty sync adapter. Do not remove it merely to try on a pump.

## Enrollment

- Read at most eight live owner offers; reject ambiguity, wrong schema/identity,
  expiry, non-ASCII PIN and unauthenticated ciphertext. Native PBKDF2-HMAC-SHA256
  uses 200000 iterations and AES256-GCM with separate salt/IV and appended tag.
- PIN is transient, masked, absent from saved state/logs/clipboard. The inline
  form reuses the existing credential window-security lease and clears on pause.
  Five attempts per ten minutes persist in encrypted private state.
  Clock regression cannot reset the budget. Immutable UI strings cannot be
  claimed securely erased; owned mutable PIN buffers are cleared.
- Atomically seal the key/owner and allocate Hello counter1 with the settings
  outbox. Never regenerate a lost key, reset an old counter or republish Hello.
  New identity replacement requires explicit local revocation and no unresolved
  native or legacy pending target. Retired identity/counter records remain sealed.
- AAPS permissions are whole-client, including commands Copilot does not expose.
  The consent form discloses this. Copilot's signer exposes only Hello/Ping and
  target-specific prepare/zero-dose commit, not a generic action-signing API.

## Connection Proof

An active roster from a newer `settings/aaps` revision, string-valued
`ns_allow_client_control=true`, an authenticated current storage subscription and
a new signed Pong are independent requirements. Ping alone can succeed for a
pending native client and therefore is not enrollment proof. Roster deletion or
client removal revokes locally; a later roster cannot silently undo revocation.

The existing observation runtime owns one conflated Room-revision observer and
a 60-second liveness refresh while paired. No unpaired timer or second worker is
introduced. Each runtime start requires its own new probe. Hello, probes, prepare
and commit share one persistent counter. A pending target suspends new probes.
Stop/restart/revocation never commits a preview, resends a command or clears an
unknown commit. Subscription availability uses volatile state rather than holding
a socket-session monitor inside a Room transaction.

## Target Manager Boundary

`NightscoutActionRepository` selects the configured native transport before any
legacy HTTP/broadcast writer, retaining existing arm/throttle/ownership/preflight
checks. It uses the existing armed publication lease for each atomic write, not
for the network wait. The delivery guard is repeated before commit; runtime and
storage are checked again after suspending guards, and signing reads a fresh clock.

Only a correlated signed successful commit ACK yields SENT. Preview, publication
and an HTTP response do not. Its transaction settles only the existing matching
pending target action, without creating a clinical row; a late background ACK can
therefore update the UI without resubmission. Unknown outcome stays pending without alternate
channel fallback. A retry reconciles only; it never adopts/replays an old operation.
Missing private state with a remaining outbox still counts as configured/damaged,
not as permission to select a legacy writer. An absent private receipt cannot
prove that an old legacy operation was absent.

Local revocation does not cancel an already published command or revoke AAPS-side
permission; the confirmation explicitly says to remove the client in AAPS too.

## Verification And Remaining Admission

The unchanged SDK fixture creates an offer using its actual cryptography, verifies
Copilot Hello/Ping signatures and DTOs, then signs preview/final ACKs. Counter
sequence is 1/2/3/4 across Hello/Ping/prepare/commit. It permanently removes the
offer and publishes a synthetic roster over TLS; commit arrives via `/storage`.
It is not execution of the Android master, a pump action, Android CA trust or
clinical ingestion. UI tests cover explicit consent, transient PIN, busy state,
communication-only wording and reachability at320dp/large Russian text.

Local final test/lint/build passed on2026-10-20 in10m6s:6483 passed,six prior
private-input skips,zero failures/errors and zero lint errors. All five UI tests
passed, including pause/resume clearing and actual secure-window flags. The SDK
fixture ran4.176s,not skipped. The41314825-byte APK retained all6997 compiled app
classes; icon-only shrinking and size guards passed. Six external build-recipe
checks passed. Exact-source CI is still required; none of this is device admission.

Before USB migration: finish typed clinical synchronization, reproduce/review the
stock receiver's clock capture across suspended/queued work, verify actual master
expiry/application behavior, resolve loopback Android CA trust, inspect signer
and settings export, and preserve the phone's JugglucoNG input and pump state.
No AAPS update or live therapy test has been performed by this stage.

## Final 4.0.0 Wire Verification

The final upstream SDK fixture publishes a PIN offer containing a role and a
display name, an HMAC-signed Pong containing the master's public key, and a
schema-2 settings document with a signed roster. Copilot validates these on
the isolated TLS listener, including role, counter, tamper and replay checks.
The role in an offer is informational; it is not permission to issue actions.
The roster must verify with the key obtained from the correlated Pong.

The final-format decoder is not attached to production enrollment. Runtime
ownership must support bootstrapping the first key without admitting an
unsigned roster, then retain the verified key and signing-time high-water
mark across restarts. The legacy RC1 path does not accept a final-format
offer or Pong.

The AAPS master host test uses its actual receiver and a mocked executor:
Hello promotes the pending client, separate target and carb-only prepares
produce signed previews without execution, commit gets one signed result,
and replay cannot invoke the executor again. This is not a pump test or an
end-to-end Copilot action. No general action client or local NSClientV3 setup
is enabled by these checks.

## Same-Phone NSClientV3 Admission

The signature-protected AAPS 4 data check is Android IPC; it is not a
configured NSClientV3 session. The tested AAPS build does not trust a
user-installed CA for its HTTPS client. In addition, the stock master
uploader requires full rights across six collections (or a connected
WebSocket), whereas Copilot advertises only the operations it implements.
Passing an SDK request does not establish that the stock uploader will run.

Do not fill the live NSClientV3 address or credential until the same-phone
route can verify the expected Copilot TLS identity and isolate its upload
state from the existing Nightscout endpoint. Do not widen TLS trust, grant
unimplemented rights, or rely on saved receive flags for upload-only
isolation. The initial route must not import records, dispatch commands or
reuse another endpoint's synchronization cursors. Verify these properties
on a stand before configuring the therapy phone.
