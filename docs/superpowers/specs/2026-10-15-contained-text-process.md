# Contained Text Process

The trusted TextSupervisor can construct SystemdTextProcess with a RuntimeImage,
SessionProfileGuard, OfficialEgress and the same fixed TextSessionPolicy used by
the API runner. Construction starts nothing. This stage supplies the concrete
process implementation; it does not install a service or enable a default route.

## Admission

The operator supplies a complete public-image manifest: root device/inode,
root-owned directories/files, exact modes/sizes/SHA256, and a separate pinned
executable. All ancestors must be root-owned and non-writable by other users.
Traversal uses no-follow descriptors, rejects extra/missing/symlink entries and
checks each file before and after hashing. No automatic capture or adoption.
Image and binary paths must be disjoint from private profile/control state.

The image contains public loader/libraries, bin/env, bin/sh, work/ca.pem,
etc/hosts, etc/nsswitch.conf, work/text-model.json and empty work/client,
work/profile, work/home, work/empty and tmp mountpoints. All on-disk directories
are0755; writable home/tmp exist only as bounded temporary mounts in the child.
No retained auth, config, sockets, clinical payload or conversation in the image.
The complete image manifest is bounded to256 entries/64MiB in total; the separate
native binary is at most512MiB. Small hosts,
resolver and model configuration have independent read limits.

The exact selected model catalog must already be restricted to direct text,
empty tools and disabled shell/search/patch capabilities. Model identity and
default reasoning stay intact. Native overrides fix the official provider and
ChatGPT base URL, tool-disabling settings, file auth, temporary state and catalog.
The profile guard checks private metadata without reading auth bodies. Local
config.toml/AGENTS.md/instructions.md in that profile refuse startup.

Only chatgpt.com and auth.openai.com appear in the image hosts file. NSS uses
files only. A trusted host admission supplies1-4 public IPv4 addresses per host,
without duplicates, and a lifetime at most900seconds. Time is checked before
image admission and before releasing START. No DNS lookup, new address adoption,
proxy environment, fallback endpoint or provider retry occurs in this component.
Host composition must obtain and validate new admissions/immutable images when
pins expire. Global IPv4 syntax alone does not establish domain ownership.

## Launch And Input Barrier

Linux root supervisor only; the HTTP API must use a separate unprivileged UID.
Each run reserves the existing guard before creating one generated systemd unit.
The argv, environment, mounts and shell barrier are fixed trusted code, with no
question/context interpolated into argv or shell. Only the permanent owner profile
is writable on disk; control state is never mounted. The shell waits for START
before exec of the pinned native app server.

Limits:512MiB RAM,swap0,25% one CPU,32 tasks,840seconds,32MiB individual files,
no core dumps,16MiB each temporary home/tmp. Nonroot copilot-ai identity,
no capabilities/new privileges, system-call filter, restricted namespaces,
read-only root/runtime, private devices and no home-directory visibility.
Only AF_INET/AF_UNIX, deny all IP with the admitted /32 exceptions, no binds.

After READY, bind the original InvocationID durably. Before START, verify the
unit/PID/cgroup, kernel UID/GID/groups/capabilities/seccomp, actual cgroup resource
limits, root/profile/binary inode mappings, mount access/tmpfs types and exact
child environment. Read-only show verifies the configured official address and
family policy. Active BPF enforcement and TLS/domain control probes remain host
admission requirements: property readback alone does not prove packet filtering.
Unknown/missing/ambiguous observations refuse input.

## Cancellation And Completion

Start runs once with a12second deadline. Shield OS creation from caller
cancellation so a partially created process can still be reconciled. Stop closes
admission immediately, waits for start, resolves only the generated owned unit,
and uses the existing exact SystemdStopVerifier/ProfileLease.finish. There is no
stop by arbitrary PID/name, profile reset, token copy or blind cleanup.

Native input supports full JSON escaping of the existing text limit. Output is
bounded by the existing65536byte line/1MiB session protocol; stderr is discarded
with a32768byte budget and never surfaced. After the exact cgroup is empty, close
native readers and drain bounded pipes before confirming helper termination. Only
the owned pipe helper may be killed after positive native-stop proof. This is not
a substitute for stopping the native unit. Cleanup survives cancellation of its
caller. Missing/changed invocation or unconfirmed stop retains the durable claim;
the existing supervisor refuses further admission and withholds the answer.
Any failure after a durable reservation can leave an unbound marker, including a
failed spawn or rejected profile configuration. This deliberately requires trusted
reconciliation, not automatic marker deletion or a guessed idle state.

## Verification And Remaining Work

Synthetic tests exercise actual child pipes and the Unix supervisor channel,
policy preservation, escaping/line/stderr budgets, modified image/config/hosts,
kernel metadata/limits/mount refusals, gate timeout, cancellation during creation
and teardown, unknown stop and quarantine. No test starts host systemd, reads
owner authorization, calls a provider or supplies clinical context.

Required before real activation: immutable-image provisioning and fresh DNS/TLS/
BPF controls on the intended host; narrow root supervisor/nonroot API assembly
with protected socket and startup recovery; durable encrypted storage/key/purge/
restore/load validation; Android signed context/chat wiring and device verification.
Preserve the current owner profile/control journal and uncertain outcomes during
rollback. Disable admission and stop only positively identified owned invocations.
