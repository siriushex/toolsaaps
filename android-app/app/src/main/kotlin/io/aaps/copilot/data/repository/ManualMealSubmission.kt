package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MealDeliveryStatus { NOT_REQUESTED, SENT, BLOCKED, UNKNOWN }

data class EatingSoonResult(val status: MealDeliveryStatus, val reason: String? = null)

data class ManualMealResult(
    val carbs: MealDeliveryStatus,
    val eatingSoon: EatingSoonResult,
    val profileSaved: Boolean = true
)

internal class ManualMealSubmission(
    private val sendCarbs: suspend (ActionCommand) -> Boolean,
    private val stageSelection: suspend (ActionCommand, MealAbsorptionSelection, Double?) -> Unit,
    private val sendEatingSoon: suspend (String) -> EatingSoonResult
) {
    private data class Input(
        val params: Map<String, String>,
        val selection: MealAbsorptionSelection,
        val energy: Double?,
        val eatingSoon: Boolean
    )
    private data class Entry(val input: Input, var result: ManualMealResult)
    private val mutex = Mutex()
    private val completed = linkedMapOf<String, Entry>()

    suspend fun submit(
        command: ActionCommand,
        selection: MealAbsorptionSelection,
        mealEnergyKcal: Double?,
        eatingSoon: Boolean
    ): ManualMealResult = mutex.withLock {
        val key = command.idempotencyKey
        val input = Input(command.params.toMap(), selection, mealEnergyKcal, eatingSoon)
        completed[key]?.let { prior ->
            return@withLock if (prior.input == input) prior.result else prior.result.copy(
                eatingSoon = EatingSoonResult(MealDeliveryStatus.BLOCKED, "submission_changed")
            )
        }
        if (completed.size >= 64) completed.remove(completed.keys.first())
        // Reserve before I/O; cancellation/unknown delivery must not replay a meal.
        // Persisted action idempotency still protects operations after eviction/restart.
        val entry = Entry(input, ManualMealResult(
            MealDeliveryStatus.UNKNOWN, EatingSoonResult(MealDeliveryStatus.NOT_REQUESTED)
        ))
        completed[key] = entry
        val sent = try {
            sendCarbs(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!sent) return@withLock entry.result
        entry.result = ManualMealResult(
            MealDeliveryStatus.SENT,
            EatingSoonResult(if (eatingSoon) MealDeliveryStatus.UNKNOWN else MealDeliveryStatus.NOT_REQUESTED),
            profileSaved = false
        )
        try {
            stageSelection(command, selection, mealEnergyKcal)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entry.result = entry.result.copy(eatingSoon = EatingSoonResult(
                if (eatingSoon) MealDeliveryStatus.BLOCKED else MealDeliveryStatus.NOT_REQUESTED,
                "meal_profile_not_saved"
            ))
            return@withLock entry.result
        }
        entry.result = entry.result.copy(profileSaved = true)
        if (eatingSoon) {
            val target = try {
                sendEatingSoon(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EatingSoonResult(MealDeliveryStatus.UNKNOWN, "delivery_unconfirmed")
            }
            entry.result = entry.result.copy(eatingSoon = target)
        }
        entry.result
    }
}
