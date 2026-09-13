package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.domain.target.BaseTargetInterval
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.BaseTargetSchedulePolicy
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.ui.foundation.format.UiFormatters
import java.util.Locale
import java.util.UUID
import kotlin.math.round

internal data class BaseTargetIntervalDraft(
    val id: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val targetMmol: Double
)

internal data class BaseTargetScheduleDraft(
    val schemaVersion: Int,
    val revision: Long,
    val defaultTargetMmol: Double,
    val autoEnabled: Boolean,
    val intervals: List<BaseTargetIntervalDraft>
)

internal data class BaseTargetScheduleEditorValidation(
    val canSave: Boolean,
    val errorsByIntervalId: Map<String, Set<String>>,
    val globalErrors: Set<String>
)

internal enum class BaseTargetAutoLabel {
    OFF,
    ACTIVE,
    WAIT,
    SENSOR,
    LOW_GUARD
}

internal fun baseTargetAutoLabel(state: CircadianAutoState): BaseTargetAutoLabel = when (state) {
    CircadianAutoState.OFF -> BaseTargetAutoLabel.OFF
    CircadianAutoState.ACTIVE -> BaseTargetAutoLabel.ACTIVE
    CircadianAutoState.BLOCKED_SENSOR -> BaseTargetAutoLabel.SENSOR
    CircadianAutoState.BLOCKED_LOW_RISK -> BaseTargetAutoLabel.LOW_GUARD
    CircadianAutoState.WAITING_DATA,
    CircadianAutoState.WAITING_WRITER -> BaseTargetAutoLabel.WAIT
}

internal object BaseTargetScheduleEditorPolicy {
    private const val MINUTES_PER_DAY = 24 * 60
    private const val TIME_STEP_MINUTES = 15
    private const val DEFAULT_INTERVAL_MINUTES = 60

    fun createDraft(schedule: BaseTargetSchedule): BaseTargetScheduleDraft = BaseTargetScheduleDraft(
        schemaVersion = schedule.schemaVersion,
        revision = schedule.revision,
        defaultTargetMmol = normalizeTarget(schedule.defaultTargetMmol),
        autoEnabled = schedule.autoEnabled,
        intervals = schedule.intervals.map { interval ->
            BaseTargetIntervalDraft(
                id = interval.id,
                startMinuteOfDay = interval.startMinuteOfDay,
                endMinuteOfDay = interval.endMinuteOfDay,
                targetMmol = interval.targetMmol
            )
        }
    )

    fun setAutoEnabled(draft: BaseTargetScheduleDraft, enabled: Boolean): BaseTargetScheduleDraft =
        draft.copy(autoEnabled = enabled)

    fun setDefaultTarget(draft: BaseTargetScheduleDraft, targetMmol: Double): BaseTargetScheduleDraft =
        draft.copy(defaultTargetMmol = normalizeTarget(targetMmol))

    fun addInterval(
        draft: BaseTargetScheduleDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): BaseTargetScheduleDraft {
        val gap = firstFreeGap(draft.intervals, DEFAULT_INTERVAL_MINUTES)
            ?: firstFreeGap(draft.intervals, TIME_STEP_MINUTES)
            ?: (0 to 0)
        val added = BaseTargetIntervalDraft(
            id = idFactory(),
            startMinuteOfDay = gap.first,
            endMinuteOfDay = gap.second,
            targetMmol = draft.defaultTargetMmol
        )
        return draft.copy(intervals = (draft.intervals + added).sortedBy { it.startMinuteOfDay })
    }

    fun updateInterval(
        draft: BaseTargetScheduleDraft,
        id: String,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        targetMmol: Double
    ): BaseTargetScheduleDraft = draft.copy(
        intervals = draft.intervals.map { interval ->
            if (interval.id != id) {
                interval
            } else {
                interval.copy(
                    startMinuteOfDay = normalizeMinute(startMinuteOfDay),
                    endMinuteOfDay = normalizeMinute(endMinuteOfDay),
                    targetMmol = normalizeTarget(targetMmol)
                )
            }
        }.sortedBy { it.startMinuteOfDay }
    )

    fun deleteInterval(draft: BaseTargetScheduleDraft, id: String): BaseTargetScheduleDraft =
        draft.copy(intervals = draft.intervals.filterNot { it.id == id })

    fun toSchedule(draft: BaseTargetScheduleDraft): BaseTargetSchedule = BaseTargetSchedule(
        schemaVersion = draft.schemaVersion,
        revision = draft.revision,
        defaultTargetMmol = draft.defaultTargetMmol,
        autoEnabled = draft.autoEnabled,
        intervals = draft.intervals.map { interval ->
            BaseTargetInterval(
                id = interval.id,
                startMinuteOfDay = interval.startMinuteOfDay,
                endMinuteOfDay = interval.endMinuteOfDay,
                targetMmol = interval.targetMmol
            )
        }
    )

    fun validate(
        draft: BaseTargetScheduleDraft,
        minTargetMmol: Double,
        maxTargetMmol: Double
    ): BaseTargetScheduleEditorValidation {
        val errors = BaseTargetSchedulePolicy.validate(
            schedule = toSchedule(draft),
            minTarget = minTargetMmol,
            maxTarget = maxTargetMmol
        )
        val byInterval = linkedMapOf<String, MutableSet<String>>()
        val global = linkedSetOf<String>()
        errors.forEach { error ->
            if (error.intervalIds.isEmpty()) {
                global += error.code
            } else {
                error.intervalIds.forEach { id ->
                    byInterval.getOrPut(id) { linkedSetOf() } += error.code
                }
            }
        }
        return BaseTargetScheduleEditorValidation(
            canSave = errors.isEmpty(),
            errorsByIntervalId = byInterval.mapValues { it.value.toSet() },
            globalErrors = global
        )
    }

    private fun firstFreeGap(
        intervals: List<BaseTargetIntervalDraft>,
        durationMinutes: Int
    ): Pair<Int, Int>? {
        val slotsNeeded = durationMinutes / TIME_STEP_MINUTES
        val slotCount = MINUTES_PER_DAY / TIME_STEP_MINUTES
        val occupied = BooleanArray(slotCount)
        intervals.forEach { interval ->
            for (slot in 0 until slotCount) {
                val minute = slot * TIME_STEP_MINUTES
                if (contains(interval, minute)) occupied[slot] = true
            }
        }
        for (startSlot in 0 until slotCount) {
            val free = (0 until slotsNeeded).all { offset ->
                !occupied[(startSlot + offset) % slotCount]
            }
            if (free) {
                val start = startSlot * TIME_STEP_MINUTES
                val end = (start + durationMinutes) % MINUTES_PER_DAY
                return start to end
            }
        }
        return null
    }

    private fun contains(interval: BaseTargetIntervalDraft, minute: Int): Boolean = when {
        interval.endMinuteOfDay > interval.startMinuteOfDay ->
            minute >= interval.startMinuteOfDay && minute < interval.endMinuteOfDay
        interval.endMinuteOfDay < interval.startMinuteOfDay ->
            minute >= interval.startMinuteOfDay || minute < interval.endMinuteOfDay
        else -> false
    }

    private fun normalizeMinute(value: Int): Int {
        val wrapped = ((value % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return ((wrapped + TIME_STEP_MINUTES / 2) / TIME_STEP_MINUTES * TIME_STEP_MINUTES) % MINUTES_PER_DAY
    }

    private fun normalizeTarget(value: Double): Double = round(value * 10.0) / 10.0
}

@Composable
fun BaseTargetScheduleDialog(
    schedule: BaseTargetSchedule,
    effectiveTargetMmol: Double,
    autoDeltaMmol: Double,
    autoState: CircadianAutoState,
    autoReason: String?,
    minTargetMmol: Double,
    maxTargetMmol: Double,
    onDismiss: () -> Unit,
    onSave: (BaseTargetSchedule) -> Unit
) {
    var draft by remember(schedule) {
        mutableStateOf(BaseTargetScheduleEditorPolicy.createDraft(schedule))
    }
    var editingId by rememberSaveable(schedule.revision) { mutableStateOf<String?>(null) }
    val validation = remember(draft, minTargetMmol, maxTargetMmol) {
        BaseTargetScheduleEditorPolicy.validate(draft, minTargetMmol, maxTargetMmol)
    }

    AlertDialog(
        modifier = Modifier.testTag("baseTargetScheduleDialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.base_target_schedule_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AutoScheduleRow(
                    checked = draft.autoEnabled,
                    effectiveTargetMmol = effectiveTargetMmol,
                    autoDeltaMmol = autoDeltaMmol,
                    autoState = autoState,
                    autoReason = autoReason,
                    onCheckedChange = {
                        draft = BaseTargetScheduleEditorPolicy.setAutoEnabled(draft, it)
                    }
                )
                HorizontalDivider()
                TargetStepperRow(
                    label = stringResource(R.string.base_target_schedule_default),
                    targetMmol = draft.defaultTargetMmol,
                    minTargetMmol = minTargetMmol,
                    maxTargetMmol = maxTargetMmol,
                    testTag = "baseTargetDefault",
                    onChange = {
                        draft = BaseTargetScheduleEditorPolicy.setDefaultTarget(draft, it)
                    }
                )
                draft.intervals.forEach { interval ->
                    HorizontalDivider()
                    IntervalEditorRow(
                        interval = interval,
                        editing = interval.id == editingId,
                        errors = validation.errorsByIntervalId[interval.id].orEmpty(),
                        minTargetMmol = minTargetMmol,
                        maxTargetMmol = maxTargetMmol,
                        onEdit = { editingId = if (editingId == interval.id) null else interval.id },
                        onDelete = {
                            draft = BaseTargetScheduleEditorPolicy.deleteInterval(draft, interval.id)
                            if (editingId == interval.id) editingId = null
                        },
                        onChange = { start, end, target ->
                            draft = BaseTargetScheduleEditorPolicy.updateInterval(
                                draft = draft,
                                id = interval.id,
                                startMinuteOfDay = start,
                                endMinuteOfDay = end,
                                targetMmol = target
                            )
                        }
                    )
                }
                TextButton(
                    modifier = Modifier.testTag("baseTargetAddInterval"),
                    enabled = draft.intervals.size < BaseTargetSchedule.MAX_INTERVALS,
                    onClick = {
                        val newId = UUID.randomUUID().toString()
                        draft = BaseTargetScheduleEditorPolicy.addInterval(draft) { newId }
                        editingId = newId
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.base_target_schedule_add_interval))
                }
                validation.globalErrors.firstOrNull()?.let { error ->
                    Text(
                        text = scheduleErrorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("baseTargetScheduleSave"),
                enabled = validation.canSave,
                onClick = { onSave(BaseTargetScheduleEditorPolicy.toSchedule(draft)) }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("baseTargetScheduleCancel"),
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AutoScheduleRow(
    checked: Boolean,
    effectiveTargetMmol: Double,
    autoDeltaMmol: Double,
    autoState: CircadianAutoState,
    autoReason: String?,
    onCheckedChange: (Boolean) -> Unit
) {
    var showReason by rememberSaveable(autoReason) { mutableStateOf(false) }
    if (showReason && !autoReason.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showReason = false },
            title = { Text(stringResource(R.string.base_target_schedule_auto)) },
            text = { Text(autoReason.replace('_', ' ')) },
            confirmButton = {
                TextButton(onClick = { showReason = false }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.base_target_schedule_auto),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = buildAutoStatusText(effectiveTargetMmol, autoDeltaMmol, autoState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!autoReason.isNullOrBlank()) {
            IconButton(
                modifier = Modifier.size(40.dp),
                onClick = { showReason = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.status_info)
                )
            }
        }
        Switch(
            modifier = Modifier.testTag("baseTargetAutoSwitch"),
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun IntervalEditorRow(
    interval: BaseTargetIntervalDraft,
    editing: Boolean,
    errors: Set<String>,
    minTargetMmol: Double,
    maxTargetMmol: Double,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onChange: (Int, Int, Double) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("baseTargetInterval-${interval.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "${formatMinute(interval.startMinuteOfDay)}-${formatMinute(interval.endMinuteOfDay)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = UiFormatters.formatMmol(interval.targetMmol, 1),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
        if (editing) {
            TimeStepperRow(
                label = stringResource(R.string.base_target_schedule_start),
                minute = interval.startMinuteOfDay,
                onChange = { onChange(it, interval.endMinuteOfDay, interval.targetMmol) }
            )
            TimeStepperRow(
                label = stringResource(R.string.base_target_schedule_end),
                minute = interval.endMinuteOfDay,
                onChange = { onChange(interval.startMinuteOfDay, it, interval.targetMmol) }
            )
            TargetStepperRow(
                label = stringResource(R.string.base_target_schedule_target),
                targetMmol = interval.targetMmol,
                minTargetMmol = minTargetMmol,
                maxTargetMmol = maxTargetMmol,
                testTag = "baseTargetIntervalTarget-${interval.id}",
                onChange = { onChange(interval.startMinuteOfDay, interval.endMinuteOfDay, it) }
            )
        }
        errors.firstOrNull()?.let { error ->
            Text(
                text = scheduleErrorText(error),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun TimeStepperRow(label: String, minute: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = { onChange(minute - 15) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.action_decrease))
        }
        Text(formatMinute(minute), modifier = Modifier.width(56.dp))
        IconButton(onClick = { onChange(minute + 15) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_increase))
        }
    }
}

@Composable
private fun TargetStepperRow(
    label: String,
    targetMmol: Double,
    minTargetMmol: Double,
    maxTargetMmol: Double,
    testTag: String,
    onChange: (Double) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        IconButton(
            enabled = targetMmol > minTargetMmol + 0.0001,
            onClick = { onChange((targetMmol - 0.1).coerceAtLeast(minTargetMmol)) }
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.action_decrease))
        }
        Text(UiFormatters.formatMmol(targetMmol, 1), modifier = Modifier.width(44.dp))
        IconButton(
            enabled = targetMmol < maxTargetMmol - 0.0001,
            onClick = { onChange((targetMmol + 0.1).coerceAtMost(maxTargetMmol)) }
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_increase))
        }
    }
}

@Composable
private fun scheduleErrorText(code: String): String = when (code) {
    "interval_overlap" -> stringResource(R.string.base_target_schedule_error_overlap)
    "zero_duration" -> stringResource(R.string.base_target_schedule_error_duration)
    "too_many_intervals" -> stringResource(R.string.base_target_schedule_error_too_many)
    else -> stringResource(R.string.base_target_schedule_error_invalid)
}

@Composable
internal fun buildAutoStatusText(
    effectiveTargetMmol: Double,
    autoDeltaMmol: Double,
    autoState: CircadianAutoState
): String {
    val effective = UiFormatters.formatMmol(effectiveTargetMmol, 1)
    return when (baseTargetAutoLabel(autoState)) {
        BaseTargetAutoLabel.ACTIVE -> String.format(
            Locale.getDefault(),
            "%s  AUTO %+.1f",
            effective,
            autoDeltaMmol
        )
        BaseTargetAutoLabel.WAIT -> "$effective  ${stringResource(R.string.base_target_auto_wait)}"
        BaseTargetAutoLabel.SENSOR -> "$effective  ${stringResource(R.string.base_target_auto_sensor)}"
        BaseTargetAutoLabel.LOW_GUARD -> "$effective  ${stringResource(R.string.base_target_auto_low_guard)}"
        BaseTargetAutoLabel.OFF -> effective
    }
}

private fun formatMinute(minuteOfDay: Int): String {
    val normalized = ((minuteOfDay % (24 * 60)) + 24 * 60) % (24 * 60)
    return String.format(Locale.getDefault(), "%02d:%02d", normalized / 60, normalized % 60)
}
