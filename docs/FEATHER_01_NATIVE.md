# Feather 01 Native Integration

Date: 2026-10-15. User-selected friendly feathered owl; presentation only.

## Integration Boundary

Before the presentation patch, the isolated main reconciliation's Android tree
exactly matched a74006dd. That incoming SHA passed Verify 38720511186 and 38720503263.
Main's duplicate sustained-rise source/tests matched it; TargetManager resolves
to that exact verified version, not a new combined algorithm. Unfinished owner-AI
work in another working tree is excluded. No phone/server update is performed.

## Native Surfaces

- Adaptive launcher with safe-zone padding and themed monochrome variant.
- 32dp context owl occupies only the compact overview's empty title slot.
  Secondary headings retain their original space and actions.
- 24dp waiting gaze follows the actual AI busy state; its cap freezes artwork,
  never reports request success. Indeterminate accessibility semantics remain
  after the cap or reduced motion. Four other short gestures are available.
- Twelve existing untinted event images retain names, 128px lossless WebP and
  512px masters. First nonurgent selected event gets at most one finite accent.
- 52 native glyph records: 40 reviewed notification scenarios plus 12 event types.
  Existing alert stages use corresponding glyphs. Unsupported runtime detection
  scenarios are not invented or activated by adding an artwork recipe.
- Android notifications remain static with recognisable risk symbols and
  separate owl artwork. Public content contains neither values nor color band.
- The existing optional gradient no longer runs forever; it shares the bounded
  foreground, system reduced-motion and device/app power-saving lifecycle.

## Cost And Verification

Cached paths/brushes; frame state read during drawing, not clinical composition.
One system settings/battery observer per root. Tiny marks skip texture decoding.
Optional large texture 192x192, bounded notification cache 7 images of 96x96. These
are design bounds, not measured RAM, CPU, battery or frame-time claims.

Native vectors are an optical-size adaptation, not an identical photorealistic
3D render. Reviewed portable sources, provenance and Lucide license are retained;
the Android package also includes the license in raw resources.

With development modules sharp/lucide available to Node, run
`node tools/generate_feather_artwork.cjs`. No network or clinical data is required.
Android gates: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
Set `FEATHER_UI_EVIDENCE_DIR` for native Robolectric PNGs. These are not physical
phone screenshots. Existing header/event/alert tests stay in the full suite.
Final gate outcomes belong in AI_NOTES.md; pending work is not release evidence.

Local verification included an immutable complete 5,541-test regression with
zero failures and 6 existing private-fixture skips. The final waiting-role change
then passed 35 targeted native/header/artwork/notification tests plus 10 event-detail
tests; lint and APK rebuild passed on the unchanged final code. Lint reports
355 warnings and 8 hints, zero errors; 32 unused static catalog glyphs deliberately
have no runtime detector. No lint checks were disabled for this stage.

The final source commit must pass Verify before main integration. Phone data
migration, real TalkBack output, notification delivery and physical performance
remain separate device checks; no phone or server installation occurs here.
