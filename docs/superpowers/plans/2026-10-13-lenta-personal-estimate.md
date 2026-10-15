# Lenta Personal History Implementation

Scope: separate passive Estimate with immediate declared-meal/GI history. No
clinical effect, phone operations or unqualified CGM-trained GI claim.

- [x] Inspect existing hybrid, quantity prior, provenance and Room audit.
- [x] Amend selected Lenta specification for bounded history connection.
- [x] Add canonical completed-meal snapshot and batched confirmation lookup.
- [x] Add sparse quantity and saved-GI preview shaping with unchanged endpoint.
- [x] Add separate mode, full gram/minute chart and passive inspection.
- [x] Add domain, Room and native interaction regressions; update project docs.
- [x] Direct Kotlin/JUnit model check:6 cases pass against existing debug dependencies.
- [x] Initial focused Android run:76 cases/6 suites, no failures/errors/skips.
- [x] Inspect initial native snapshots and reproduce large-font hidden readout with a failing assertion.
- [ ] Run focused domain/source/native tests and inspect actual failures.
- [ ] Inspect corrected native portrait/dark/large-font chart screenshots and bounds.
- [ ] Run full Android unit, lint, compile and APK/signature gates.
- [ ] Review source/privacy, publish the authorized source-only CI stage and inspect exact-SHA CI.

GI calculation limit: available historical context cannot independently identify
food GI from CGM. This connects real saved-GI/declared portions, not a fabricated
CGM model. Reuse the existing type model when GI or matching history is missing.

Current gate limit: the runtime-only Kotlin DSL snapshot workaround and SDK36
AAPT2 passed resources, Room generation and app Kotlin/Java compilation. Unit
test compilation found three outdated DAO fakes; all three are updated and the
focused run is restarted. The user explicitly authorized source-only publication
for CI before the full local gate. Publication is not release or phone acceptance.

Commands: `./gradlew :app:testDebugUnitTest :app:lintDebug
:app:compileDebugKotlin :app:assembleDebug` from android-app. Private artifacts
stay outside source. Prior infusion changes must be included in regression gates
and reviewed separately before any release claim.
