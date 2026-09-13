package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ManualMealSubmissionTest {
    private val selection = MealAbsorptionSelection(MealAbsorptionProfile.MIXED)
    private val command = ActionCommand(
        id = "meal-command", type = "carbs", params = mapOf("carbsGrams" to "20.0"),
        safetySnapshot = SafetySnapshot(false, true, null, 0),
        idempotencyKey = "manual:meal:unique-dialog"
    )

    @Test fun checkedSendsCarbsThenProfileThenTarget() = runTest {
        val calls = mutableListOf<String>()
        val subject = ManualMealSubmission(
            { calls += "carbs"; true }, { _, _, _ -> calls += "profile" },
            { calls += it; EatingSoonResult(MealDeliveryStatus.SENT) }
        )
        val result = subject.submit(command, selection, null, true)
        assertThat(calls).containsExactly("carbs", "profile", command.idempotencyKey).inOrder()
        assertThat(result.carbs).isEqualTo(MealDeliveryStatus.SENT)
        assertThat(result.eatingSoon.status).isEqualTo(MealDeliveryStatus.SENT)
    }

    @Test fun uncheckedNeverRequestsTarget() = runTest {
        val subject = ManualMealSubmission({ true }, { _, _, _ -> }, { error("target not requested") })
        val result = subject.submit(command, selection, null, false)
        assertThat(result.carbs).isEqualTo(MealDeliveryStatus.SENT)
        assertThat(result.eatingSoon.status).isEqualTo(MealDeliveryStatus.NOT_REQUESTED)
    }

    @Test fun failedCarbsDoesNotStageProfileOrSendTarget() = runTest {
        var calls = 0
        val subject = ManualMealSubmission({ calls++; false }, { _, _, _ -> error("profile") }, { error("target") })
        val result = subject.submit(command, selection, null, true)
        subject.submit(command, selection, null, true)
        assertThat(result.carbs).isEqualTo(MealDeliveryStatus.UNKNOWN)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun blockedTargetDoesNotResubmitCarbsOnDuplicate() = runTest {
        var carbs = 0
        var targets = 0
        val subject = ManualMealSubmission(
            { carbs++; true }, { _, _, _ -> },
            { targets++; EatingSoonResult(MealDeliveryStatus.BLOCKED, "low_glucose") }
        )
        val first = subject.submit(command, selection, null, true)
        val repeated = subject.submit(command, selection, null, true)
        assertThat(first).isEqualTo(repeated)
        assertThat(first.carbs).isEqualTo(MealDeliveryStatus.SENT)
        assertThat(first.eatingSoon.reason).isEqualTo("low_glucose")
        assertThat(carbs).isEqualTo(1)
        assertThat(targets).isEqualTo(1)
    }

    @Test fun concurrentDoubleTapHasOneTransportPerCommand() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val subject = ManualMealSubmission(
            { calls++; entered.complete(Unit); release.await(); true }, { _, _, _ -> },
            { calls++; EatingSoonResult(MealDeliveryStatus.SENT) }
        )
        val first = async { subject.submit(command, selection, null, true) }
        entered.await()
        val second = async { subject.submit(command, selection, null, true) }
        release.complete(Unit)
        assertThat(first.await()).isEqualTo(second.await())
        assertThat(calls).isEqualTo(2)
    }

    @Test fun cancellationDuringTargetRetainsCarbsAndNeverRetries() = runTest {
        var calls = 0
        val subject = ManualMealSubmission({ calls++; true }, { _, _, _ -> }, { throw CancellationException() })
        try {
            subject.submit(command, selection, null, true)
            error("cancellation must propagate")
        } catch (_: CancellationException) { }
        val result = subject.submit(command, selection, null, true)
        assertThat(result.carbs).isEqualTo(MealDeliveryStatus.SENT)
        assertThat(result.eatingSoon.status).isEqualTo(MealDeliveryStatus.UNKNOWN)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun profileFailureDoesNotHideSentCarbsOrStartTarget() = runTest {
        val subject = ManualMealSubmission({ true }, { _, _, _ -> error("profile write failed") }, { error("target") })
        val result = subject.submit(command, selection, null, true)
        assertThat(result.carbs).isEqualTo(MealDeliveryStatus.SENT)
        assertThat(result.profileSaved).isFalse()
        assertThat(result.eatingSoon.status).isEqualTo(MealDeliveryStatus.BLOCKED)
    }

    @Test fun changedInputCannotReuseAnOperationId() = runTest {
        var calls = 0
        val subject = ManualMealSubmission({ calls++; true }, { _, _, _ -> }, { EatingSoonResult(MealDeliveryStatus.SENT) })
        subject.submit(command, selection, null, false)
        val changed = subject.submit(command.copy(params = mapOf("carbsGrams" to "40.0")), selection, null, true)
        assertThat(changed.eatingSoon.reason).isEqualTo("submission_changed")
        assertThat(calls).isEqualTo(1)
    }
}
