# Photo API And Isolated Decoder

## Source Path

The explicit owner API can opt into MEAL_PHOTO using a protected `vision` socket
descriptor. Without that field the existing text-only configuration is unchanged
and the photo route remains absent. HTTP still runs under a dedicated nonroot
identity; it does not decode pixels, open the login profile or select commands.

Android prepares at most 1 MiB JPEG and wraps it in base64 JSON. Both its closed
route check and concrete HTTP client must admit the existing 3 MiB photo POST
budget; the generic 96000-byte connection limit is not an image limit. Other
routes and the 65536-byte photo response limit stay unchanged. Loopback tests
exercise the concrete client with a maximum prepared image and request overflow,
not only an injected signed transport or a tiny image fixture.

SupervisorMealVisionWorker freezes canonical work metadata and the bounded JPEG
into immutable bytes. A distinct Unix endpoint verifies kernel peer credentials,
the fixed food-policy digest, canonical job/owner/device/deadline metadata and
image schema before constructing a worker. A correlated receipt includes the
complete frozen-work digest. No automatic reconnect/replay, arbitrary prompt,
file/URL input, model selection or native RPC proxy. Text's closed schema and
limits remain unchanged; only private lifecycle hooks are shared.

The root vision_supervisor_host builds exactly:

1. SystemdMealDecoder using a separately pinned public decoder image and UID.
2. MealVisionWorker with that explicit normalizer, never its development thread.
3. NativeMealVisionTransport/SystemdVisionProcess using the same original
   SessionProfileGuard binding as login/text and the exact fixed model policy.

Decoder stop confirmation precedes any authenticated native input. Failed/unknown
stop poisons the supervisor and keeps the existing signed job claim unresolved.
API startup refuses executing ledger claims; it never clears/replays them.
Both text and photo services are drained on API shutdown or failed maintenance,
including when one service reports cancellation during its own cleanup.

## Decoder Boundary

The decoder's fixed systemd unit runs as `copilot-image`, distinct from API and
native/profile UID/GID, with a pinned RootDirectory, read-only Python binding,
private network namespace/AF_UNIX only, no profile mount and no credentials.
Limits:256MiB RAM, zero swap,25 percent of one CPU,8 tasks,12 seconds, no core or
file output. Only /tmp is an8MiB writable tmpfs. All executable/library/stdlib
files must be public, root-owned, non-writable and individually pinned.

The exact tree verifier additionally pins the current decoder entry, JPEG
normalizer, request schema and empty package initializers to this source stage.
The interpreter/Pillow/loader/stdlib image is independently provisioned, not
adopted from the API virtual environment. It must fit the existing256-entry/64MiB
public-file budget. Runtime normalization still requires exact Pillow12.3.0.

The parent withholds image bytes until the original invocation, unit/user,
separate network namespace, UID/GID/groups, capabilities/NNP/seccomp, actual
cgroup limits, root/binary mounts and cleared environment pass kernel checks.
The fixed entry reads one length-prefixed JPEG and emits one bounded
length/width/height/JPEG result, then waits to remain independently identifiable.
No metadata or decoder errors are logged. Exact original stop, empty descendant
cgroup, reaped IO and zero trailing stdout/stderr precede accepting the image.
The native profile lease cannot substitute the decoder user in a stop verifier.

## Configuration

Owner descriptor version1 retains all existing fields. The only optional addition
is `vision: {socket, uid, gid}` for the trusted supervisor's distinct socket/peer.
It cannot equal the text socket, a key or private store path; unknown fields and
null/partial descriptors fail closed. This explicit configuration is an operator
admission, not a feature flag to enable before host checks.

Run the trusted entry with:
`PYTHONPATH=backend python -m app.ai.vision_supervisor_host --config /etc/personal-ai/image.json`

Its closed descriptor is the existing text host shape plus
`decoder: {image: {root, device, inode, entries, binary, binary_pin}, uid, gid}`.
The native image must admit the exact model's image modality. The decoder image
and identity must be disjoint from private/native/API bindings. Startup verifies
both images, current official egress, the existing idle native guard and no active
or populated decoder units before binding its protected0660 socket. Reload may
change independently admitted public images/egress, not private identities/policy.
Imports, construction and --help perform no deployment or inference.

An operator may supply --text-admission with the existing protected text-host
descriptor. Each load still reads the separate vision descriptor, then adopts
only the text source's public image and official egress. Model, API peer and
original profile/control/cgroup bindings must match; the image listener and
offline decoder remain independent. No missing-source fallback or credential
copy occurs. This supports the existing public admission refresher without
editing its policy or letting an HTTP request select a source.

## Checks And Deployment

Synthetic tests cover signed API composition, actual local socket/child byte
protocols, malformed/foreign/expired work, stop/cancellation, source pins,
identity separation and weakened kernel records. Full backend with pinned Pillow
and scoped lint are required before source publication. These tests substitute
privileged host admission and do not prove actual Linux isolation or inference.

Read-only inspection on2026-10-15 found no copilot-api/copilot-image users or
dedicated owner API directory; only earlier offline/probe root images were found.
Do not reuse those offline images as an inference admission. Public API remains
the explicit nginx503 until a separately verified deployment.

Operational rollout still requires provisioning public images/identities and
protected API storage/keys, actual kernel/network/storage/retention/restore/load
checks, then scoped synthetic provider and signed device tests. Never copy login
tokens into the API/decoder or enable capabilities by editing nginx responses.
Rollback drains only owned services/exact invocations, retains uncertain claims,
profile and encrypted state, and restores only the owned proxy mapping. It must
not initialize over existing private state, reset a ledger or alter AAPS/therapy.

## Owner Rollout (2026-10-17)

With explicit approval, the separate image host was admitted against current
public pins, existing exact-model catalog and original guard. Actual isolated
decoder normalization/stop passed; exactly one synthetic food request returned
a typed four-ingredient estimate with confirmed native stop and idle guard.
After an initial failed proxy assertion, both owned config hashes were confirmed
restored and API/text active. Config-only activation then passed with bounded
reload propagation checks, sustained idle before API restart, photo-only 3 MiB
and unchanged text 128 KiB limits. Other owner configuration was unchanged.
External HTTPS authentication refusal and enabled active image service were
checked. No real patient image, nutrition-accuracy validation, phone installation,
credential copy, ledger reset or therapy change is implied.
