package io.aaps.copilot.data.repository

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.aaps.copilot.R
import io.aaps.copilot.AlertNotificationEntryActivity
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.alerts.AlertCauseAnalysis
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.receiver.GlucoseAlertActionReceiver
import java.text.NumberFormat
import java.util.Locale

class GlucoseAlertNotifier(
    private val context: Context,
    private val audioController: GlucoseAlertAudioController,
    private val episodeDelivery: EpisodeAlertDeliveryStateMachine? = null
) {

    suspend fun deliver(
        decision: GlucoseAlertDecision,
        currentGlucoseMmol: Double?,
        settings: AppSettings,
        causeAnalysis: AlertCauseAnalysis? = null,
        causeSnapshot: AlertCauseSnapshot? = null,
        nowTs: Long = System.currentTimeMillis()
    ): GlucoseAlertDeliveryResult {
        if (decision.notifyKind == GlucoseAlertNotifyKind.CLEAR) {
            episodeDelivery?.coordinate(
                EpisodeAlertSignal(decision.episodeStage, decision.episodeDirection, nowTs)
            ) { AlertSideEffectResult.failed("clear_has_no_delivery") }
            clearPostedNotifications(context)
            audioController.stop()
            return GlucoseAlertDeliveryResult(cleared = true)
        }
        val coordinator = episodeDelivery ?: return GlucoseAlertDeliveryResult(degradedReason = "episode_coordinator_missing")
        var nativeResult = GlucoseAlertDeliveryResult()
        val coordinated = coordinator.coordinate(
            signal = EpisodeAlertSignal(
                stage = decision.episodeStage,
                direction = decision.episodeDirection,
                nowTs = nowTs,
                causeAnalysis = causeAnalysis,
                causeSnapshot = causeSnapshot
            )
        ) { claim ->
            ensureChannels()
            val claimedDecision = claimedDecisionForDelivery(decision, claim)
            nativeResult = when (claimedDecision.notifyKind) {
                GlucoseAlertNotifyKind.NONE,
                GlucoseAlertNotifyKind.CLEAR -> GlucoseAlertDeliveryResult(degradedReason = "non_deliverable_stage")
                GlucoseAlertNotifyKind.WATCH_60 -> notifyWatch(
                    claim,
                    claimedDecision,
                    currentGlucoseMmol
                )
                GlucoseAlertNotifyKind.WARNING_30,
                GlucoseAlertNotifyKind.SOFT_HIGH -> notifySoft(
                    claim,
                    claimedDecision,
                    currentGlucoseMmol,
                    settings
                )
                GlucoseAlertNotifyKind.CRITICAL_5,
                GlucoseAlertNotifyKind.LOW_NOW -> notifyCritical(
                    claim,
                    claimedDecision,
                    currentGlucoseMmol,
                    settings
                )
            }
            nativeResult.toAlertSideEffectResult()
        }
        return when {
            coordinated.sideEffectAttempted -> nativeResult
            coordinated.deliveryKind == AlertDeliveryKind.SUPPRESSED_SNOOZE ->
                GlucoseAlertDeliveryResult(degradedReason = "glucose_alert_muted")
            else -> GlucoseAlertDeliveryResult()
        }
    }

    private suspend fun notifyWatch(
        claim: EpisodeDeliveryClaim,
        decision: GlucoseAlertDecision,
        currentGlucoseMmol: Double?
    ): GlucoseAlertDeliveryResult {
        val manager = NotificationManagerCompat.from(context)
        val permissionGranted = notificationsAllowed()
        val content = context.getString(
            R.string.glucose_alert_watch_body,
            formatMmol(currentGlucoseMmol),
            formatMinutes(decision.predictedMinutesToLow)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.glucose_alert_watch_title))
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setContentIntent(alertsContentPendingIntent(claim.episodeId))
            .setAutoCancel(true)
            .setCompactMuteRow(decision, currentGlucoseMmol, claim.persistedCause)
            .build()
        audioController.stop()
        return deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = permissionGranted,
            postNotification = { postNotification(manager, claim, notification) },
            invokeVibration = { false },
            startAudio = { GlucoseAlertAudioPlaybackResult() }
        )
    }

    private suspend fun notifySoft(
        claim: EpisodeDeliveryClaim,
        decision: GlucoseAlertDecision,
        currentGlucoseMmol: Double?,
        settings: AppSettings
    ): GlucoseAlertDeliveryResult {
        val manager = NotificationManagerCompat.from(context)
        val permissionGranted = notificationsAllowed()
        val title = when (decision.state) {
            GlucoseAlertState.WARNING_30 -> context.getString(R.string.glucose_alert_warning_title)
            GlucoseAlertState.SOFT_HIGH_RISK -> context.getString(R.string.glucose_alert_soft_high_title)
            else -> context.getString(R.string.glucose_alert_warning_title)
        }
        val content = when (decision.state) {
            GlucoseAlertState.SOFT_HIGH_RISK -> context.getString(
                R.string.glucose_alert_soft_body,
                formatMmol(currentGlucoseMmol),
                formatMmol(decision.pred30)
            )
            else -> context.getString(
                R.string.glucose_alert_warning_body,
                formatMmol(currentGlucoseMmol),
                formatMinutes(decision.predictedMinutesToLow)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_SOFT_SOUND)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // The bounded app clip and vibration are the single soft-signal source.
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setContentIntent(alertsContentPendingIntent(claim.episodeId))
            .setAutoCancel(true)
            .setCompactMuteRow(decision, currentGlucoseMmol, claim.persistedCause)
            .build()
        return deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = permissionGranted,
            postNotification = { postNotification(manager, claim, notification) },
            invokeVibration = { vibrate(SOFT_VIBRATION) },
            startAudio = { audioController.playForDecision(decision = decision, settings = settings) }
        )
    }

    private suspend fun notifyCritical(
        claim: EpisodeDeliveryClaim,
        decision: GlucoseAlertDecision,
        currentGlucoseMmol: Double?,
        settings: AppSettings
    ): GlucoseAlertDeliveryResult {
        val permissionGranted = notificationsAllowed()
        val (title, body) = when (decision.notifyKind) {
            GlucoseAlertNotifyKind.LOW_NOW -> {
                context.getString(R.string.glucose_alert_low_now_title) to context.getString(
                    R.string.glucose_alert_low_now_body,
                    formatMmol(currentGlucoseMmol),
                    formatMmol(decision.urgentLowThreshold)
                )
            }
            else -> {
                context.getString(R.string.glucose_alert_critical_title) to context.getString(
                    R.string.glucose_alert_critical_body,
                    formatMmol(currentGlucoseMmol),
                    formatMinutes(decision.predictedMinutesToLow)
                )
            }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_CRITICAL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOnlyAlertOnce(true)
            .setContentIntent(alertsContentPendingIntent(claim.episodeId))
            .setAutoCancel(true)
            .setCompactMuteRow(decision, currentGlucoseMmol, claim.persistedCause)
            .build()
        return deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = permissionGranted,
            postNotification = {
                postNotification(
                    manager = NotificationManagerCompat.from(context),
                    claim = claim,
                    notification = notification
                )
            },
            invokeVibration = { vibrate(STRONG_VIBRATION) },
            startAudio = { audioController.playForDecision(decision = decision, settings = settings) }
        )
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_WATCH) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WATCH,
                    context.getString(R.string.glucose_alert_channel_watch_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.glucose_alert_channel_watch_description)
                    enableVibration(true)
                    vibrationPattern = WATCH_VIBRATION
                    setShowBadge(false)
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_SOFT_SOUND) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SOFT_SOUND,
                    context.getString(R.string.glucose_alert_channel_soft_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.glucose_alert_channel_soft_description)
                    enableVibration(true)
                    vibrationPattern = SOFT_VIBRATION
                    setShowBadge(false)
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_CRITICAL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CRITICAL,
                    context.getString(R.string.glucose_alert_channel_strong_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.glucose_alert_channel_strong_description)
                    enableVibration(true)
                    vibrationPattern = STRONG_VIBRATION
                    setShowBadge(true)
                }
            )
        }
    }

    private fun notificationsAllowed(): Boolean {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val permissionOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return enabled && permissionOk
    }

    private fun postNotification(
        manager: NotificationManagerCompat,
        claim: EpisodeDeliveryClaim,
        notification: Notification
    ): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            manager.notify(claim.notificationTag, claim.notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun vibrate(pattern: LongArray): Boolean {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return false
        if (!vibrator.hasVibrator()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
        return true
    }

    private fun formatMmol(value: Double?): String {
        return value?.takeIf(Double::isFinite)?.let {
            "${localizedGlucoseValue(it, notificationLocale())} ${context.getString(R.string.unit_mmol_l)}"
        } ?: "--"
    }

    private fun formatMinutes(value: Int?): String {
        return value?.let { context.getString(R.string.glucose_alert_minutes_template, it) } ?: "--"
    }

    private fun NotificationCompat.Builder.setCompactMuteRow(
        decision: GlucoseAlertDecision,
        currentGlucoseMmol: Double?,
        persistedCause: AlertCauseCode?
    ): NotificationCompat.Builder {
        val compactView = RemoteViews(context.packageName, R.layout.notification_glucose_alert_compact).apply {
            setTextViewText(
                R.id.glucose_alert_compact_message,
                compactGlucoseAlertText(
                    riskLabel = decision.notifyKind.compactRiskLabelRes()
                        ?.let(context::getString)
                        .orEmpty(),
                    currentGlucoseMmol = currentGlucoseMmol,
                    causeLabel = persistedCause?.let { context.getString(it.compactLabelRes()) },
                    locale = notificationLocale()
                )
            )
            setTextViewText(
                R.id.glucose_alert_compact_off_30,
                context.getString(GlucoseAlertMuteOption.MINUTES_30.compactLabelRes)
            )
            setTextViewText(
                R.id.glucose_alert_compact_off_60,
                context.getString(GlucoseAlertMuteOption.MINUTES_60.compactLabelRes)
            )
            setOnClickPendingIntent(
                R.id.glucose_alert_compact_off_30,
                mutePendingIntent(decision, GlucoseAlertMuteOption.MINUTES_30)
            )
            setOnClickPendingIntent(
                R.id.glucose_alert_compact_off_60,
                mutePendingIntent(decision, GlucoseAlertMuteOption.MINUTES_60)
            )
        }
        setStyle(NotificationCompat.DecoratedCustomViewStyle())
        setCustomContentView(compactView)
        setCustomBigContentView(compactView)
        setCustomHeadsUpContentView(compactView)
        return this
    }

    private fun notificationLocale(): Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.resources.configuration.locales[0]
    } else {
        @Suppress("DEPRECATION")
        context.resources.configuration.locale ?: Locale.getDefault()
    }

    private fun mutePendingIntent(
        decision: GlucoseAlertDecision,
        option: GlucoseAlertMuteOption
    ): PendingIntent {
        val intent = Intent(context, GlucoseAlertActionReceiver::class.java)
            .setAction(option.action)
            .setPackage(context.packageName)
            .putExtra(EXTRA_MUTE_SOURCE_KIND, decision.notifyKind.name)
            .putExtra(EXTRA_MUTE_SOURCE_STATE, decision.state.name)
            .putExtra(EXTRA_EPISODE_ID, decision.episodeId)
            .putExtra(EXTRA_MUTE_DURATION_MS, option.durationMs)
        return PendingIntent.getBroadcast(
            context,
            option.requestCode xor decision.episodeId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alertsContentPendingIntent(episodeId: String): PendingIntent {
        val intent = glucoseAlertContentIntent(context, episodeId)
        return PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE xor episodeId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val EXTRA_MUTE_SOURCE_KIND = "io.aaps.copilot.extra.MUTE_SOURCE_KIND"
        const val EXTRA_MUTE_SOURCE_STATE = "io.aaps.copilot.extra.MUTE_SOURCE_STATE"
        const val EXTRA_EPISODE_ID = "io.aaps.copilot.extra.EPISODE_ID"
        const val EXTRA_MUTE_DURATION_MS = "io.aaps.copilot.extra.MUTE_DURATION_MS"
        const val ACTION_OPEN_ALERTS = "io.aaps.copilot.action.OPEN_ALERTS"
        const val EXTRA_OPEN_ALERT_EPISODE_ID = "io.aaps.copilot.extra.OPEN_ALERT_EPISODE_ID"
        private const val CHANNEL_WATCH = "copilot_glucose_alert_watch"
        private const val CHANNEL_SOFT_SOUND = "copilot_glucose_alert_soft"
        private const val CHANNEL_CRITICAL = "copilot_glucose_alert_critical"
        private const val WATCH_NOTIFICATION_ID = 31_000
        private const val SOFT_NOTIFICATION_ID = 31_001
        private const val STRONG_NOTIFICATION_ID = 31_002
        private const val CONTENT_REQUEST_CODE = 31_020
        private val WATCH_VIBRATION = longArrayOf(0, 40)
        private val SOFT_VIBRATION = longArrayOf(0, 60, 80, 60)
        private val STRONG_VIBRATION = longArrayOf(0, 180, 120, 220, 120, 260)

        fun clearPostedNotifications(context: Context) {
            val compat = NotificationManagerCompat.from(context)
            compat.run {
                cancel(
                    EpisodeAlertDeliveryStateMachine.GLUCOSE_NOTIFICATION_TAG,
                    EpisodeAlertDeliveryStateMachine.GLUCOSE_NOTIFICATION_ID
                )
                cancel(WATCH_NOTIFICATION_ID)
                cancel(SOFT_NOTIFICATION_ID)
                cancel(STRONG_NOTIFICATION_ID)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.activeNotifications
                    ?.filter { it.tag?.startsWith(EpisodeAlertDeliveryStateMachine.NOTIFICATION_TAG_PREFIX) == true }
                    ?.forEach { compat.cancel(it.tag, it.id) }
            }
        }

        fun cancelGlucoseAlertVibration(context: Context) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            runCatching { vibrator?.cancel() }
        }
    }
}

internal fun glucoseAlertContentIntent(context: Context, episodeId: String): Intent =
    Intent(context, AlertNotificationEntryActivity::class.java)
        .setAction(GlucoseAlertNotifier.ACTION_OPEN_ALERTS)
        .setData(Uri.Builder().scheme("aapscopilot").authority("alerts").appendPath(episodeId).build())
        .setPackage(context.packageName)
        .putExtra(GlucoseAlertNotifier.EXTRA_OPEN_ALERT_EPISODE_ID, episodeId)

private fun GlucoseAlertState.toNotifyKindForDelivery(): GlucoseAlertNotifyKind = when (this) {
    GlucoseAlertState.NONE -> GlucoseAlertNotifyKind.NONE
    GlucoseAlertState.WATCH_60 -> GlucoseAlertNotifyKind.WATCH_60
    GlucoseAlertState.WARNING_30 -> GlucoseAlertNotifyKind.WARNING_30
    GlucoseAlertState.SOFT_HIGH_RISK -> GlucoseAlertNotifyKind.SOFT_HIGH
    GlucoseAlertState.CRITICAL_5 -> GlucoseAlertNotifyKind.CRITICAL_5
    GlucoseAlertState.LOW_NOW -> GlucoseAlertNotifyKind.LOW_NOW
}

internal fun claimedDecisionForDelivery(
    currentDecision: GlucoseAlertDecision,
    claim: EpisodeDeliveryClaim
): GlucoseAlertDecision = currentDecision.copy(
    state = claim.claimedStage,
    episodeId = claim.episodeId,
    notifyKind = claim.claimedStage.toNotifyKindForDelivery(),
    episodeStage = claim.claimedStage
)

enum class GlucoseAlertMuteOption(
    val action: String,
    val minutes: Int,
    val requestCode: Int,
    val compactLabelRes: Int
) {
    MINUTES_30(
        action = "io.aaps.copilot.action.MUTE_GLUCOSE_ALERTS_30M",
        minutes = 30,
        requestCode = 31_010,
        compactLabelRes = R.string.glucose_alert_action_mute_30m_compact
    ),
    MINUTES_60(
        action = "io.aaps.copilot.action.MUTE_GLUCOSE_ALERTS_60M",
        minutes = 60,
        requestCode = 31_011,
        compactLabelRes = R.string.glucose_alert_action_mute_60m_compact
    );

    val durationMs: Long
        get() = minutes * 60_000L

    companion object {
        fun fromAction(action: String?): GlucoseAlertMuteOption? =
            entries.firstOrNull { it.action == action }
    }
}

data class GlucoseAlertDeliveryResult(
    val notificationPermissionGranted: Boolean = true,
    val notificationPosted: Boolean = false,
    val vibrationSucceeded: Boolean = false,
    val cleared: Boolean = false,
    val degradedReason: String? = null,
    val audioPlayed: Boolean = false,
    val audioStartEnqueued: Boolean = false,
    val audioClipLabel: String? = null,
    val audioFallbackUsed: Boolean = false,
    val audioStartMs: Int? = null,
    val audioDurationMs: Int? = null
) {
    val vibrationAttempted: Boolean
        get() = vibrationSucceeded
}

internal fun compactGlucoseAlertText(
    riskLabel: String,
    currentGlucoseMmol: Double?,
    causeLabel: String? = null,
    locale: Locale
): String {
    val risk = riskLabel.boundedCompactToken(MAX_COMPACT_RISK_TOKEN_CHARS)
        .takeIf(String::isNotBlank) ?: return ""
    val cause = causeLabel?.boundedCompactToken(MAX_COMPACT_CAUSE_TOKEN_CHARS)
        ?.takeIf(String::isNotBlank)
    val glucose = currentGlucoseMmol?.takeIf(Double::isFinite)?.let {
        localizedGlucoseValue(it, locale)
    }
    return listOfNotNull(risk, cause, glucose)
        .joinToString(" · ")
        .take(MAX_COMPACT_ALERT_TEXT_CHARS)
}

internal fun localizedGlucoseValue(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)

private fun String.boundedCompactToken(maxLength: Int): String =
    lineSequence().joinToString(" ") { it.trim() }.trim().take(maxLength)

internal const val MAX_COMPACT_ALERT_TEXT_CHARS = 96
private const val MAX_COMPACT_RISK_TOKEN_CHARS = 24
private const val MAX_COMPACT_CAUSE_TOKEN_CHARS = 40

private fun GlucoseAlertNotifyKind.compactRiskLabelRes(): Int? = when (this) {
    GlucoseAlertNotifyKind.LOW_NOW -> R.string.glucose_alert_compact_risk_low_now
    GlucoseAlertNotifyKind.CRITICAL_5 -> R.string.glucose_alert_compact_risk_low_5m
    GlucoseAlertNotifyKind.WARNING_30 -> R.string.glucose_alert_compact_risk_low_30m
    GlucoseAlertNotifyKind.WATCH_60 -> R.string.glucose_alert_compact_risk_low_60m
    GlucoseAlertNotifyKind.SOFT_HIGH -> R.string.glucose_alert_compact_risk_high_30m
    GlucoseAlertNotifyKind.NONE,
    GlucoseAlertNotifyKind.CLEAR -> null
}

private fun AlertCauseCode.compactLabelRes(): Int = when (this) {
    AlertCauseCode.SENSOR_QUALITY -> R.string.glucose_alert_cause_sensor_quality
    AlertCauseCode.MEAL_UAM -> R.string.glucose_alert_cause_meal_uam
    AlertCauseCode.INSULIN_ACTIVITY_LOW -> R.string.glucose_alert_cause_insulin_activity_low
    AlertCauseCode.DELIVERY_NONRESPONSE -> R.string.glucose_alert_cause_delivery_nonresponse
    AlertCauseCode.CIRCADIAN_PATTERN -> R.string.glucose_alert_cause_circadian_pattern
    AlertCauseCode.TARGET_RESPONSE -> R.string.glucose_alert_cause_target_response
    AlertCauseCode.EVENT_CONTEXT -> R.string.glucose_alert_cause_event_context
    AlertCauseCode.DATA_INCOMPLETE -> R.string.glucose_alert_cause_data_incomplete
    AlertCauseCode.UNKNOWN -> R.string.glucose_alert_cause_unknown
}
