package io.aaps.copilot.service

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.TelemetryMetricMapper
import io.aaps.copilot.domain.activity.ActivityIntensity
import io.aaps.copilot.scheduler.WorkScheduler
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HealthConnectActivityCollector(
    context: Context,
    private val db: CopilotDatabase,
    private val auditLogger: AuditLogger
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var started = false
    private var syncJob: Job? = null
    private var lastState: String? = null

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            syncJob = scope.launch {
                while (isActive && started) {
                    runCatching { syncOnce() }
                        .onFailure { error ->
                            reportState(
                                state = "sync_failed",
                                warn = true,
                                metadata = mapOf("error" to (error.message ?: error::class.java.simpleName))
                            )
                        }
                    delay(millisUntilNextBoundary())
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            started = false
            syncJob?.cancel()
            syncJob = null
        }
    }

    private suspend fun syncOnce() {
        val sdkStatus = HealthConnectClient.getSdkStatus(appContext, PROVIDER_PACKAGE_NAME)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            reportState(
                state = "sdk_unavailable_$sdkStatus",
                warn = true,
                metadata = mapOf("sdkStatus" to sdkStatus)
            )
            return
        }

        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }
            .getOrElse { error ->
                reportState(
                    state = "client_unavailable",
                    warn = true,
                    metadata = mapOf("error" to (error.message ?: error::class.java.simpleName))
                )
                return
            }

        val grantedPermissions = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrElse { error ->
                reportState(
                    state = "permission_check_failed",
                    warn = true,
                    metadata = mapOf("error" to (error.message ?: error::class.java.simpleName))
                )
                return
            }

        if (!grantedPermissions.containsAll(REQUIRED_PERMISSIONS)) {
            val missing = REQUIRED_PERMISSIONS - grantedPermissions
            reportState(
                state = "permission_missing",
                warn = true,
                metadata = mapOf("missingPermissions" to missing.sorted().joinToString(","))
            )
            return
        }

        val now = Instant.now()
        val nowTs = System.currentTimeMillis()
        val dayStart = now
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        val recentStart = now.minus(Duration.ofMinutes(15))

        val todayStepsRecords = readRecords<StepsRecord>(client, dayStart, now)
        val recentStepsRecords = readRecords<StepsRecord>(client, recentStart, now)
        val distanceRecords = readRecords<DistanceRecord>(client, dayStart, now)
        val activeCaloriesRecords = readRecords<ActiveCaloriesBurnedRecord>(client, dayStart, now)

        val stepsToday = todayStepsRecords.sumOf { it.count.toDouble() }.coerceAtLeast(0.0)
        val distanceKm = distanceRecords.sumOf { it.distance.inKilometers }.coerceAtLeast(0.0)
        val activeCalories = activeCaloriesRecords.sumOf { it.energy.inKilocalories }.coerceAtLeast(0.0)
        val activeMinutes = estimateActiveMinutes(todayStepsRecords)

        val recentSteps = recentStepsRecords.sumOf { it.count.toDouble() }.coerceAtLeast(0.0)
        val pacePerMinute = recentSteps / RECENT_WINDOW_MINUTES.toDouble()
        val activityRatio = ActivityIntensity.ratioFromPacePerMinute(pacePerMinute)
        val activityLabel = ActivityIntensity.labelFromRatio(activityRatio)

        val payload = linkedMapOf(
            "steps" to format(stepsToday),
            "distanceKm" to format(distanceKm),
            "activeMinutes" to format(activeMinutes),
            "activeCalories" to format(activeCalories),
            "activityRatio" to format(activityRatio),
            "activityType" to activityLabel
        )
        val telemetry = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = nowTs,
            source = SOURCE,
            values = payload
        )
        if (telemetry.isEmpty()) return

        db.telemetryDao().upsertAll(telemetry)
        WorkScheduler.triggerReactiveAutomation(appContext)
        reportState(
            state = "ok",
            metadata = mapOf(
                "steps" to stepsToday,
                "distanceKm" to distanceKm,
                "activeMinutes" to activeMinutes,
                "activityRatio" to activityRatio
            )
        )
    }

    private fun estimateActiveMinutes(records: List<StepsRecord>): Double {
        val minutes = records.sumOf { record ->
            val durationMin = Duration.between(record.startTime, record.endTime).toMinutes()
                .coerceAtLeast(1L)
                .toDouble()
            val pacePerMinute = record.count.toDouble() / durationMin
            if (pacePerMinute >= ACTIVE_PACE_THRESHOLD_STEPS_PER_MIN) {
                durationMin.coerceAtMost(15.0)
            } else {
                0.0
            }
        }
        return minutes.coerceIn(0.0, 1_440.0)
    }

    private suspend inline fun <reified T : Record> readRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant
    ): List<T> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = T::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return response.records
    }

    private suspend fun reportState(
        state: String,
        warn: Boolean = false,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        if (lastState == state) return
        lastState = state
        val data = mapOf("state" to state) + metadata
        if (warn) {
            auditLogger.warn("health_connect_activity_status", data)
        } else {
            auditLogger.info("health_connect_activity_status", data)
        }
    }

    private fun millisUntilNextBoundary(): Long {
        val now = System.currentTimeMillis()
        val next = ((now / SYNC_INTERVAL_MS) + 1L) * SYNC_INTERVAL_MS
        return (next - now).coerceIn(5_000L, SYNC_INTERVAL_MS)
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    companion object {
        private const val SOURCE = "health_connect"
        private const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"
        private const val SYNC_INTERVAL_MS = 5 * 60_000L
        private const val RECENT_WINDOW_MINUTES = 15L
        private const val ACTIVE_PACE_THRESHOLD_STEPS_PER_MIN = 20.0

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        )
    }
}

