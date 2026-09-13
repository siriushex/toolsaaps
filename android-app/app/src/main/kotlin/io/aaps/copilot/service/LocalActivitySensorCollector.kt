package io.aaps.copilot.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.TelemetryMetricMapper
import io.aaps.copilot.domain.activity.ActivityIntensity
import io.aaps.copilot.domain.activity.LocalActivityMetricsEstimator
import io.aaps.copilot.domain.activity.LocalActivitySnapshot
import io.aaps.copilot.domain.activity.LocalActivityState
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal suspend fun persistLocalActivityClinicalInput(
    shouldPersist: Boolean,
    shouldInvalidate: Boolean,
    persist: suspend () -> Boolean,
    onClinicalInputPersisted: suspend () -> Unit
): Boolean = persistPhysicalActivityClinicalInput(
    shouldPersist = shouldPersist,
    shouldInvalidate = shouldInvalidate,
    persist = persist,
    onClinicalInputPersisted = onClinicalInputPersisted
)

internal suspend fun persistHealthConnectActivityClinicalInput(
    shouldPersist: Boolean,
    persist: suspend () -> Boolean,
    onClinicalInputPersisted: suspend () -> Unit
): Boolean = persistPhysicalActivityClinicalInput(
    shouldPersist = shouldPersist,
    shouldInvalidate = true,
    persist = persist,
    onClinicalInputPersisted = onClinicalInputPersisted
)

private suspend fun persistPhysicalActivityClinicalInput(
    shouldPersist: Boolean,
    shouldInvalidate: Boolean,
    persist: suspend () -> Boolean,
    onClinicalInputPersisted: suspend () -> Unit
): Boolean {
    if (!shouldPersist) return false
    if (!persist()) return false
    if (shouldInvalidate) onClinicalInputPersisted()
    return true
}

class LocalActivitySensorCollector(
    private val context: Context,
    private val db: CopilotDatabase,
    private val auditLogger: AuditLogger,
    private val onClinicalInputPersisted: suspend () -> Unit = {}
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val estimator = LocalActivityMetricsEstimator()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val stateLock = Any()

    @Volatile
    private var registered = false

    private var state: LocalActivityState = readState()
    private var lastPersistAtMs: Long = 0L
    private var lastPersistSteps: Double = -1.0
    private var lastPersistActiveMinutes: Double = -1.0
    private var lastPersistActivityRatio: Double = -1.0
    private var heartbeatJob: Job? = null

    private val stepCounterSensor: Sensor? by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    fun start() {
        synchronized(stateLock) {
            if (registered) return
            if (sensorManager == null) {
                logWarn("local_activity_sensor_unavailable", mapOf("reason" to "sensor_manager_null"))
                return
            }
            if (!hasPermission()) {
                logWarn("local_activity_sensor_permission_missing", mapOf("permission" to "ACTIVITY_RECOGNITION"))
                return
            }
            val sensor = stepCounterSensor
            if (sensor == null) {
                logWarn("local_activity_sensor_unavailable", mapOf("reason" to "step_counter_absent"))
                return
            }
            val ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            registered = ok
            if (ok) {
                logInfo("local_activity_sensor_started", mapOf("sensor" to sensor.name))
                seedBaselineSnapshot()
                cleanupLegacyRawLocalSensorRows()
                startHeartbeat()
            } else {
                logWarn("local_activity_sensor_start_failed", mapOf("reason" to "register_listener_failed"))
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            if (!registered) return
            runCatching { sensorManager?.unregisterListener(this) }
            registered = false
            heartbeatJob?.cancel()
            heartbeatJob = null
            logInfo("local_activity_sensor_stopped", emptyMap<String, Any>())
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        if (sensorEvent.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val counterTotal = sensorEvent.values.firstOrNull()?.toDouble() ?: return
        val nowTs = System.currentTimeMillis()

        val (updatedState, snapshot) = synchronized(stateLock) {
            estimator.update(
                counterTotal = counterTotal,
                timestamp = nowTs,
                previous = state
            ).also { state = it.first }
        }
        writeState(updatedState)
        persistSnapshot(snapshot = snapshot, nowTs = nowTs, force = false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun readState(): LocalActivityState {
        val raw = prefs.getString(PREF_STATE_JSON, null) ?: return LocalActivityState()
        return runCatching {
            val obj = JSONObject(raw)
            LocalActivityState(
                dayStartTs = obj.optLong("dayStartTs", 0L),
                baselineCounter = obj.optDouble("baselineCounter", 0.0),
                lastCounter = obj.optDouble("lastCounter", 0.0),
                lastTs = obj.optLong("lastTs", 0L),
                activeMinutesToday = obj.optDouble("activeMinutesToday", 0.0)
            )
        }.getOrDefault(LocalActivityState())
    }

    private fun writeState(newState: LocalActivityState) {
        val obj = JSONObject(
            mapOf(
                "dayStartTs" to newState.dayStartTs,
                "baselineCounter" to newState.baselineCounter,
                "lastCounter" to newState.lastCounter,
                "lastTs" to newState.lastTs,
                "activeMinutesToday" to newState.activeMinutesToday
            )
        )
        prefs.edit().putString(PREF_STATE_JSON, obj.toString()).apply()
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun inferActivityLabel(activityRatio: Double): String =
        ActivityIntensity.labelFromRatio(activityRatio)

    private fun seedBaselineSnapshot() {
        val nowTs = System.currentTimeMillis()
        val (updatedState, snapshot) = synchronized(stateLock) {
            estimator.update(
                counterTotal = state.lastCounter.coerceAtLeast(0.0),
                timestamp = nowTs,
                previous = state
            ).also { state = it.first }
        }
        writeState(updatedState)
        persistSnapshot(snapshot = snapshot, nowTs = nowTs, force = true)
    }

    private fun persistSnapshot(
        snapshot: LocalActivitySnapshot,
        nowTs: Long,
        force: Boolean
    ) {
        val decision = synchronized(stateLock) {
            val stepsDelta = if (lastPersistSteps < 0.0) {
                Double.MAX_VALUE
            } else {
                snapshot.stepsToday - lastPersistSteps
            }
            val activeMinutesDelta = if (lastPersistActiveMinutes < 0.0) {
                Double.MAX_VALUE
            } else {
                snapshot.activeMinutesToday - lastPersistActiveMinutes
            }
            val activityRatioDelta = if (lastPersistActivityRatio < 0.0) {
                Double.MAX_VALUE
            } else {
                abs(snapshot.activityRatio - lastPersistActivityRatio)
            }
            val meaningfulChange = stepsDelta >= MIN_STEP_DELTA_TO_PERSIST ||
                activeMinutesDelta >= MIN_ACTIVE_MINUTES_DELTA_TO_PERSIST ||
                activityRatioDelta >= MIN_ACTIVITY_RATIO_DELTA_TO_PERSIST
            val heartbeatDue = nowTs - lastPersistAtMs >= ACTIVITY_HEARTBEAT_INTERVAL_MS
            if (force) {
                lastPersistSteps = snapshot.stepsToday
                lastPersistActiveMinutes = snapshot.activeMinutesToday
                lastPersistActivityRatio = snapshot.activityRatio
                lastPersistAtMs = nowTs
                PersistDecision(shouldPersist = true, triggerAutomation = true)
            } else if (meaningfulChange || heartbeatDue) {
                lastPersistSteps = snapshot.stepsToday
                lastPersistActiveMinutes = snapshot.activeMinutesToday
                lastPersistActivityRatio = snapshot.activityRatio
                lastPersistAtMs = nowTs
                PersistDecision(shouldPersist = true, triggerAutomation = meaningfulChange || heartbeatDue)
            } else {
                PersistDecision(shouldPersist = false, triggerAutomation = false)
            }
        }
        scope.launch {
            val persisted = persistLocalActivityClinicalInput(
                shouldPersist = decision.shouldPersist,
                shouldInvalidate = decision.triggerAutomation,
                persist = {
                    val payload = linkedMapOf(
                        "steps" to format(snapshot.stepsToday),
                        "distanceKm" to format(snapshot.distanceKmToday),
                        "activeMinutes" to format(snapshot.activeMinutesToday),
                        "activeCalories" to format(snapshot.activeCaloriesKcalToday),
                        "activityRatio" to format(snapshot.activityRatio),
                        "activityType" to inferActivityLabel(snapshot.activityRatio)
                    )
                    val telemetry = TelemetryMetricMapper.fromPhysicalActivityKeyValueMap(
                        timestamp = nowTs,
                        source = SOURCE,
                        values = payload
                    )
                    if (telemetry.persistedMetrics.isEmpty()) return@persistLocalActivityClinicalInput false
                    db.telemetryDao().upsertPhysicalActivityMinute(telemetry.persistedMetrics)
                    if (telemetry.labels.isNotEmpty()) {
                        db.telemetryDao().upsertAll(telemetry.labels)
                    }
                    true
                },
                onClinicalInputPersisted = onClinicalInputPersisted
            )
            if (force && persisted) {
                logInfo(
                    "local_activity_sensor_seeded",
                    mapOf(
                        "steps" to snapshot.stepsToday,
                        "activeMinutes" to snapshot.activeMinutesToday,
                        "activityRatio" to snapshot.activityRatio
                    )
                )
            }
        }
    }

    private fun logInfo(message: String, metadata: Map<String, Any?>) {
        scope.launch {
            auditLogger.info(message, metadata)
        }
    }

    private fun logWarn(message: String, metadata: Map<String, Any?>) {
        scope.launch {
            auditLogger.warn(message, metadata)
        }
    }

    private fun cleanupLegacyRawLocalSensorRows() {
        scope.launch {
            val deleted = db.telemetryDao().deleteBySourceAndKeyLike(SOURCE, "raw_%")
            if (deleted > 0) {
                auditLogger.info(
                    "local_activity_sensor_raw_cleanup",
                    mapOf("deleted" to deleted)
                )
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(millisUntilNextMinuteBoundary())
                if (!registered || !isActive) continue
                emitHeartbeatSnapshot()
            }
        }
    }

    private fun emitHeartbeatSnapshot() {
        val nowTs = System.currentTimeMillis()
        val (updatedState, snapshot) = synchronized(stateLock) {
            estimator.update(
                counterTotal = state.lastCounter.coerceAtLeast(0.0),
                timestamp = nowTs,
                previous = state
            ).also { state = it.first }
        }
        writeState(updatedState)
        persistSnapshot(snapshot = snapshot, nowTs = nowTs, force = false)
    }

    private fun millisUntilNextMinuteBoundary(): Long {
        val now = System.currentTimeMillis()
        val nextMinute = ((now / 60_000L) + 1L) * 60_000L
        return (nextMinute - now).coerceIn(1_000L, 60_000L)
    }

    private data class PersistDecision(
        val shouldPersist: Boolean,
        val triggerAutomation: Boolean
    )

    private companion object {
        private const val PREFS_NAME = "local_activity_sensor_collector"
        private const val PREF_STATE_JSON = "state_json"
        private const val SOURCE = PhysicalActivityTelemetryPolicy.LOCAL_SENSOR_SOURCE
        private const val MIN_STEP_DELTA_TO_PERSIST = 10.0
        private const val MIN_ACTIVE_MINUTES_DELTA_TO_PERSIST = 1.0
        private const val MIN_ACTIVITY_RATIO_DELTA_TO_PERSIST = 0.05
        private const val ACTIVITY_HEARTBEAT_INTERVAL_MS = 5 * 60_000L
    }
}
