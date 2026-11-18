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
and receives one synthetic committed document. The SDK tests separately check
HTTP method/path limits, non-canonical query values, a changed leaf and closure.

TLS authenticates the listener, **not** the command author or a therapy role.
The test's synthetic owner credential is not suitable for a phone integration.
Before starting the channel on Android, provision a revocable settings-only
credential over the authenticated local boundary, enforce its scope on the
server, integrate the signed reply path and recover missed revisions. Do not
reuse upload tickets or overwrite Nightscout settings. A successful test or
subscription is not proof of device pairing, command application or delivery.
