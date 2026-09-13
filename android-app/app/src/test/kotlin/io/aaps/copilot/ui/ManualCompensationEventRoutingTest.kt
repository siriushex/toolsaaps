package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEventType
import org.junit.Test

class ManualCompensationEventRoutingTest {
    @Test
    fun authorityOwnedTypesNeverUseContextRows() {
        assertThat(manualCompensationEventRoute(CompensationEventType.MEAL))
            .isEqualTo(ManualCompensationEventRoute.MEAL_ACTION)
        assertThat(manualCompensationEventRoute(CompensationEventType.ACTIVITY))
            .isEqualTo(ManualCompensationEventRoute.PLANNED_ACTIVITY)
        assertThat(manualCompensationEventRoute(CompensationEventType.SENSOR_CALIBRATION))
            .isEqualTo(ManualCompensationEventRoute.BLOOD_CHECK)
        assertThat(manualCompensationEventRoute(CompensationEventType.INFUSION_PUMP_INSULIN))
            .isEqualTo(ManualCompensationEventRoute.READ_ONLY_DIAGNOSTIC)
    }

    @Test
    fun onlyContextTypesUseContextCoordinator() {
        val contextTypes = CompensationEventType.entries.filter {
            manualCompensationEventRoute(it) == ManualCompensationEventRoute.CONTEXT
        }

        assertThat(contextTypes).containsExactly(
            CompensationEventType.STRESS,
            CompensationEventType.ILLNESS,
            CompensationEventType.SLEEP,
            CompensationEventType.HORMONAL,
            CompensationEventType.MEDICATION_STEROID,
            CompensationEventType.ALCOHOL,
            CompensationEventType.CUSTOM,
            CompensationEventType.MENSTRUAL_CYCLE
        )
    }
}
