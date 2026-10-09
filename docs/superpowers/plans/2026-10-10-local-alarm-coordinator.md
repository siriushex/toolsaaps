# Inactive Serialized Alarm Coordinator

Scope: one event-driven owner combining existing read-only arbitration and
cycle execution. No production source, service, capability inference, opt-in,
notification/vibration/wake/native fallback, device or therapy activation.

1. RED tests for committed ownership and cleanup reporting, including cancellation.
   Add exact interrupted-claim metadata cleanup without speculative START and a
   separate per-database runtime mutex that never occupies the global OFF lock.
2. Implement one explicitly run coordinator, mandatory current immutable accepted
   context and one conflated wake channel/nearest deadline. Invalid current
   source/OFF/capabilities synchronously revoke guard admission and cancel the
   owned job; serialized cancel/join precedes replacement claims.
3. Test priority/fairness, duplicate stability, starting/cancellation races,
   abandoned claims/no replay, nearest expiry/repeat, unavailable cleanup/storage,
   stopped/competing runtime owners and no production construction.
4. Inline review and focused/full Android unit/lint/compile/debug build; document
   contracts and remaining source/lifecycle/channel/UI/device release gates.
5. Publish explicit reviewed paths to the existing branch/draft PR, inspect
   exact-source Verify. Never call a synthetic pass operational night delivery.
