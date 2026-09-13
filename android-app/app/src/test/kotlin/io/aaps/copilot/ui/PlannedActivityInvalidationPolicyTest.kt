package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult
import io.aaps.copilot.domain.profile.ScheduleValidation
import io.aaps.copilot.ui.foundation.screens.PlannedActivityEventUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlannedActivityInvalidationPolicyTest {

    @Test
    fun createAndChangedSaveRequirePersistenceButIdenticalSaveDoesNot() {
        val existing = event("activity-1", durationMinutes = 30)

        assertThat(
            PlannedActivityInvalidationPolicy.saveChangesDurableState(emptyList(), existing)
        ).isTrue()
        assertThat(
            PlannedActivityInvalidationPolicy.saveChangesDurableState(listOf(existing), existing)
        ).isFalse()
        assertThat(
            PlannedActivityInvalidationPolicy.saveChangesDurableState(
                listOf(existing),
                existing.copy(durationMinutes = 45)
            )
        ).isTrue()
    }

    @Test
    fun deleteRequiresExistingEvent() {
        val existing = event("activity-1", durationMinutes = 30)

        assertThat(
            PlannedActivityInvalidationPolicy.deleteChangesDurableState(listOf(existing), "activity-1")
        ).isTrue()
        assertThat(
            PlannedActivityInvalidationPolicy.deleteChangesDurableState(listOf(existing), "missing")
        ).isFalse()
    }

    @Test
    fun savedResultInvalidatesExactlyOnceAfterCommit() = runTest {
        val order = mutableListOf<String>()

        val result = persistPlannedActivityClinicalInput(
            persistence = {
                order += "committed"
                PlannedActivityScheduleSaveResult.Saved
            },
            onClinicalInputPersisted = { order += "invalidated" }
        )

        assertThat(result).isEqualTo(PlannedActivityScheduleSaveResult.Saved)
        assertThat(order).containsExactly("committed", "invalidated").inOrder()
    }

    @Test
    fun invalidResultAndPersistenceFailureDoNotInvalidate() = runTest {
        var callbacks = 0
        val invalid = PlannedActivityScheduleSaveResult.Invalid(ScheduleValidation.Invalid(emptySet(), "bad"))

        val result = persistPlannedActivityClinicalInput(
            persistence = { invalid },
            onClinicalInputPersisted = { callbacks++ }
        )
        val failure = runCatching {
            persistPlannedActivityClinicalInput(
                persistence = { error("db_failed") },
                onClinicalInputPersisted = { callbacks++ }
            )
        }

        assertThat(result).isEqualTo(invalid)
        assertThat(callbacks).isEqualTo(0)
        assertThat(failure.exceptionOrNull()).hasMessageThat().isEqualTo("db_failed")
    }

    @Test
    fun ordinaryInvalidationFailureDoesNotLieAboutDurableSave() = runTest {
        val result = persistPlannedActivityClinicalInput(
            persistence = { PlannedActivityScheduleSaveResult.Saved },
            onClinicalInputPersisted = { error("ledger_failed") }
        )

        assertThat(result).isEqualTo(PlannedActivityScheduleSaveResult.Saved)
    }

    @Test
    fun invalidationCancellationAndFatalFailureStillPropagate() = runTest {
        val cancelled = runCatching {
            persistPlannedActivityClinicalInput(
                persistence = { PlannedActivityScheduleSaveResult.Saved },
                onClinicalInputPersisted = { throw CancellationException("cancelled") }
            )
        }
        val fatal = runCatching {
            persistPlannedActivityClinicalInput(
                persistence = { PlannedActivityScheduleSaveResult.Saved },
                onClinicalInputPersisted = { throw AssertionError("fatal") }
            )
        }

        assertThat(cancelled.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(fatal.exceptionOrNull()).isInstanceOf(AssertionError::class.java)
    }

    private fun event(id: String, durationMinutes: Int) = PlannedActivityEventUi(
        eventId = id,
        enabled = true,
        title = "Morning walk",
        activityType = "WALKING",
        intensity = "MEDIUM",
        localStartIso = "09:00",
        durationMinutes = durationMinutes,
        timezoneId = "Asia/Tbilisi",
        recurrenceDaysMask = 1,
        recurrenceEndEpochDay = null,
        revision = 1L,
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L
    )
}
