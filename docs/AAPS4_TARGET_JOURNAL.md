# Native Target Journal

Room37 adds only a private `aaps_v3_target_journal` table. No clinical columns,
live controllers, phone settings or AAPS sources change in this stage.

## Ownership And Publication

- One bound client, one unresolved command. Binding is NOT owner pairing approval;
  a fresh authenticated native pairing and active roster are still required.
- Counter allocation, encrypted owner/receipt state and controlled v3 settings
  publication share a Room transaction. A failure of either journal write or wire
  storage rolls back all of them. Notifications must be sent only after commit.
- Operation IDs are hashed for row keys. Repeating an ID returns its saved state
  without publication; changing its target is refused. Receipts are retained, not
  recycled. A100000-row limit fails closed rather than dropping replay protection.
- Prepare and commit have separate caller guard checks. Preview is not application.
  Commit is target-only with zero dose, and uses the exact signed preview ID.
- Target notes retain `copilot:<operation ID>` for later clinical reconciliation.
  They are correlation, not independent evidence that a target was applied.
- Prepare-only expiry releases ownership because no commit was issued. Commit
  timeout stays UNKNOWN and holds ownership. A signed correlated final ACK can
  reconcile later. Restart never re-publishes a command or chooses a legacy route.
- Terminal receipts discard envelope/preview bodies but retain target fingerprints,
  counters and outcomes. There is deliberately no reset/prune API here. Explicit
  revoked-owner replacement retains sealed retired identity/counter state; it is
  not an old-identity counter reset. See AAPS4_PAIRING.md.

## Storage

Each row is sealed with AES256-GCM and a separate non-exportable Android Keystore
key, version byte, random12-byte IV and128-bit tag. AAD binds the table format,
row key and revision. Reading existing state never generates a replacement key.
Missing key, malformed state or authentication failure blocks new commands.
Messages and entity string representations are redacted. Plain byte buffers are
cleared after use; immutable runtime JSON strings are not claimed securely erased.

The existing database backup exclusions cover this table. The explicit AI export
whitelist does not include it. This protects against accidental export and row
swapping, not restoration of an entire old app/database/Keystore snapshot. Native
AAPS replay counters remain independently authoritative. Never restore counters
from an old backup or silently reuse an old pairing after database replacement.

## Local Verification

On2026-10-19, the journal/protocol/cipher and migration assessment passed46 cases
with one prior private-input skip. The real unchanged RC1 SDK fixture now reads
the journal's atomic outbox, verifies notes and counters1/2, signs preview/final
ACKs over TLS, and settles the receipt without clinical writes.

Unchanged upstream Android host tests also passed173 cases without skips:
ClientControlReceiver49, RoundTrip24, UplinkIntegration2, BatchActionMapping18,
WizardBolusExecutor80. These exercise the stock master on mocks, separately from
the cross-process SDK fixture. They are not real pump/device acceptance.

Full local Copilot test/lint/build passed in6m48s:6438 passed,six prior skips,
zero failures/errors,zero lint errors. The40109883-byte APK retained all6913
compiled app classes. Full backend passed1942 cases with27 optional skips after
the isolated test-helper polling correction. Exact-source87ce15e8 Verify run
39315169287 subsequently passed all jobs.

## Live Admission

The journal now has a pairing UI, runtime ACK owner and repository routing;
production publication is explicitly blocked until clinical projection and master
admission. See AAPS4_PAIRING.md. Authenticated `/storage` covers committed outbox;
see LOCAL_NS_V3_STORAGE.md. Keystore provisioning still needs
device verification. Whole-client v3 exchange, TLS trust and JugglucoNG continuity
must pass before the AAPS USB upgrade. No live therapy test is authorized by a
synthetic fixture or by successful compilation.
