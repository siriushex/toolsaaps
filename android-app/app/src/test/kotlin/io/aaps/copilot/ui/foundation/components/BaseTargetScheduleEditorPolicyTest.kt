package io.aaps.copilot.ui.foundation.components

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.target.BaseTargetInterval
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import org.junit.Test

class BaseTargetScheduleEditorPolicyTest {

    @Test
    fun addEditDeleteKeepsStableDraftIdAndUsesFirstFreeHour() {
        val source = schedule(
            intervals = listOf(BaseTargetInterval("night", 0, 60, 5.8))
        )
        val initial = BaseTargetScheduleEditorPolicy.createDraft(source)

        val added = BaseTargetScheduleEditorPolicy.addInterval(initial) { "new-id" }
        val newRow = added.intervals.single { it.id == "new-id" }
        assertThat(newRow.startMinuteOfDay).isEqualTo(60)
        assertThat(newRow.endMinuteOfDay).isEqualTo(120)

        val edited = BaseTargetScheduleEditorPolicy.updateInterval(
            draft = added,
            id = "new-id",
            startMinuteOfDay = 133,
            endMinuteOfDay = 207,
            targetMmol = 6.24
        )
        val editedRow = edited.intervals.single { it.id == "new-id" }
        assertThat(editedRow.id).isEqualTo("new-id")
        assertThat(editedRow.startMinuteOfDay).isEqualTo(135)
        assertThat(editedRow.endMinuteOfDay).isEqualTo(210)
        assertThat(editedRow.targetMmol).isEqualTo(6.2)

        val deleted = BaseTargetScheduleEditorPolicy.deleteInterval(edited, "new-id")
        assertThat(deleted.intervals.map { it.id }).containsExactly("night")
    }

    @Test
    fun overlapErrorIsAttachedToBothRowsAndDisablesSave() {
        val draft = BaseTargetScheduleEditorPolicy.createDraft(
            schedule(
                intervals = listOf(
                    BaseTargetInterval("a", 60, 180, 5.8),
                    BaseTargetInterval("b", 120, 240, 6.0)
                )
            )
        )

        val validation = BaseTargetScheduleEditorPolicy.validate(
            draft = draft,
            minTargetMmol = 4.0,
            maxTargetMmol = 10.0
        )

        assertThat(validation.canSave).isFalse()
        assertThat(validation.errorsByIntervalId.getValue("a")).contains("interval_overlap")
        assertThat(validation.errorsByIntervalId.getValue("b")).contains("interval_overlap")
    }

    @Test
    fun invalidDurationAndTooManyIntervalsDisableSave() {
        val zeroDuration = BaseTargetScheduleEditorPolicy.createDraft(
            schedule(intervals = listOf(BaseTargetInterval("zero", 120, 120, 5.8)))
        )
        val invalid = BaseTargetScheduleEditorPolicy.validate(zeroDuration, 4.0, 10.0)
        assertThat(invalid.canSave).isFalse()
        assertThat(invalid.errorsByIntervalId.getValue("zero")).contains("zero_duration")

        val tooMany = zeroDuration.copy(
            intervals = List(BaseTargetSchedule.MAX_INTERVALS + 1) { index ->
                BaseTargetIntervalDraft("row-$index", 0, 15, 5.8)
            }
        )
        assertThat(BaseTargetScheduleEditorPolicy.validate(tooMany, 4.0, 10.0).canSave).isFalse()
    }

    @Test
    fun draftMutationsDoNotMutateSourceScheduleAndAutoIsGlobal() {
        val source = schedule(
            autoEnabled = false,
            intervals = listOf(BaseTargetInterval("morning", 360, 480, 5.9))
        )
        val draft = BaseTargetScheduleEditorPolicy.createDraft(source)
        val changed = BaseTargetScheduleEditorPolicy.setAutoEnabled(draft, true)
        val edited = BaseTargetScheduleEditorPolicy.updateInterval(
            changed,
            id = "morning",
            startMinuteOfDay = 420,
            endMinuteOfDay = 480,
            targetMmol = 6.1
        )

        assertThat(source.autoEnabled).isFalse()
        assertThat(source.intervals.single().startMinuteOfDay).isEqualTo(360)
        assertThat(edited.autoEnabled).isTrue()
        assertThat(edited.intervals.single().startMinuteOfDay).isEqualTo(420)
    }

    @Test
    fun autoStateMapsToCompactOperationalLabels() {
        assertThat(baseTargetAutoLabel(CircadianAutoState.ACTIVE)).isEqualTo(BaseTargetAutoLabel.ACTIVE)
        assertThat(baseTargetAutoLabel(CircadianAutoState.WAITING_DATA)).isEqualTo(BaseTargetAutoLabel.WAIT)
        assertThat(baseTargetAutoLabel(CircadianAutoState.BLOCKED_SENSOR)).isEqualTo(BaseTargetAutoLabel.SENSOR)
        assertThat(baseTargetAutoLabel(CircadianAutoState.BLOCKED_LOW_RISK)).isEqualTo(BaseTargetAutoLabel.LOW_GUARD)
        assertThat(baseTargetAutoLabel(CircadianAutoState.WAITING_WRITER)).isEqualTo(BaseTargetAutoLabel.WAIT)
        assertThat(baseTargetAutoLabel(CircadianAutoState.OFF)).isEqualTo(BaseTargetAutoLabel.OFF)
    }

    private fun schedule(
        autoEnabled: Boolean = false,
        intervals: List<BaseTargetInterval> = emptyList()
    ) = BaseTargetSchedule(
        revision = 7L,
        defaultTargetMmol = 5.8,
        autoEnabled = autoEnabled,
        intervals = intervals
    )
}
