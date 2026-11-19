# Same-phone enrollment on AAPS 4

Use the AAPS authorized-client screen to add Copilot on this phone. Enter the
eight-digit invitation PIN only in Copilot; it is not the pump pairing code.
Both apps must support the local invitation protocol. An invitation that is
dismissed or expires before completion cannot be resumed as a new session.

The first authenticated ping proves contact. Copilot separately verifies the
master signature on the role for this invitation. Only after the private
provider returns a positive receipt does AAPS recheck the pending client,
control setting, master identity and deadline, retain the rotated key, and set
the client to Active. No acknowledgement or an ambiguous IPC outcome leaves
the invitation pending. An active local client survives invitation expiry and
remains removable in the AAPS authorized-client list. Its identity and role
are not published in the Nightscout roster.

On the bench, distinguish three observations:

1. The PIN dialog closes and the local client remains in the AAPS list after
   expiry. This confirms enrollment on the master, not current liveness.
2. Copilot receives a new response to an authenticated challenge on a new
   session. An earlier invitation receipt is not a reconnect result.
3. A requested temporary target appears in AAPS and matches the request and
   expiry. Neither of the preceding steps establishes that a target applied.

After the invitation closes, Copilot can check condition 2 on this phone:
it sends a fresh nonce authenticated with the retained key, then checks the
master signature, both installation identities, nonce, explicit current role
and eight-second deadline. This call uses a separate signature-protected
provider; it does not publish a settings message or use a therapy counter.
The timestamp displayed is the last successful check, not target permission.

The manual connection check also tests the settings transport. Copilot opens a
single-use TLS polling ticket for an eight-second signed challenge. Binder
carries only the ticket, master identity and nonce to AAPS; it does not carry
the signed request. After subscribing to `/storage`, AAPS receives that
request from the pinned loopback listener. It checks the envelope, retained
client identity and current role before signing the response. Copilot verifies
the signature and nonce before reporting a successful settings test. The
ticket has no history cursor and cannot subscribe to normal documents. Its
socket closes after the check; the regular background membership check does
not open a socket. If the settings test fails, a Binder-only reply can still
prove membership, but cannot mark the settings transport as verified. Neither
result establishes a persistent command channel or proves target application.

Do not paste the invitation PIN, the AI activation code, the local upload
bearer, or a Nightscout API secret into the NSClientV3 access-token field.
The stock NSClientV3 profile needs a real Nightscout URL and that server's
access token. The scoped local upload route is not a full substitute: it
does not establish the persistent settings subscription or authenticate
outgoing targets. Keep external sync settings and cursors separate. Before
enabling a local bidirectional profile, verify pinned TLS for HTTP and
WebSocket, session renewal and revocation, and condition 3 on the bench.
