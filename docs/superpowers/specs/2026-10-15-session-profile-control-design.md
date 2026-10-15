# Session Profile Control

Continuation of the owner-requested independent ChatGPT-session work and the
verified synthetic exchange stage9291475d. This slice is an inactive local
control component, not a launcher, API route, login or production integration.

## Decision

Use a private, separately admitted controller directory, permanent file lock and
durable run journal. A process-local mutex cannot survive a controller crash;
using the medical-history database would mix independent ownership boundaries.
One trusted installation manifest must map the one profile to one controller
directory. This is same-host/local-filesystem coordination, not a distributed
lock or protection from a compromised controller/root/same-UID process.

## Identity And Admission

Trusted administration supplies pinned canonical directory paths, device/inode,
UID/GID and exact0700 modes for profile and control root. No symlink component,
root replacement, path overlap, untrusted state-file link or unknown controller
entry is accepted. The lock is an owned0600 empty regular single-link file; it
is never unlinked/replaced on release. Recheck its current inode after locking
and before each transition. All descriptors are close-on-exec. A child inheriting
a lease through fork cannot transition it or unlock the parent's file description.
The journal inode is also pinned for a lease; replacement with identical bytes
does not justify adopting another writer's state. Configuration is frozen and
the run ID is read-only. Journal directory identifiers require integer types.

Initialize only an empty profile and unused controller root. Thereafter journal
identity must match both admitted directories. No state-reset, implicit adoption
of an existing authorization, token read/copy, backup restore or orphan promotion.
Profile admission is a bounded top-level metadata inventory, not a recursive
worker-filesystem audit: files0600/single-link, directories0700, no top-level
links/special files, no native writer .tmp orphan, bounded entries/file bytes.
The sole0644 metadata exception is the exact36-byte installation_id. Its public
UUID can be validated; auth.json is never opened and cannot be deemed valid by
metadata. Recursive native alias/state containment remains a separate gate.

## State Machine

Idle -> reserved with a newly generated canonical-v4 run identity -> bound to
the exact32-hex native systemd InvocationID -> idle only after confirmed stop
and revalidated profile/control state. Reservation is durable before a launcher
may act. The guard itself launches nothing. The profile lock remains held across
the full lease. Exiting the context, exceptions and process death release the OS
lock but never clear the durable active marker. Restart therefore refuses a new
reservation even if the kernel lock is free.
The exact cgroup root and slice are persisted with the marker. A new controller
configuration cannot recover through a different empty scope.

The trusted caller supplies an existing SystemdStopVerifier bound to this exact
run/invocation, configured cgroup root and slice. It must actually return True
from its original-identity/stop/empty-descendant check; a bool supplied by a
request or an unrelated verifier cannot release the journal. Cancellation must
drain any already-started stop operation before returning/releasing the lease,
while retaining the active marker. No real stop command runs in source tests.

An explicit trusted recovery operation may close only a matching bound marker
after the same stop verification and fresh metadata checks. Reserved-but-unbound
crashes remain refused: absence of a saved InvocationID is not proof no worker
started. No generic force-clear/automatic recovery/timeout-based lock stealing.
Ambiguous stop, changed roots, profile or journal orphan retain the blocked state.

## Durability And Errors

Bounded closed-schema JSON contains only directory/run identities and phase, never
tokens, codes, prompts or medical data. Write a new owned0600 sibling, complete
write/file sync, atomic replace and parent sync. Ordinary pre-replace errors may
remove only the writer's exact temporary inode. A killed writer can leave an
orphan; refuse it instead of promoting/deleting it. Post-replace errors never
restore old journal/token bytes or claim the update did not occur. This requests
POSIX durability, not power-loss or provider-refresh transaction certification.
Errors expose fixed codes, not paths, file bodies or raw OS exceptions.

## Verification And Exclusions

Failing-first tests cover empty initialization, lock contention including another
process, crash markers, native-writer/control orphans, mode/link/schema/identity
rejection, exact stop binding, failed/cancelled stop, safe bound recovery, fork
ownership, replaced roots/lock/journal and pre/post-replace errors. Synthetic
fixtures only; never open a real auth profile or execute host systemctl.
Run focused/full backend tests, E/F checks, explicit reviewed publication and
exact-SHA Verify CI. Existing Android/native sources and public routes stay
unchanged. Immutable runtime delivery, private owner code presentation, real
provider/model admission and per-device contextual chat remain later gates.
