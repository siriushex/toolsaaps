package io.aaps.copilot.telegram

import android.content.Context
import io.aaps.copilot.R
import io.aaps.copilot.data.local.dao.AlertEventDao
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.data.repository.ClinicalLocalReport
import io.aaps.copilot.data.repository.ClinicalPeriodSummary
import io.aaps.copilot.data.repository.ClinicalReportRepository
import io.aaps.copilot.data.repository.ClinicalReportState
import io.aaps.copilot.data.repository.EpisodeAlertDeliveryStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TelegramDeliveryController(
    private val context: Context,
    private val repository: TelegramRepository,
    private val events: AlertEventDao,
    private val mute: EpisodeAlertDeliveryStateMachine,
    private val reports: ClinicalReportRepository
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            repository.initialize()
            combine(
                events.observeOpen().map { rows -> rows.mapNotNull(::alert) }.distinctUntilChanged(),
                mute.mutedUntil,
                repository.state.map { it.routingRevision }.distinctUntilChanged()
            ) { alerts, mutedUntil, revision -> Triple(alerts, mutedUntil, revision) }
                .collectLatest { (alerts, mutedUntil, _) ->
                    if (mutedUntil > System.currentTimeMillis()) return@collectLatest
                    for (value in alerts) {
                        try {
                            repository.sendAlert(value, muted = mute.currentMutedUntil() > System.currentTimeMillis())
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Local delivery is independent of Telegram. */ }
                    }
                }
        }
        scope.launch {
            repository.initialize()
            combine(
                reports.state,
                repository.state.map { it.routingRevision }.distinctUntilChanged(),
                mute.mutedUntil
            ) { report, revision, until -> Triple(report, revision, until) }.collectLatest { (report, _, until) ->
                if (until > System.currentTimeMillis()) return@collectLatest
                if (report !is ClinicalReportState.Complete) return@collectLatest
                try {
                    repository.sendReport(report.requestId, report.local.generatedAt,
                        summary(report.local), automatic = true)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the report available locally. */ }
            }
        }
    }

    suspend fun sendCurrentSummary() {
        val local = when (val current = reports.state.value) {
            is ClinicalReportState.LocalReady -> current.local
            is ClinicalReportState.Complete -> current.local
            is ClinicalReportState.Failed -> current.local
            else -> null
        } ?: throw TelegramFailure(TelegramIssue.NOT_READY)
        if (System.currentTimeMillis() - local.generatedAt !in 0..60 * 60_000L) {
            throw TelegramFailure(TelegramIssue.NOT_READY)
        }
        repository.sendReport(local.requestHash, local.generatedAt, summary(local), automatic = false)
    }

    private fun alert(event: AlertEventEntity): TelegramAlert? {
        val timestamp = event.lastNotificationAt ?: return null
        if (event.suppressionUntil?.let { it > System.currentTimeMillis() } == true) return null
        val resource = when {
            event.eventType == "DELIVERY_DIAGNOSTIC" -> R.string.telegram_delivery_risk
            event.eventType !in setOf("GLUCOSE_ALERT_LOW", "GLUCOSE_ALERT_HIGH") -> return null
            event.stage == "LOW_NOW" -> R.string.glucose_alert_compact_risk_low_now
            event.stage == "CRITICAL_5" -> R.string.glucose_alert_compact_risk_low_5m
            event.stage == "WARNING_30" -> R.string.glucose_alert_compact_risk_low_30m
            event.stage == "WATCH_60" -> R.string.glucose_alert_compact_risk_low_60m
            event.stage == "SOFT_HIGH_RISK" -> R.string.glucose_alert_compact_risk_high_30m
            else -> return null
        }
        val cause = when (event.causeCode) {
            "SENSOR_QUALITY" -> R.string.glucose_alert_cause_sensor_quality
            "MEAL_UAM" -> R.string.glucose_alert_cause_meal_uam
            "DELIVERY_NONRESPONSE" -> R.string.glucose_alert_cause_delivery_nonresponse
            "CIRCADIAN_PATTERN" -> R.string.glucose_alert_cause_circadian_pattern
            else -> null
        }
        val text = buildString {
            append("AAPS Copilot\n")
            append(context.getString(resource))
            cause?.let { append("\n").append(context.getString(it)) }
            append("\n").append(time(timestamp))
            append("\n").append(context.getString(R.string.telegram_contact_patient))
        }
        return TelegramAlert(
            key = "${event.episodeId}:$timestamp", timestamp = timestamp, text = text,
            urgent = event.stage in setOf("LOW_NOW", "CRITICAL_5") || event.eventType == "DELIVERY_DIAGNOSTIC"
        )
    }

    private fun summary(local: ClinicalLocalReport): String = buildString {
        append(context.getString(R.string.telegram_summary_title)).append("\n")
        append(time(local.generatedAt)).append("\n")
        append(period(local.summary7d)).append("\n\n")
        append(period(local.summary30d))
    }

    private fun period(value: ClinicalPeriodSummary): String = context.getString(
        R.string.telegram_summary_body, value.days, number(value.meanMmol), number(value.timeBelow4Pct),
        number(value.timeInRangePct), number(value.timeAboveRangePct), number(value.coveragePct),
        number(value.confirmedInsulinU), number(value.estimatedInsulinU), number(value.enteredCarbsG),
        number(value.uamCarbsG), number(value.activity.steps), number(value.activity.activeCaloriesKcal),
        number(value.totalInsulinU), number(value.totalCarbsG)
    )

    private fun number(value: Double?): String = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "?"
    private fun time(timestamp: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
}
