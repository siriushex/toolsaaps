# Daily Result Archive

Scope: internal backend source only. No provider, schedule, server, device or
therapy activation. Existing model/reasoning and publication gates stay unchanged.

1. Reproduce absent durable result lifecycle with synthetic tests.
2. Add a protected immutable result table through explicit additive schema 5,
   distinct AEAD purpose, shared quota/retention and bounded reads.
3. Compose the existing exact daily worker and JobExecutor; only confirmed-stop
   SUCCEEDED outcomes reach the private writer. Do not accept a caller outcome
   as stop proof. A settled job without a committed result never re-infers.
4. Store original source-check and work/result provenance. Selected reads replay
   current retained source in the same read snapshot; list summaries explicitly
   remain unchecked. Unreviewed text is always NOT_ADMITTED.
5. Cover restart/retry, crash windows, cancellation, unknown stop, isolation,
   tampering, limits, expiry, supersession and late source changes. Run complete
   backend, scoped lint/format/compile, then review source/security/docs.
6. Publish explicit reviewed source paths on the existing branch/PR; inspect
   exact-head Verify without merging, releasing, deploying or installing.

Two independent databases cannot commit atomically. Crash after ledger success
but before result commit can lose the reply; preserve consumed handoff/job and
report missing result, never retry the model. A committed write with lost
acknowledgement is recovered by immutable job identity. This is not exactly-once
inference, forensic rollback protection, medical approval or public admission.
