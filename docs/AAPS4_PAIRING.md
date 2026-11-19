# Native AAPS 4 Pairing And Target Routing

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

New PIN enrollments in Copilot require final-format key rotation and a signed
roster. Hello carries a one-use P-256 key. The journal verifies the master's
answer against the pairing identity, the original public key and the offer
window, and replaces the secret atomically without resetting the counter.
Wait for the owner to permanently erase the one-use offer and accept the new
secret by answering a non-mutating Ping. Its correlated HMAC Pong pins
the master's P-256 key. The encrypted pairing record keeps the key and
roster signing-time high-water mark across restarts. Unsigned, tampered or
rolled-back rosters cannot authorize commands. Only a newer signed roster
can remove membership or change roles. A key change requires re-pairing.
Existing RC1 journals keep their original decoding path; new enrollments
never downgrade based on the contents of an offer or a received roster.
Final-format enrollments from before key rotation must be revoked and
re-enrolled. Keep the one-use exchange answer out of readable deletion
history, preserving its non-secret revision.

For the final same-device build, Copilot can issue one random token for a pending
PIN enrollment. The Binder call requires the exact AAPS package, its exclusive
UID, a matching signer and a signature permission. Match the client, master,
one-use Hello public key and unexpired enrollment; reject a different subject.
The endpoint is not an HTTP role or token administration API.

The AAPS receiver asks for this token only on its local HTTPS connection and
returns it in its stock signed key-exchange answer. Verify the MAC first, open
the AES-GCM token using the second HKDF block, and compare it against the
locally issued credential. A missing, different or unauthenticated token
cannot complete a token-bearing enrollment. Discard the issued token on
successful rotation or local revocation. The verified name travels as the
settings document's wire subject, on HTTP and WebSocket, without changing the
stored source. Legacy tokenless enrollment remains supported only when no
local token was issued. This identifies the client, not a therapy permission.
Temporary targets require the Caregiver or Admin role from the signed
roster, not the role shown in the PIN offer.

The AAPS master host test uses its actual receiver and a mocked executor:
Hello promotes the pending client, separate target and carb-only prepares
produce signed previews without execution, commit gets one signed result,
and replay cannot invoke the executor again. This is not a pump test or an
end-to-end Copilot action. No general action client or local NSClientV3 setup
is enabled by these checks. The installed observation-only configuration
still lacks an authenticated settings/storage session, separate client-token
handling and accepted clinical projection.
The clinical publication guard remains closed. The synthetic Copilot
channel test exercises Eating Soon as a target-only batch and validates
its commit ACK. Separately, the final AAPS executor test verifies one
database write per target and no pump call; a temporary Room database
test verifies the Overview's active-target query returns the recorded
target, the replacement Eating Soon target and then no target after
expiry. These checks do not read a target from the installed AAPS.

## Isolated Final Storage Round Trip

The final interoperability suite runs the real Copilot channel and local
TLS listener against an external synthetic master. The master uses stock
4.0.0 SDK cryptography and settings APIs. It subscribes to `/storage`, consumes
Hello, Ping, prepare and commit from live settings packets, and replies
through authenticated settings writes. A data-only subscription does not
admit the command path.

For both a routine target and Eating Soon, the test master writes to a
fresh stock AAPS database only after the matching zero-dose commit. It
checks the active record and expiry with the same repository method that
AAPS Overview uses, then returns the signed receipt. Resubmitting the
same operation in Copilot creates no additional settings write. This is
a synthetic master and storage test, not the Android service, Android UI
or a physical pump. The clinical publication guard remains unchanged.

## Same-Phone NSClientV3 Admission

Before entering a PIN, use the connection check in the AAPS 4 section. For an
unpaired device it samples whether a live client is subscribed to `settings`
on `/storage`. It does not read a PIN offer, create a pairing, identify the
peer as AAPS, or grant a role. A fresh glucose entry alone is not evidence of
this subscription. If the heartbeat expires, the sample is negative without
waiting for an additional packet. The display is a snapshot, not a promise
that the connection is still present; enrollment rechecks the live state.

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

The optional final SDK patch limits both HTTP clients (status/upload and token
refresh) to the configured numeric loopback origin. It trusts only the supplied
root CA and exact leaf DER, retains normal hostname verification, and disables
redirects, proxies and request logging for this transport. The allowed routes
are status, token refresh, the SDK's two permission probes, and collection
creates; data downloads, updates, deletions and settings publication are refused.
The stock client does not opt into this patch. The CI fixture exercises the
patched SDK against actual Copilot TLS and verifies retained synthetic glucose,
effective profile and pump-associated insulin without clinical promotion.

The compatible Android build adds a bounded upload operation to the existing
signature-protected same-phone observation provider. Copilot explicitly enables
the upload per installation and supplies the two live listener certificates
and a short-lived, non-renewable bearer over Binder. The server limits that
bearer to status and creates for CGM, effective profile and insulin observations;
it revokes the bearer when the call returns. Nothing installs a system CA or
changes stock NSClient settings, identifiers, cursors, or receive flags.

The upload scans the recent 30 minutes of CGM and the last 24 hours of insulin
observations. It reads the effective profile active at the time of the scan.
An in-memory acknowledgment cache and server-side exact-content deduplication
bound repeated uploads without treating an edited record as a second dose.
This is an observation store, not a complete delivery archive or a therapy
input. It is not a command transport. On-device installation and freshness
checks remain separate from the synthetic TLS fixture.

When the accepted clinical forecast is unavailable, Copilot can show a
separate observation-only view using recent local AAPS v3 glucose in explicit
mg/dL. The preview requires one sensor source, unique timestamps, fresh data
and enough contiguous history. The existing trend model is used without
meals, insulin, calibration transfer or clinical confidence intervals.
The preview does not replace a current accepted forecast, populate its storage,
or feed Target Manager, alerts or meal timing. When it is strictly newer than
the stale clinical graph, the observation view can replace that graph for
display. Do not attach the stale forecast, meal curve, IOB, COB, ISF or CR to
the new sample. Safety warnings and settings stay in force. Active profile and
insulin records remain diagnostic until clinical completeness is independently
proven. Restoring an old database cannot recreate fresh signed session state.
