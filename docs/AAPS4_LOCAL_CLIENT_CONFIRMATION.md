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

This enrollment change implements the first condition only; it does not grant
permissions on its own, remove the clinical readiness checks, or send a target.
