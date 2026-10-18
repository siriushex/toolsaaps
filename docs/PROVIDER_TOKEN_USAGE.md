# Provider Token Usage

## Decision (2026-10-18)

Keep the accepted model and existing ChatGPT login. No separate API key,
model substitution, guessed tokenizer mapping or new provider request is used.
An isolated inspection of public tiktoken 0.14.0 found no exact or prefix mapping
for the selected gpt-6-astra model. Installing an unrelated encoding would not
make the native request count exact.

OpenAI documents a separate Responses input-token counting endpoint. It counts
the complete supplied API request, not an unknown native runtime envelope, and
is not connected by this stage:

- https://developers.openai.com/api/docs/guides/token-counting
- https://developers.openai.com/api/reference/resources/responses/subresources/input_tokens/methods/count
- https://github.com/openai/tiktoken/blob/main/tiktoken/model.py

## Source Evidence

Pinned upstream revision `3d2ee51ca2d5db578f328aa75e20aa22c0197c9a` exposes
`rawResponse/completed`: thread/turn/response identity, optional provider usage
and optional metadata. The v2 protocol describes this as usage of one upstream
response. Ordinary `thread/tokenUsage/updated` is not an equivalent source:
the runtime also has synthetic context-window accounting paths.

The adapter accepts only the pinned raw notification schema, correlates thread
and turn, hashes response identity, and discards usageMetadata. Six counters are
retained: input, cached input, cache-write input, output, reasoning output and
total. Missing usage remains null. A missing event means no usage receipt, not
zero use. Identical response events are deduplicated; conflicting duplicates,
invalid integers/relationships and more than 16 distinct responses fail closed.

## End-To-End Contract

`NativeTextRunner` produces an immutable bounded `UsageReceipt` only after the
native turn completes successfully. It binds model and SHA-256 digests of the
submitted UTF-8 input and instructions. Counts remain per upstream response;
there is no sum presented as the current context occupancy or billing amount.

The local text supervisor returns `usageReceipt` only for a successful turn
after independent stop confirmation. The API client rechecks exact input/model/
instructions binding. ContextualChatWorker checks the same binding again.
AiJobService forwards it only after the executor settles a successful job.
HistoryStore adds `providerUsage` inside that device's existing encrypted chat
entry, atomically with the answer. Compact entries additionally require the
input digest to equal the immutable context manifest's frameDigest.

The optional receipt is at most 8192 bytes and contains neither raw provider IDs
nor arbitrary metadata. Existing archive quota and retention remain unchanged.
Old records remain readable, absent usage is not reconstructed, repeated final
writes preserve existing usage, and conflicting final receipts are rejected.
It is not exposed in public ChatResult or included in model conversation input.

## Compatibility And Deployment Boundary

Only local text requests opt in with
`usageReceiptRevision=provider-token-usage-v1`. New supervisors accept old text
clients without returning additional fields. Unknown revisions fail before a
process is started. New clients do not retry against old supervisors. A future
separately reviewed rollout must upgrade the text supervisor before the API;
rollback must restore the API before the supervisor, in an idle window. No job
replay, history replacement, token restore or guard reset is part of rollback.

Daily-review and photo response contracts do not opt in. Their shared native
notification parser accepts the pinned usage event, but they do not transport
or archive this new receipt. No installed descriptor, service, profile, phone,
APK, public API, safety policy or therapy changes are included here.

## Limits And Verification

This is provider-reported post-response accounting, not an exact preflight
tokenizer. Input binding proves which application frame was submitted, not that
all raw history fit, that native processing retained every token, or that the
answer used every observation. Existing coverage/omission notices and preflight
limits remain mandatory; usage does not enlarge limits or activate compact CHAT.
No token/cost savings, provider latency or clinical accuracy is measured here.

Synthetic tests cover raw-event correlation, unknown versus zero, duplicate
handling, numeric limits, immutable receipt binding, real local Unix transport,
confirmed stop, legacy compatibility, encrypted device isolation, archival
conflicts and a signed API job that persists usage without changing ChatResult.
Full backend and exact-head CI results are recorded in AI_NOTES.md.
