package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.service.ApiFactory
import io.aaps.copilot.service.hasActiveManualTempTargetStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EatingSoonDeliveryRoomTest {
    @Test fun restartCannotTurnAcceptedSuggestionIntoIndependentTrainingLabel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val firstRepository = repository(server)
            var profiles = 0
            fun submission(repository: NightscoutActionRepository) = ManualMealSubmission(
                repository::submitCarbs, { _, _, _ -> profiles++ }, { error("target not requested") },
                readCarbBlockReason = repository::manualCarbBlockReason
            )
            val meal = command("origin-restart").copy(type = "carbs",
                params = mapOf("carbsGrams" to "20.0"), idempotencyKey = "manual:meal:origin-restart")
            val accepted = io.aaps.copilot.domain.profile.MealAbsorptionSelection(
                io.aaps.copilot.domain.profile.MealAbsorptionProfile.MIXED,
                portionMetadata = io.aaps.copilot.domain.nutrition.MealPortionMetadata(
                    io.aaps.copilot.domain.nutrition.MealPortion.MEDIUM,
                    io.aaps.copilot.domain.nutrition.MealPortionProvenance.ACCEPTED_SUGGESTION))
            assertThat(submission(firstRepository).submit(meal, accepted, null, false).carbs)
                .isEqualTo(MealDeliveryStatus.SENT)
            val stored = db.actionCommandDao().byIdempotencyKey(meal.idempotencyKey)
            val restarted = repository(server, reuseSettings = true)
            val corrected = accepted.copy(portionMetadata = accepted.portionMetadata!!.copy(
                provenance = io.aaps.copilot.domain.nutrition.MealPortionProvenance.USER_CORRECTED))
            val rejected = submission(restarted).submit(meal, corrected, null, false)
            assertThat(rejected.carbs).isEqualTo(MealDeliveryStatus.BLOCKED)
            assertThat(rejected.carbBlockReason).isEqualTo("submission_changed")
            assertThat(db.actionCommandDao().byIdempotencyKey(meal.idempotencyKey)).isEqualTo(stored)
            assertThat(profiles).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(submission(restarted).submit(meal, accepted, null, false).carbs)
                .isEqualTo(MealDeliveryStatus.SENT)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun restartCannotAcknowledgeChangedQuantityUnderAlreadySentMealId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val firstRepository = repository(server)
            val original = command("immutable-restart").copy(type = "carbs",
                params = mapOf("carbsGrams" to "20.0"), idempotencyKey = "manual:meal:immutable-restart")
            assertThat(firstRepository.submitCarbs(original)).isTrue()
            val originalRow = db.actionCommandDao().byIdempotencyKey(original.idempotencyKey)
            val restarted = repository(server, reuseSettings = true)
            val changed = original.copy(params = mapOf("carbsGrams" to "40.0"))
            assertThat(restarted.submitCarbs(changed)).isFalse()
            assertThat(restarted.manualCarbBlockReason(changed)).isEqualTo("submission_changed")
            assertThat(db.actionCommandDao().byIdempotencyKey(original.idempotencyKey)).isEqualTo(originalRow)
            assertThat(restarted.submitCarbs(original)).isTrue()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun manualEightyGramsPostsExactlyOnceWithoutChangingAutomaticCap() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            settings.update { it.copy(carbComputationMaxGrams = 20.0) }
            val meal = command("eighty-grams").copy(type = "carbs",
                params = mapOf("carbsGrams" to "80.0"), idempotencyKey = "manual:meal:eighty-grams")
            assertThat(repository.submitCarbs(meal)).isTrue()
            val body = Gson().fromJson(server.takeRequest().body.readUtf8(), Map::class.java)
            assertThat(body["carbs"]).isEqualTo(80.0)
            assertThat(repository.submitCarbs(meal)).isTrue()
            assertThat(server.requestCount).isEqualTo(1)
            val automatic = meal.copy(id = "automatic-eighty-grams", idempotencyKey = "automatic:eighty-grams")
            assertThat(repository.submitCarbs(automatic)).isFalse()
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(db.actionCommandDao().byIdempotencyKey(automatic.idempotencyKey)?.status)
                .isEqualTo("FAILED")
        }
    }

    @Test fun invalidManualGramsNeverReachTransportOrBecomeSmallerMeals() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            listOf("80.01", "NaN", "Infinity", "-Infinity", "0", "-1").forEachIndexed { i, grams ->
                val meal = command("invalid-$i").copy(type = "carbs",
                    params = mapOf("carbsGrams" to grams), idempotencyKey = "manual:meal:invalid-$i")
                assertThat(repository.submitCarbs(meal)).isFalse()
                assertThat(db.actionCommandDao().byIdempotencyKey(meal.idempotencyKey)?.status)
                    .isEqualTo("FAILED")
            }
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun manualCarbRateLimitRetainsReasonAndDoesNotPost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            val first = command("carbs-first").copy(type = "carbs", params = mapOf("carbsGrams" to "10"))
            val second = command("carbs-second").copy(type = "carbs", params = mapOf("carbsGrams" to "10"))
            assertThat(repository.submitCarbs(first)).isTrue()
            assertThat(repository.submitCarbs(second)).isFalse()
            assertThat(repository.manualCarbBlockReason(second)).isEqualTo("carbs_rate_limit_30m")
            assertThat(repository.manualCarbBlockReason(first)).isNull()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CopilotDatabase
    private lateinit var settings: AppSettingsStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test fun queuedEatingSoonIsDurableBeforeWaitingAndBlocksQueuedManager() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manual = command("priority-queued")
            fun automatic(suffix: String, target: String) = command(suffix).copy(
                params = mapOf("targetMmol" to target, "durationMinutes" to "30", "reason" to "normal_control"),
                idempotencyKey = "TargetManager.v1:$suffix"
            )
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitTempTarget(automatic("priority-in-flight", "5.5")) {
                    held.complete(Unit)
                    release.await()
                    null
                }
            }
            withTimeout(5_000) { held.await() }
            val queuedManager = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitTempTarget(automatic("priority-manager", "7.0")) {
                    val pending = db.actionCommandDao().byTypeAndIdempotencyPrefixSince(
                        "temp_target", "manual:%", 0L)
                    if (hasActiveManualTempTargetStatic(pending, System.currentTimeMillis(), Gson())) {
                        "manual_target_active_or_pending"
                    } else null
                }
            }
            val queuedManual = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitManualMealTarget(manual) { null }
            }
            try {
                val pending = withTimeout(5_000) {
                    db.actionCommandDao().observeLatest(10).first { rows ->
                        rows.any { it.idempotencyKey == manual.idempotencyKey && it.status == "PENDING" }
                    }
                }
                assertThat(pending.any { it.idempotencyKey == manual.idempotencyKey }).isTrue()
                assertThat(server.requestCount).isEqualTo(0)
                release.complete(Unit)
                assertThat(withTimeout(5_000) { first.await() }).isTrue()
                assertThat(withTimeout(5_000) { queuedManager.await() }).isFalse()
                assertThat(withTimeout(5_000) { queuedManual.await() }.status).isEqualTo(MealDeliveryStatus.SENT)
                assertThat(server.requestCount).isEqualTo(2)
                val firstBody = Gson().fromJson(server.takeRequest().body.readUtf8(), Map::class.java)
                val manualBody = Gson().fromJson(server.takeRequest().body.readUtf8(), Map::class.java)
                assertThat(firstBody["notes"]).isEqualTo("copilot:TargetManager.v1:priority-in-flight")
                assertThat(manualBody["notes"]).isEqualTo("copilot:${manual.idempotencyKey}")
            } finally {
                release.complete(Unit)
                queuedManual.cancelAndJoin()
                queuedManager.cancelAndJoin()
                first.cancelAndJoin()
            }
        }
    }

    @Test fun cancelledEatingSoonBeforeQueueAdmissionIsKnownNotSent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manual = command("priority-cancelled")
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitTempTarget(command("queue-owner")) {
                    held.complete(Unit)
                    release.await()
                    null
                }
            }
            withTimeout(5_000) { held.await() }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitManualMealTarget(manual) { error("cancelled request must not run guard") }
            }
            try {
                withTimeout(5_000) {
                    db.actionCommandDao().observeLatest(10).first { rows ->
                        rows.any { it.idempotencyKey == manual.idempotencyKey && it.status == "PENDING" }
                    }
                }
                waiting.cancelAndJoin()
                val persisted = checkNotNull(db.actionCommandDao().byIdempotencyKey(manual.idempotencyKey))
                assertThat(persisted.status).isEqualTo("BLOCKED")
                assertThat(hasActiveManualTempTargetStatic(listOf(persisted), System.currentTimeMillis(), Gson())).isFalse()
                assertThat(server.requestCount).isEqualTo(0)
                release.complete(Unit)
                assertThat(withTimeout(5_000) { first.await() }).isTrue()
                assertThat(server.requestCount).isEqualTo(1)
            } finally {
                release.complete(Unit)
                waiting.cancelAndJoin()
                first.cancelAndJoin()
            }
        }
    }

    @Test fun manualEatingSoonEntryRejectsNonCanonicalPayloadBeforeReservation() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            val changed = command("priority-changed").copy(params = mapOf(
                "targetMmol" to "6.0", "durationMinutes" to "30", "reason" to "Eating Soon"))
            val result = repository.submitManualMealTarget(changed) { "glucose_too_low" }
            assertThat(result.status).isEqualTo(MealDeliveryStatus.BLOCKED)
            assertThat(result.reason).isEqualTo("invalid_meal_identity")
            assertThat(db.actionCommandDao().byIdempotencyKey(changed.idempotencyKey)).isNull()
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun cancellingQueuedDuplicateCannotWithdrawOriginalEatingSoonReservation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manual = command("priority-duplicate")
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitTempTarget(command("duplicate-owner")) {
                    held.complete(Unit)
                    release.await()
                    null
                }
            }
            withTimeout(5_000) { held.await() }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitManualMealTarget(manual) { null }
            }
            try {
                withTimeout(5_000) {
                    db.actionCommandDao().observeLatest(10).first { rows ->
                        rows.any { it.idempotencyKey == manual.idempotencyKey && it.status == "PENDING" }
                    }
                }
                val original = db.actionCommandDao().byIdempotencyKey(manual.idempotencyKey)
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) {
                    repository.submitManualMealTarget(manual) { error("duplicate must not deliver") }
                }
                duplicate.cancelAndJoin()
                assertThat(db.actionCommandDao().byIdempotencyKey(manual.idempotencyKey)).isEqualTo(original)
                release.complete(Unit)
                assertThat(withTimeout(5_000) { first.await() }).isTrue()
                assertThat(withTimeout(5_000) { waiting.await() }.status).isEqualTo(MealDeliveryStatus.SENT)
                assertThat(repository.submitManualMealTarget(manual) { error("sent duplicate must not run guard") }.status)
                    .isEqualTo(MealDeliveryStatus.SENT)
                assertThat(server.requestCount).isEqualTo(2)
            } finally {
                release.complete(Unit)
                waiting.cancelAndJoin()
                first.cancelAndJoin()
            }
        }
    }

    @Test fun queuedEatingSoonCannotChangeAfterCallerMutatesBorrowedParams() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val params = command("priority-frozen").params.toMutableMap()
            val manual = command("priority-frozen").copy(params = params)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitTempTarget(command("frozen-owner")) {
                    held.complete(Unit)
                    release.await()
                    null
                }
            }
            withTimeout(5_000) { held.await() }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitManualMealTarget(manual) { null }
            }
            try {
                withTimeout(5_000) {
                    db.actionCommandDao().observeLatest(10).first { rows ->
                        rows.any { it.idempotencyKey == manual.idempotencyKey && it.status == "PENDING" }
                    }
                }
                params["targetMmol"] = "6.0"
                params["durationMinutes"] = "120"
                release.complete(Unit)
                assertThat(withTimeout(5_000) { first.await() }).isTrue()
                assertThat(withTimeout(5_000) { waiting.await() }.status).isEqualTo(MealDeliveryStatus.SENT)
                server.takeRequest()
                val body = Gson().fromJson(server.takeRequest().body.readUtf8(), Map::class.java)
                assertThat(body["targetBottom"]).isEqualTo(74.0)
                assertThat(body["duration"]).isEqualTo(30.0)
                val persisted = checkNotNull(db.actionCommandDao().byIdempotencyKey(manual.idempotencyKey))
                assertThat(isCanonicalEatingSoonCommand(persisted, Gson())).isTrue()
                assertThat(server.requestCount).isEqualTo(2)
            } finally {
                release.complete(Unit)
                waiting.cancelAndJoin()
                first.cancelAndJoin()
            }
        }
    }

    @Test fun cancellingEatingSoonAfterHttpStartedPreservesUnknownAndNeverReplays() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val repository = repository(server)
            val manual = command("priority-http-cancelled")
            val sending = async(start = CoroutineStart.UNDISPATCHED) {
                repository.submitManualMealTarget(manual) { null }
            }
            try {
                val posted = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
                assertThat(posted).isNotNull()
                sending.cancelAndJoin()
                assertThat(db.actionCommandDao().byIdempotencyKey(manual.idempotencyKey)?.status).isEqualTo("PENDING")
                val duplicate = repository.submitManualMealTarget(manual) { error("uncertain request must not replay") }
                assertThat(duplicate.status).isEqualTo(MealDeliveryStatus.UNKNOWN)
                assertThat(server.requestCount).isEqualTo(1)
            } finally {
                sending.cancelAndJoin()
            }
        }
    }

    @Test fun managerRetryEntryPointCannotReplayUnknownEatingSoon() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            val manual = command("priority-no-retry")
            assertThat(repository.submitManualMealTarget(manual) { null }.status)
                .isEqualTo(MealDeliveryStatus.UNKNOWN)
            assertThat(repository.submitOrRetryTempTarget(manual) { null }).isFalse()
            assertThat(db.actionCommandDao().byIdempotencyKey(manual.idempotencyKey)?.status).isEqualTo("PENDING")
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun eatingSoonShapedKeyWithDifferentPayloadCannotUseFirstRefusalException() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            val changes = listOf(
                "targetMmol" to "4.2",
                "durationMinutes" to "45",
                "reason" to "Other manual target"
            )
            changes.forEachIndexed { index, changed ->
                val original = command("different-payload-$index")
                val command = original.copy(params = original.params + changed)
                assertThat(repository.submitTempTarget(command) { "glucose_too_low" }).isFalse()
                val persisted = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)!!
                assertThat(persisted.status).isEqualTo("FAILED")
                assertThat(hasActiveManualTempTargetStatic(listOf(persisted), persisted.timestamp, Gson())).isTrue()
            }
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun firstAllowlistedGuardRefusalIsBlockedWithoutManualOwnershipHold() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            val command = command("blocked")
            var guards = 0
            val sent = repository.submitTempTarget(command) { guards++; "glucose_too_low" }
            assertThat(sent).isFalse()
            assertThat(guards).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(0)
            val persisted = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)!!
            assertThat(persisted.status).isEqualTo("BLOCKED")
            assertThat(persisted.payloadJson).contains("glucose_too_low")
            assertThat(persisted.payloadJson).doesNotContain("managed_preflight")
            assertThat(
                hasActiveManualTempTargetStatic(
                    commands = listOf(persisted),
                    now = persisted.timestamp,
                    gson = Gson()
                )
            ).isFalse()
            assertThat(repository.submitTempTarget(command) { error("duplicate guard") }).isFalse()
            val duplicate = repository.submitManualMealTarget(command) { error("blocked request must not retry") }
            assertThat(duplicate.reason).isEqualTo("glucose_too_low")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun acceptedGuardPostsRequestedTargetAndDuplicateDoesNotPostAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val repository = repository(server)
            val command = command("sent")
            assertThat(repository.submitTempTarget(command) { null }).isTrue()
            val body = Gson().fromJson(server.takeRequest().body.readUtf8(), Map::class.java)
            assertThat(body["duration"]).isEqualTo(30.0)
            assertThat(body["reason"]).isEqualTo("Eating Soon")
            assertThat(body["targetBottom"]).isEqualTo(74.0)
            assertThat(body["targetTop"]).isEqualTo(74.0)
            assertThat(repository.submitTempTarget(command) { error("already sent") }).isTrue()
            settings.setTherapyActionsArmed(false)
            assertThat(repository.submitManualMealTarget(command) { error("already sent") }.status)
                .isEqualTo(MealDeliveryStatus.SENT)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun failedTargetKeepsOriginalReasonAndRecordAfterDisarming() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            val command = command("failed-then-disarmed")
            val first = repository.submitManualMealTarget(command) { "glucose_too_low" }
            assertThat(first.reason).isEqualTo("glucose_too_low")
            val original = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)
            settings.setTherapyActionsArmed(false)

            val duplicate = repository.submitManualMealTarget(command) { error("duplicate must not run guard") }

            assertThat(duplicate).isEqualTo(first)
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)).isEqualTo(original)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun unknownHttpResultDoesNotRetryOrInvokeLocalFallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)
            val command = command("unknown")
            var unknown = false
            try {
                repository.submitTempTarget(command) { null }
            } catch (_: NightscoutActionRepository.TempTargetDeliveryUnknownException) {
                unknown = true
            }
            assertThat(unknown).isTrue()
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status).isEqualTo("PENDING")
            assertThat(repository.submitTempTarget(command) { error("unknown delivery must not retry") }).isFalse()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun ambiguousMealCarbsRemainPendingAndNeverTriggerTarget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)
            var targets = 0
            val submission = ManualMealSubmission(repository::submitCarbs, { _, _, _ -> }, {
                targets++
                EatingSoonResult(MealDeliveryStatus.SENT)
            })
            val meal = command("carb-unknown").copy(type = "carbs",
                params = mapOf("carbsGrams" to "10", "reason" to "manual_ui_carbs"),
                idempotencyKey = "manual:meal:carb-unknown")
            val result = submission.submit(meal,
                io.aaps.copilot.domain.profile.MealAbsorptionSelection(
                    io.aaps.copilot.domain.profile.MealAbsorptionProfile.MIXED), null, true)
            assertThat(result.carbs).isEqualTo(MealDeliveryStatus.UNKNOWN)
            assertThat(targets).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(db.actionCommandDao().byIdempotencyKey(meal.idempotencyKey)?.status).isEqualTo("PENDING")
            assertThat(repository.submitCarbs(meal)).isFalse()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun disarmedTargetIsKnownNotSentInsteadOfUnknown() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            settings.setTherapyActionsArmed(false)
            val result = repository.submitManualMealTarget(command("disarmed")) { error("must stop before guard") }
            assertThat(result.status).isEqualTo(MealDeliveryStatus.BLOCKED)
            assertThat(result.reason).isEqualTo("actions_disarmed")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun missingTargetEndpointIsKnownNotSentInsteadOfUnknown() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            settings.update { it.copy(nightscoutUrl = "", localNightscoutEnabled = false) }
            val result = repository.submitManualMealTarget(command("no-endpoint")) { null }
            assertThat(result.status).isEqualTo(MealDeliveryStatus.BLOCKED)
            assertThat(result.reason).isEqualTo("missing_nightscout_url")
            val persisted = db.actionCommandDao().byIdempotencyKey(command("no-endpoint").idempotencyKey)!!
            assertThat(persisted.status).isEqualTo("BLOCKED")
            assertThat(hasActiveManualTempTargetStatic(listOf(persisted), persisted.timestamp, Gson())).isFalse()
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun ambiguousTargetStaysUnknownWhenDuplicateArrivesAfterDisarming() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)
            val command = command("disarmed-unknown")
            try {
                repository.submitTempTarget(command) { null }
            } catch (_: NightscoutActionRepository.TempTargetDeliveryUnknownException) {
                // The original POST may have committed remotely.
            }
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status).isEqualTo("PENDING")
            settings.setTherapyActionsArmed(false)
            val duplicate = repository.submitManualMealTarget(command) { error("never retry") }
            assertThat(duplicate.status).isEqualTo(MealDeliveryStatus.UNKNOWN)
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status).isEqualTo("PENDING")
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun ambiguousCarbsStayPendingWhenDuplicateArrivesAfterDisarming() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)
            val meal = command("disarmed-carb").copy(type = "carbs",
                params = mapOf("carbsGrams" to "10"), idempotencyKey = "manual:meal:disarmed-carb")
            assertThat(repository.submitCarbs(meal)).isFalse()
            settings.setTherapyActionsArmed(false)
            assertThat(repository.submitCarbs(meal)).isFalse()
            assertThat(db.actionCommandDao().byIdempotencyKey(meal.idempotencyKey)?.status).isEqualTo("PENDING")
            settings.setTherapyActionsArmed(true)
            assertThat(repository.submitCarbs(meal)).isFalse()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun typedTargetResultMapsUncertainHttpResponseToUnknown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)
            val result = repository.submitManualMealTarget(command("typed-unknown")) { null }
            assertThat(result.status).isEqualTo(MealDeliveryStatus.UNKNOWN)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun unknownGuardDetailsAreNotCopiedIntoDurableDiagnosticCode() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            val command = command("private-reason")
            val result = repository.submitManualMealTarget(command) { "https://private.invalid/?token=test-secret" }
            assertThat(result.status).isEqualTo(MealDeliveryStatus.BLOCKED)
            assertThat(result.reason).isEqualTo("target_not_sent")
            val payload = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)!!.payloadJson
            assertThat(payload).doesNotContain("private.invalid")
            assertThat(payload).doesNotContain("test-secret")
            assertThat(db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status)
                .isEqualTo("FAILED")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    private suspend fun repository(server: MockWebServer, reuseSettings: Boolean = false): NightscoutActionRepository {
        if (!reuseSettings) settings = AppSettingsStore(context)
        settings.update {
            it.copy(nightscoutUrl = server.url("/").toString(), localNightscoutEnabled = false,
                localCommandFallbackEnabled = true, localNightscoutLegacyMigrationAcknowledged = true)
        }
        settings.setTherapyActionsArmed(true)
        return NightscoutActionRepository(
            context = context, db = db, settingsStore = settings, apiFactory = ApiFactory(),
            carbsSendThrottle = CarbsSendThrottle(db.actionCommandDao()),
            tempTargetSendThrottle = TempTargetSendThrottle(db.actionCommandDao()),
            gson = Gson(), auditLogger = AuditLogger(db.auditLogDao(), Gson())
        )
    }

    private fun command(suffix: String) = ActionCommand(
        id = "meal-$suffix", type = "temp_target",
        params = mapOf("targetMmol" to "4.1", "durationMinutes" to "30", "reason" to "Eating Soon"),
        safetySnapshot = SafetySnapshot(false, true, null, 0),
        idempotencyKey = "manual:meal:$suffix:eating-soon"
    )
}
