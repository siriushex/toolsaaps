# Trusted Text Supervisor Host

Run explicitly with PYTHONPATH=backend python -m app.ai.text_supervisor_host
--config /etc/personal-ai/supervisor.json. This is the root local supervisor;
the public API must run with its own nonroot identity. Importing the module
starts nothing. Startup does not create a runtime image, profile or database,
lookup addresses, log in, or request a model turn.

## Configuration

One closed version1 JSON object, at most128KiB, UTF-8, unique keys, no NaN:

- version: integer1.
- socket: canonical absolute path, at most103UTF-8 bytes.
- api: exact uid/gid integers. Both nonzero; both distinct from the worker profile.
- model: admitted model slug. Contextual instructions and limits are derived from
  contextual_chat_policy, never arbitrary configuration/client instructions.
- image: root/device/inode/entries/binary/binary_pin, matching RuntimeImage.
  Each entry has exactly relative/mode/size/sha256; directory size/hash are null.
- profile and control: exact path/device/inode/uid/gid DirectoryBinding records.
- scope: cgroup_root and slice_id; the root is /sys/fs/cgroup, optional generated
  slice identity follows the existing guard/stop rules.
- egress: chat/auth address lists, admitted_at/expires_at integer UTC seconds.
  OfficialEgress and the contained-process admission constraints apply.

The file and every ancestor are explicitly admitted root-owned state. The file
must be a0600 regular single-link file, opened no-follow; changes/replacement
during read are refused. No file values or raw exceptions are printed by the CLI.
This is an operator descriptor of independently verified inputs, not a tool to
capture current filesystem metadata or grant authority to a device request.

Startup verifies the public image/egress and existing private guard is idle.
Bound/unbound prior state requires trusted reconciliation; startup never clears
the journal, reinitializes a nonempty profile or restores an old authorization.
The existing separate owner login remains the only authorization source.

## Local Listener And Shutdown

The operator provisions a root:root directory with API traversal and no API write,
for example0711, under a protected local parent. The new socket is root:api_gid,
0660. Kernel peer UID/GID checks still apply independently. Startup refuses every
pre-existing path, including symlinks and stale sockets, without unlinking it.
Only the exact created socket identity is removed after shutdown. Replaced paths
are preserved and reported with a fixed error code.

The host drives the existing TextSupervisor and constructs SystemdTextProcess
for each single turn. SIGTERM/SIGINT stop admission, drain/cancel active work,
confirm independent teardown and close the socket before exit. Unknown teardown
produces failure; no success or automatic profile reset is fabricated.

Each factory call reads the protected descriptor afresh. An explicit operator
replacement may supply a new public-image/egress admission. Socket, API identity,
model/instructions/limits and profile/control/cgroup bindings must remain exactly
the same during that host lifetime; changing them requires a controlled restart.
Image/runtime checks still run before every native input. No automatic DNS or
trust refresh, new model selection, old-token recovery or provider retry.

## Verification And Deployment

Focused test_ai_text_supervisor_host covers strict configuration, root-file mode/
link/no-follow/racing replacement, real local socket/channel lifetime, existing/
replaced path preservation, active request shutdown, identity drift and fixed CLI
errors. Host/systemd operations and model responses are synthetic in these tests.
Run the full backend gate before publication. CLI --help is safe without root;
actual serve refuses non-Linux/nonroot execution before configuration access.

Still required for operational activation: immutable runtime/rootfs/descriptor
provisioning with fresh actual DNS/TLS/BPF controls, the separate nonroot public
API composition, encrypted data/key/purge/restore/load checks, and Android signed
history/chat wiring and device verification. This stage installs no service and
does not change the phone or issue a provider request.
