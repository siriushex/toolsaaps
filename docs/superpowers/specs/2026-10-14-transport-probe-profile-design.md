# Credential-Free Transport Probe Profile

Owner approved the isolated gateway design before this implementation. This
source slice implements pure profile generation and receipt validation only.
It neither installs nor launches a worker. The exact host manifest, namespace/
relay composition, actual cgroup enforcement and invocation-bound teardown of
all roles must be independently verified before any remote transport test.

The probe is deliberately narrower than inference: HEAD / over HTTPS to exactly
chatgpt.com or auth.openai.com. No auth/refresh/model route, prompt, credential
mount or medical input is accepted. A valid transport receipt is not AI readiness.

Use the existing Nginx HTTP/TLS engine, not a new HTTP/TLS implementation. Fixed
upstreams verify provider names and certificates. Default SNI, cross-host routing,
methods other than HEAD, noncanonical request targets, queries, credentials and
request bodies fail closed. Forward no client headers/body or cookie/Location
responses; never follow redirects. Disable payload logs and on-disk buffering.

Synthetic mode has only two test Unix upstreams and a separate test CA. Public
mode accepts one strictly public IPv4 literal per fixed host, with no runtime DNS
or synthetic upstream fallback. Host administration must independently establish
the DNS snapshot/provenance: a public IP argument is not evidence of ownership.
IPv6 is intentionally unsupported in this initial transport probe.

Private run paths derive only from a canonical random-v4 UUID. Nonroot UID/GID,
private read-only roots, finite resource/time budgets and neutral generated
identifiers are required. Gateway IP properties are additional defense, not proof
that cgroup filtering is active. A worker's loopback namespace must actually be
connected only to the reviewed filesystem-socket relay; properties alone do not
establish that composition. No privileged endpoint/installer is added.

Probe curl arguments disable user configuration, proxies, redirects and DNS
selection; retain TLS verification, fixed paths and short deadlines. Output is
only bounded HTTP/TLS status. Require the actual successful process exit code;
reject malformed, duplicated, oversized, interim-only or failed TLS receipts,
while accepting non-200 final HTTP responses as transport evidence only.

Private operational plan remains separate: fresh preflight, exact own-object
manifest, negative filesystem/network/process tests, per-invocation stop receipts,
then own-object cleanup. Authorization, neutral real inference, API/history/chat
and any clinical model activation are later independent gates. Existing public
routes, runners and Android behavior remain unchanged.
