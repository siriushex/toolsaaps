# Android Server Daily Archive Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task. Native subagent tools are unavailable. Continue the owner-approved daily-review design and Focus+ workspace; do not create an alternative design or activation path.

**Goal:** Read and retain same-device server daily reviews inside the existing Reports archive, without reinference or clinical publication.

**Architecture:** Reuse the signed HistoryService routes added at efa3b91a and the Android connection-owned proof/refresh transport. A separate device-bound encrypted cache retains a complete bounded summary index and one selected audit result. The existing local primary report, advice and therapy remain unchanged; unreviewed server results are a separately labelled audit surface.

**Tech Stack:** Existing Kotlin coroutines/Flow, strict HistoryJson, Keystore AES-GCM AtomicFile and native Compose/Robolectric. No new dependency, Room schema, provider, worker or server configuration.

## Contracts And Bounds

- Add only DAILY_RESULTS and DAILY_RESULT to ServerAiHistoryRoute, both1 KiB request/128 KiB response. HistoryClient methods use exact owner-held proof and expected-device binding, never job submission or caller headers.
- Closed page grammar/revision/membership, <=20 summaries, canonical UUIDs, strict numeric cursors, monotonic frozen sequence, fixed original day/zone expiry and unreviewed labels. Wrong IDs/unknown fields/duplicate keys/invalid UTF-8/clock or binding disagreement fail without partial adoption.
- Detail projects only dated audit metadata, qualitative summary/findings, limitations and independent current-vs-original source status. It never renders unsupported numeric cohort metrics as validated facts, converts actions or promotes NOT_ADMITTED to clinical approval. Full wire is retained privately, not interpreted as instructions.
- Complete summary index is bounded to512 entries/26 pages/1 MiB normalized summary payload/45 s; each response independently remains <=128 KiB. No source-history scan. Freeze the first page's sequence for every continuation. Commit only after all pages pass. Sort by declared report date and storage clock, not receipt-time physiology. Local UI shows20 rows at a time.
- Dedicated DAILY_ARCHIVE encrypted slot, independent alias/AAD/file and unchanged1 MiB storage ceiling. Cache envelope has version, device, fetchedAtMs, summaries and one detail. No credentials, keys, logs or clinical-table writes. Original expiry is not extended by refresh. Expired cached rows are not displayed.
- Load cache immediately on first archive use, then at most once per device-local05:00 cutoff while visible; failure backoff5 minutes in memory. Explicit refresh can retry reads, never model execution. No new background job or automatic upload. Device/session failure or changed key hides prior-device data. Complete cached data remains dated on network/parser/storage failure; no false new success.
- Selected details reuse the retained audit view in the same cutoff; explicit refresh obtains a new source check. Cached checks always disclose their original time, never a current-freshness badge. Changed/superseded/unavailable source is visibly distinct. No default-report or Recommendations merge.

## Task 1: Signed Client And Codec

Files: create `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/ServerDailyReview.kt`; modify `security/ServerAiHistoryPersistence.kt` and `data/repository/ServerAiHistoryClient.kt`; create `data/repository/ServerDailyReviewCodecTest.kt` and `ServerDailyReviewClientTest.kt` under test Kotlin.

- [x] Add REDs for `DailyReviewCodec.listQuery(DailyReviewQuery())`, exact page/result projection and actual signed client round trip. Include malformed/duplicate UTF-8/extra-field/UUID/sequence/source binding/expiry/admission-label cases.
- [x] Observe compilation or missing-method REDs, then add closed routes and internal `readDailyResults(query, expectedDeviceBinding)` / `readDailyResult(jobId, expectedDeviceBinding)` methods using existing guarded/exchange/response handling.
- [x] Implement typed audit DTOs and strict rendering-field validation. Bind requested job, report date/zone/revision, digests and both source checks; preserve all original labels. Do not infer missing domains or decode treatment actions.
- [x] Run focused codec/client plus existing history/connection/proof tests.

```kotlin
val query = DailyReviewQuery(afterSequence = 0, snapshotSequence = null)
val page = client.readDailyResults(query, expectedDeviceBinding = device)
check(page.records.size <= 20)
val selected = client.readDailyResult(page.records.first().jobId, device)
check(selected.jobId == page.records.first().jobId)
```

## Task 2: Retained Cache

Files: create `data/repository/ServerDailyReviewRepository.kt`; add DAILY_ARCHIVE storage slot; wire lazy instance in `service/AppContainer.kt`; create `ServerDailyReviewRepositoryTest.kt`.

- [x] Write REDs for restart/cache-first load, no repeated same-cutoff reads, complete pagination, backoff, selection and no partial replacement.
- [x] Implement StateFlow and serialized operations with injected binding/time/zone/read functions. `open()` restores then reads only if due; `refresh()` explicitly retries the index; `select(jobId)` loads only the chosen detail; `closeSelection()` changes display only.
- [x] Freeze membership and check uniqueness/device after every response and before durable adoption. Limit time/pages/bytes/rows; failures and cancellation preserve the last complete cache with dated checks. Fixed public failure enums omit exception text.
- [x] Test changed device, missing session, revocation, expiry, clock reversal, source changes, malformed cache, read/write failure, cancellation and simultaneous operations. No call may reach model/upload/therapy APIs.

## Task 3: Existing Reports Surface

Files: create `ui/foundation/screens/ServerDailyReviewArchive.kt`; minimally pass a server-archive composable slot through AiAnalysisScreen/AiFocusWorkspace/CopilotFoundationRoot; add English/Russian resources and `ServerDailyReviewArchiveTest.kt`.

- [x] Native REDs: compact archive row/date, full summary in selected dialog, visible unreviewed/source-check state, retained content during refresh/error, bounded More control and no recommendation/apply command.
- [x] AndroidViewModel uses the app-scoped repository and lifecycle collection. The native view renders plain text and existing icon controls, no HTML, remote media, fake progress or second owl.
- [x] The server block is distinct from existing local saved reports. A missing server route is unavailable, not proof that credentials are missing. Actual access refusal can open existing Settings; server text does not enter chat automatically.
- [x] Inspect native rasters on320x640 Russian and actual1.8x Android resource font scale, controls/scroll/dialog, no overlap/clipping. Preserve Focus+ primary report, four tabs and local advice behavior. Native assertions check the rendered text density and line bounds, not just a composition override.

## Task 4: Verification And Publication

- [x] Focused Android tests, then full unit/lint/compile/assemble with fixed source inputs. Current38-case gate and full6168 cases passed (6162 successful, six existing skips). Zero errors/new lint issues; existing376 warnings/eight hints. Actual synthetic backend projection is readable without schema relaxation.
- [x] Inspect terminal test counts and native rasters; review source/privacy/identifiers and update OPEN_FEATURES plus mandatory project docs/AI_NOTES. Native1.8x test includes long summary/five findings and actual scroll. Source/Gradle input manifests before/after the final gate match. New source does not prove overnight/provider/device acceptance.
- Source publication is a post-commit gate: fetch/check divergence, explicitly stage reviewed paths, commit/push existing PR7 and inspect exact-head Verify. Record the actual outcome in a source-only PR receipt after terminal success. No phone installation or server admission is implied.

Commands use the existing Java17/SDK36 environment: `rtk proxy ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.data.repository.ServerDailyReview*Test' --tests 'io.aaps.copilot.ui.foundation.screens.ServerDailyReviewArchiveTest' --no-daemon --max-workers=2 --console=plain`, then `rtk proxy ./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug --no-daemon --max-workers=2 --console=plain` in android-app. Retain logs and native images only in this task's private artifact directory.
