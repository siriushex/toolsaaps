# Independent local settings transport

The pinned AAPS 4.0.0 recipe applies `final-settings-polling.patch` after the
upload patch. `NsLocalSettingsPolling` is a separate OkHttp call factory for
the Socket.IO manager. It requires the enrolled root, exact listener leaf,
normal HTTPS hostname verification and the numeric loopback address and port.
It allows Engine.IO 4 polling on `/socket.io/` only. The REST upload client's
existing path/method allowlist is unchanged. Neither client changes global TLS
trust or the main NSClient address, token, cursors or preferences.

The integration test runs that SDK and the AAPS Socket.IO library in a separate
JVM against the Copilot listener. It disables transport upgrades, reconnection
and manager reuse, subscribes to exactly `settings`, checks the acknowledgement
and receives a synthetic journal publication for its own client ID. The SDK tests separately check
HTTP method/path limits, non-canonical query values, a changed leaf and closure.

TLS authenticates the listener, **not** the command author or a therapy role.
Copilot can now issue a separate, two-minute same-phone ticket scoped to one
client ID. It is claimed only once by one Engine.IO session subscribing to
exactly `settings`. It cannot authenticate to REST or upgrade that session
to another namespace. The listener queries only the client's known outgoing
journal identifiers and checks the envelope identity and validity interval.
The ticket's deadline uses elapsed time, not the phone's wall clock. Revoking
it closes its socket and discards pending packets. No reusable session ID or
ticket is written to the socket audit. The interop test uses this ticket, not
the Nightscout owner secret.

Enrollment uses the private, signature-permission Android provider. It accepts
an encrypted native PIN offer only from the installed AAPS package and its
exclusive UID. The PIN never crosses Binder. AAPS validates the provider
identity and uses its scoped ticket to subscribe over pinned TLS. Copilot
decrypts the offer locally, verifies that exact subscription and atomically
journals the first Hello. Expiry, withdrawal or replacement cannot complete
the withdrawn offer. AAPS verifies the signature, counter, client ID, time and
Hello payload. No command handler runs on this route.

The loopback fixture runs the production AAPS Hello receiver and the real
Copilot listener in separate processes. A verified Hello is not a connected
client. The local enrollment UI, signed key reply, signed roster, ongoing
session ownership and post-disconnect reconciliation must precede phone use.
Do not reuse upload tickets or overwrite Nightscout settings. The separate
target round-trip test reads ordinary and Eating Soon targets from an isolated
AAPS database; it does not demonstrate application on the phone.
