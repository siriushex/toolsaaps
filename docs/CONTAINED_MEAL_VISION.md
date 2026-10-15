# Contained Meal Vision Source

## Scope

This stage implements the pinned native image protocol and concrete trusted-host
transport. It does not deploy the public API, admit a model or enable a capability.
The owner API and text supervisor remain unchanged. No provider request, credential
access, phone action or clinical change is part of source verification.

## Fixed Image Path

`native_vision.py` accepts only one NormalizedMealImage with a bounded JPEG and
dimensions. The caller must normalize it independently. The exact public native
source is `3d2ee51ca2d5db578f328aa75e20aa22c0197c9a`, v2/turn.rs:
one fixed food-only text item plus `{type: image, url: data:image/jpeg;base64,...}`.
No localImage, remote URL, history, identifiers, arbitrary prompt, tool dispatch,
provider fallback or retry is accepted. Local job/device correlation never goes
into the model prompt. Nutrients and energy remain per 100 g, with uncertainty;
the returned schema is validated independently from the chat result schema.

The shared internal protocol checker owns strict envelope parsing, correlation,
known startup warning, notification/action rejection and exactly one completion.
Public NativeTextSession remains text-only with its original limits. Vision has
fixed limits of 1,500,000 bytes per send, 2 MiB per native response line, 8 MiB
per response stream, 2048 events and 64 KiB of food-result JSON. A native echo of
the input image must fit without widening the public CHAT contract.

`VisionRuntimeImage` verifies the complete operator-pinned image/binary and
restricted exact-model catalog, additionally requiring image modality. The
existing `work/text-model.json` path is the shared immutable runtime layout,
not an admission shortcut. Catalog restrictions disable tools but preserve the
selected model and reasoning metadata. A declared modality is not inference proof.

`SystemdVisionProcess` reuses the same trusted process lifecycle, fixed neutral
unit identity, input barrier, resource/kernel/mount/egress checks, profile lease
and original-invocation stop as text. It accepts only the vision image/policy and
uses its own fixed pipe limits. The host must pass the SAME SessionProfileGuard
binding as login/text. The transport returns a result only after confirmed stop,
pipe completion and a final deadline check. Unknown teardown retains admission.

## Shared Stop Fix

Previously the process adapter called ProfileLease.finish before draining native
IO. That could write `idle` before discovering a pipe/stderr failure. finish now
supports a trusted IO confirmation after exact cgroup stop and before inventory
and the idle write. A false, failed or cancelled confirmation retains `bound`.
Existing login/recovery callers without process IO retain their existing contract.
No journal reset, credential restoration or automatic uncertain-request replay.

## Verification And Rollout

Run test_ai_native_vision, test_ai_contained_vision, test_ai_native_text,
test_ai_contained_text_process and test_ai_session_profile, then full backend/tests
with pinned requirements including Pillow. Tests use synthetic frames/images and
actual local child pipes; privileged kernel/host admissions are substituted.
They are not actual Linux containment, external model or phone evidence.

Read-only inspection at 2026-10-15 17:57 UTC confirmed public capabilities still
return 503 ai_service_not_deployed. The host's
`/etc/nginx/sites-available/diai.centv.ru` explicitly returns this code at location
`/`; changing a capability response alone cannot deploy the owner API.

Before activation, finish and independently verify the OS-isolated decoder and
typed authenticated vision supervisor channel, immutable Linux image/active
network controls, shared profile binding, nonroot API/storage/key/retention and
restore checks. Only then run an explicitly scoped synthetic image model test
and signed client test. Do not route images through TextSupervisor, mount login
credentials into a decoder or mark photo ready based on these synthetic tests.
No service/proxy/profile changes or fake ready responses are performed here.
