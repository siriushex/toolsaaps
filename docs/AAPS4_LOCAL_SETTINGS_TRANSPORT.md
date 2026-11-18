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

The local issuer is not a public HTTP or Binder endpoint. Before starting it
on a phone, bind provisioning and renewal to the authenticated local AAPS
installation and explicit enrollment, implement signed replies and handle
missed revisions without replaying an ambiguous command. Do not reuse upload
tickets or overwrite Nightscout settings. A successful test or subscription
is not proof of device pairing, command application or delivery.
