# Checked Pump-Link Source Publication

## Scope

First accepted-source producer stage after the inactive coordinator. Extend the
existing monitor with immutable current technical evidence and committed global
OFF/resume cleanup. Do not manufacture LocalAlarmEvidence generations/expiry,
change technical/clinical policy, activate a consumer or touch a phone.

## Implementation

1. Add synthetic monitor regressions before the API: exact durable provenance,
   duplicate rejection, superseding writes, failure/cancellation, recovery,
   intentional disconnect, unknown data, boot/time and policy deadline.
2. Publish only after existing durable writes; clear before superseding accepted
   evaluation. Reuse existing canonical condition and nearest-deadline timer.
3. Provide synchronous current read with exact-publication recheck after clock
   callbacks. Flow alone is metadata, not hardware admission. Missing-heartbeat
   absence is technical evidence only; invent neither TTL nor worn-pump proof.
4. Withdraw during existing committed global OFF/resume/overview cleanup before
   legacy player cleanup, without lock reentry. Test real Room ordering, expiry,
   old coordination failure, late commit and restart; retain no-runtime guards.
5. Run focused/full Android checks, review source/private-data boundaries and
   update documentation. Publish reviewed source to existing feature branch/PR;
   confirm exact pushed SHA and Verify. No merge, installation or activation.

## Verification

- Focused: PumpLinkAlarmSourceTest, PumpLinkHealthMonitorTest,
  PumpLinkHealthPolicyTest, DataStorePumpLinkRecordStoreTest,
  EpisodeAlertDeliveryRoomTest, GlucoseAlertLocalPlaybackTest,
  AndroidAlarmVolumePortTest.
- Full: testDebugUnitTest, lintDebug, compileDebugKotlin, assembleDebug and fresh
  lintReportDebug XML with bounded workers/Java17. Optional phone-copy skips
  remain explicit; source CI does not prove locked-screen delivery or resources.
- Future integration must supply glucose/diagnostic sources, complete stable
  alarm key/generation/queue-age mapping, pre-lock OFF-intent revocation,
  capability/opt-in admission, service/channels/UI and real-device acceptance.
