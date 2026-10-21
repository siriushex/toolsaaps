package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MealDeliveryStatus { NOT_REQUESTED, SENT, BLOCKED, UNKNOWN }

data class EatingSoonResult(val status: MealDeliveryStatus, val reason: String? = null)

data class ManualMealResult(
    val carbs: MealDeliveryStatus,
    val eatingSoon: EatingSoonResult,
    val profileSaved: Boolean = true,
    val carbBlockReason: String? = null
)

internal class ManualMealSubmission(
    private val sendCarbs: suspend (ActionCommand) -> Boolean,
    private val stageSelection: suspend (ActionCommand, MealAbsorptionSelection, Double?) -> Unit,
    private val sendEatingSoon: suspend (String) -> EatingSoonResult,
    private val readCarbBlockReason: suspend (ActionCommand) -> String? = { null },
    private val onMealInput: suspend (ActionCommand) -> Unit = {}
) {
    private data class Input(
        val params: Map<String, String>,
        val selection: MealAbsorptionSelection,
        val energy: Double?,
        val eatingSoon: Boolean
    )
    private data class Entry(
        val input: Input,
        var result: ManualMealResult,
        val completion: CompletableDeferred<ManualMealResult> = CompletableDeferred()
    )
    private val reservationMutex = Mutex()
    private val carbDeliveryMutex = Mutex()
    private val completed = linkedMapOf<String, Entry>()

    suspend fun submit(
        command: ActionCommand,
        selection: MealAbsorptionSelection,
        mealEnergyKcal: Double?,
        eatingSoon: Boolean
    ): ManualMealResult {
        val frozenParams = command.params.toMutableMap().apply {
            put("mealProfile", selection.profile.name)
            put("mealDurationMinutes", selection.durationMinutes?.toString().orEmpty())
            put("mealTherapyRevision", selection.therapyRevision.orEmpty())
            put("mealPortion", selection.portionMetadata?.portion?.name.orEmpty())
            put("mealPortionProvenance", selection.portionMetadata?.provenance?.name.orEmpty())
            put("mealEnergyKcal", mealEnergyKcal?.toString().orEmpty())
            put("mealEatingSoon", eatingSoon.toString())
        }
        val reservedCommand = command.copy(params = Collections.unmodifiableMap(frozenParams))
        val key = reservedCommand.idempotencyKey
        val input = Input(reservedCommand.params, selection, mealEnergyKcal, eatingSoon)
        val (entry, owner) = reservationMutex.withLock {
            completed[key]?.let { prior ->
                if (prior.input != input) return rejected("submission_changed")
                return@withLock prior to false
            }
            if (completed.size >= 64) {
                val retired = completed.entries.firstOrNull { it.value.completion.isCompleted }?.key
                    ?: return rejected("submission_busy")
                completed.remove(retired)
            }
            // Never evict an in-flight owner or let a duplicate take over its send.
            val reserved = Entry(input, ManualMealResult(
                MealDeliveryStatus.NOT_REQUESTED, EatingSoonResult(MealDeliveryStatus.NOT_REQUESTED),
                profileSaved = false
            ))
            completed[key] = reserved
            reserved to true
        }
        if (!owner) return entry.completion.await()
        try {
            // Reserve the target before waiting for any other meal's carbohydrate I/O.
            if (eatingSoon) {
                entry.result = entry.result.copy(eatingSoon = EatingSoonResult(MealDeliveryStatus.UNKNOWN))
                val target = try {
                    sendEatingSoon(key)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    EatingSoonResult(MealDeliveryStatus.UNKNOWN, "delivery_unconfirmed")
                }
                entry.result = entry.result.copy(eatingSoon = target)
            }
            return carbDeliveryMutex.withLock {
                // Research intent still precedes carbs, after the independent target request.
                try { onMealInput(reservedCommand) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                entry.result = entry.result.copy(carbs = MealDeliveryStatus.UNKNOWN)
                val sent = try {
                    sendCarbs(reservedCommand)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!sent) {
                    val reason = try {
                        readCarbBlockReason(reservedCommand)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (reason != null) entry.result = entry.result.copy(
                        carbs = MealDeliveryStatus.BLOCKED, carbBlockReason = reason
                    )
                    return@withLock entry.result
                }
                entry.result = entry.result.copy(carbs = MealDeliveryStatus.SENT)
                try {
                    stageSelection(reservedCommand, selection, mealEnergyKcal)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withLock entry.result
                }
                entry.result = entry.result.copy(profileSaved = true)
                entry.result
            }
        } finally {
            // A cancelled owner still publishes its partial outcome to duplicate callers.
            entry.completion.complete(entry.result)
        }
    }

    private fun rejected(reason: String) = ManualMealResult(
        carbs = MealDeliveryStatus.BLOCKED,
        eatingSoon = EatingSoonResult(MealDeliveryStatus.BLOCKED, reason),
        profileSaved = false,
        carbBlockReason = reason
    )
}
