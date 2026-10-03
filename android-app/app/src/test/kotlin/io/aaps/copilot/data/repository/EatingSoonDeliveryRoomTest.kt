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
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
