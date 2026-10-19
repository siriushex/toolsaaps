# Native Target Protocol Boundary

Target: unchanged official AAPS4.0.0-rc1 d5e7f5bd. Use its Client Control rather
than transplanting custom command receivers or changing dosing algorithms.

## Codec

AapsV3TargetProtocol uses JCA HMAC-SHA256 over exact native UTF-8 canonical
strings, constant-time comparison and a private copied key erased on close.
Request string representations redact their contents.

1. batch_prepare contains exactly one temp_target, known reason, finite mg/dl
   bounds, bounded duration and zero start offset. Duration0 explicitly cancels
   with zero bounds. There is no insulin/carbs/profile/running-mode factory.
2. Verify client, counter, signature, timestamp, fields and phase/status. Executing
   is not completion; a valid Done/Ok prepare is PREVIEW only. A target preview
   cannot carry wizard/advisor/dose data.
3. Commit references that exact preview ID with a fresh higher counter and valid
   preview. RC1 calls this shared message bolus_commit, but a target-only batch
   has no bolus: advisor is false and correction is zero. Only its correlated
   signed Done/Ok is classified APPLIED.

Commands expire after8s. ACK clock tolerance is5s; a late-arriving signed in-window
commit ACK may reconcile but cannot initiate another commit. Failed/Expired remain
distinct from unknown; unrelated Delivery/progress messages are not TT proof.
These wire bounds do not replace existing clinical guards.

Full local Android test/lint/build also passed on 2026-10-19: 6419 passed,
six prior private-input skips, zero test failures/errors and zero lint errors.
The 40109881-byte APK preserved all6874 compiled application classes. No APK
from this protocol stage has been installed or admitted for live target control.

## Verification And Admission

Ten codec cases and eight settings cases passed, plus23 store regressions.
Extended focused checks passed115 cases/one prior private-input skip. The real
SDK fixture ran: native DTO/crypto verifies our prepare, uploads a signed preview
through TLS/settings, then verifies our separately published zero-dose commit and
uploads a final fixture ACK. Clinical tables stay empty and runtime stays SETUP.
This is synthetic peer/wire proof, NOT execution of the master's domain handler
or a real therapy command.

The codec itself does not enroll a pairing or control Target Manager. The separate
Room37 journal provides durable counters, encrypted ownership/receipts and atomic
settings publication; see AAPS4_TARGET_JOURNAL.md. This is not a live controller
integration. Before live use: secure pairing/revocation, background
restart/timeout/unknown reconciliation, authenticated
storage events, clinical v3/source arbitration, Android TLS trust, actual CGM/pump
compatibility, settings export and device acceptance. Preserve armed/ownership/
freshness/safety checks before prepare and again before commit. Unknown published
commands must not be automatically re-sent or fall back to a legacy channel.
