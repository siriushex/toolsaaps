package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.PhysicalActivityBucketProjection
import io.aaps.copilot.data.local.dao.PhysicalActivityMetricBucketProjection
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import com.google.gson.Gson
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PhysicalActivityTelemetryDaoRoomTest {

    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rapidLocalChangesKeepLatestDurableRatioPerMinuteAndRolloverAddsOne() = runBlocking {
        val minuteStart = 20L * MINUTE_MS
        listOf(
            activity("volatile-a", minuteStart + 1_000L, "local_sensor", 1.10),
            activity("volatile-b", minuteStart + 20_000L, "local_sensor", 2.00),
            activity("volatile-c", minuteStart + 50_000L, "local_sensor", 1.00)
        ).forEach { row -> db.telemetryDao().upsertPhysicalActivityMinute(listOf(row)) }

        var rows = db.telemetryDao().betweenForClinicalReport(
            minuteStart,
            minuteStart + 59_999L,
            listOf("activity_ratio")
        )
        assertThat(rows).hasSize(1)
        assertThat(rows.single().ts).isEqualTo(minuteStart + 50_000L)
        assertThat(rows.single().value).isEqualTo(1.00)

        db.telemetryDao().upsertPhysicalActivityMinute(
            listOf(activity("volatile-next", minuteStart + MINUTE_MS, "local_sensor", 1.18))
        )
        rows = db.telemetryDao().betweenForClinicalReport(
            minuteStart,
            minuteStart + MINUTE_MS,
            listOf("activity_ratio")
        )
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.rowId }.distinct()).hasSize(2)
    }

    @Test
    fun minuteStorageRejectsDerivedPeakAsNonCanonicalRuntimeTelemetry() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                db.telemetryDao().upsertPhysicalActivityMinute(
                    listOf(
                        metric(
                            "derived-peak",
                            MINUTE_MS,
                            "local_sensor",
                            "activity_ratio_peak",
                            2.0,
                            "ratio",
                            "TRUSTED"
                        )
                    )
                )
            }
        }
        assertThat(runBlocking { db.telemetryDao().byId("derived-peak") }).isNull()
    }

    @Test
    fun collectorPayloadBoundaryPersistsFiveLatestCanonicalRowsAndBoundedLabelOnly() = runBlocking {
        val minuteStart = 40L * MINUTE_MS
        val sources = listOf("local_sensor", "health_connect")

        sources.forEachIndexed { index, source ->
            val first = TelemetryMetricMapper.fromPhysicalActivityKeyValueMap(
                timestamp = minuteStart + index * 1_000L,
                source = source,
                values = collectorPayload(
                    steps = 100.0,
                    ratio = 1.1,
                    label = "x".repeat(100)
                )
            )
            db.telemetryDao().upsertPhysicalActivityMinute(first.persistedMetrics)
            db.telemetryDao().upsertAll(first.labels)

            val latestTimestamp = minuteStart + 30_000L + index * 1_000L
            val latest = TelemetryMetricMapper.fromPhysicalActivityKeyValueMap(
                timestamp = latestTimestamp,
                source = source,
                values = collectorPayload(
                    steps = 125.0,
                    ratio = 1.35,
                    label = "$source-moving"
                )
            )
            db.telemetryDao().upsertPhysicalActivityMinute(latest.persistedMetrics)
            db.telemetryDao().upsertAll(latest.labels)
        }

        val rows = db.telemetryDao().since(minuteStart)
        sources.forEach { source ->
            val sourceRows = rows.filter { it.source == source }
            val canonical = sourceRows.filter {
                it.key in PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS
            }
            assertThat(canonical.map { it.key })
                .containsExactlyElementsIn(PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS)
            assertThat(canonical).hasSize(5)
            assertThat(canonical.single { it.key == "steps_count" }.valueDouble).isEqualTo(125.0)
            assertThat(canonical.single { it.key == "activity_ratio" }.valueDouble).isEqualTo(1.35)

            val labels = sourceRows.filter { it.key == "activity_label" }
            assertThat(labels).isNotEmpty()
            assertThat(labels.all { (it.valueText?.length ?: 0) in 1..64 }).isTrue()
            assertThat(labels.maxBy { it.timestamp }.valueText).isEqualTo("$source-moving")
            assertThat(sourceRows.any { it.key.startsWith("raw_") }).isFalse()
        }
        Unit
    }

    @Test
    fun thirtyDayDenseRowsProjectToAtMost8640BucketsPerTrustedSourceAndExcludeAutosens() = runBlocking {
        val fromTs = 100L * DAY_MS
        val throughTsExclusive = fromTs + 30L * DAY_MS
        val trustedLocal = (0 until 30 * 24 * 60).map { minute ->
            activity(
                id = "local-$minute",
                ts = fromTs + minute.toLong() * MINUTE_MS,
                source = "local_sensor",
                ratio = 1.0 + (minute % 9) / 10.0
            )
        }
        val trustedHealth = (0 until 30 * 24 * 12).map { bucket ->
            activity(
                id = "health-$bucket",
                ts = fromTs + bucket.toLong() * FIVE_MINUTES_MS + MINUTE_MS,
                source = "health_connect",
                ratio = 1.1 + (bucket % 5) / 10.0,
                quality = if (bucket % 2 == 0) "TRUSTED" else "OK"
            )
        }
        val foreignAutosens = (0 until 30 * 24 * 60).map { minute ->
            activity(
                id = "aaps-$minute",
                ts = fromTs + minute.toLong() * MINUTE_MS,
                source = "aaps",
                ratio = 1.8
            )
        }
        db.telemetryDao().upsertAll(trustedLocal + trustedHealth + foreignAutosens)

        val buckets = db.telemetryDao().physicalActivity5MinuteBuckets(
            fromTs = fromTs,
            toTsExclusive = throughTsExclusive,
            key = PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY,
            sources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.toList().sorted(),
            qualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.toList().sorted(),
            bucketMs = FIVE_MINUTES_MS
        )

        assertThat(buckets.map { it.source }.distinct())
            .containsExactly("health_connect", "local_sensor")
        assertThat(buckets.groupingBy { it.source }.eachCount())
            .containsExactly("health_connect", 8_640, "local_sensor", 8_640)
        assertThat(buckets).hasSize(17_280)
        assertThat(buckets).containsExactlyElementsIn(
            buckets.sortedWith(compareBy<PhysicalActivityBucketProjection> { it.bucketTs }.thenBy { it.source })
        ).inOrder()
        val firstLocal = buckets.first { it.source == "local_sensor" }
        assertThat(firstLocal.firstTs).isEqualTo(fromTs)
        assertThat(firstLocal.lastTs).isEqualTo(fromTs + 4L * MINUTE_MS)
        assertThat(firstLocal.sampleCount).isEqualTo(5)
        assertThat(firstLocal.peakRatio).isEqualTo(1.4)
        assertThat(firstLocal.meanRatio).isWithin(0.000_001).of(1.2)
    }

    @Test
    fun allPhysicalMetricsUseBoundedFiveMinuteSqlProjectionWithExactSentinels() = runBlocking {
        val fromTs = 140L * DAY_MS
        val toTsExclusive = fromTs + 30L * DAY_MS
        val units = mapOf(
            "activity_ratio" to "ratio",
            "steps_count" to "steps",
            "distance_km" to "km",
            "active_minutes" to "min",
            "calories_active_kcal" to "kcal"
        )
        val dense = PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS.flatMap { key ->
            PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.flatMap { source ->
                (0 until 30 * 24 * 12).map { bucket ->
                    val base = when (key) {
                        "activity_ratio" -> 1.2
                        "steps_count" -> bucket * 10.0
                        "distance_km" -> bucket / 100.0
                        "active_minutes" -> bucket / 12.0
                        else -> bucket / 4.0
                    }
                    metric(
                        id = "$source-$key-$bucket",
                        ts = fromTs + bucket.toLong() * FIVE_MINUTES_MS + MINUTE_MS,
                        source = source,
                        key = key,
                        value = base,
                        unit = units.getValue(key),
                        quality = if (source == "health_connect") "TRUSTED" else "OK"
                    )
                }
            }
        }
        db.telemetryDao().upsertAll(dense)
        db.telemetryDao().upsertAll(
            listOf(
                metric("sentinel-first", fromTs + 1_000L, "local_sensor", "steps_count", 10.0, "steps", "OK"),
                metric("sentinel-peak", fromTs + 2L * MINUTE_MS, "local_sensor", "steps_count", 40.0, "steps", "TRUSTED"),
                metric("sentinel-last", fromTs + 4L * MINUTE_MS, "local_sensor", "steps_count", 30.0, "steps", "OK"),
                metric("foreign", fromTs + MINUTE_MS, "aaps", "steps_count", 999_999.0, "steps", "OK"),
                metric("stale", fromTs + MINUTE_MS, "health_connect", "steps_count", 999_999.0, "steps", "STALE")
            )
        )

        val buckets = db.telemetryDao().physicalActivityMetric5MinuteBuckets(
            fromTs = fromTs,
            toTsExclusive = toTsExclusive,
            firstBucketTs = fromTs,
            keys = PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS.sorted(),
            sources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.sorted(),
            qualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.sorted(),
            bucketMs = FIVE_MINUTES_MS
        )

        assertThat(buckets).hasSize(86_400)
        assertThat(buckets.groupingBy { it.source to it.key }.eachCount().values.distinct())
            .containsExactly(8_640)
        assertThat(buckets.map { it.source }.distinct()).containsExactly("health_connect", "local_sensor")
        val sentinel = buckets.single {
            it.bucketTs == fromTs && it.source == "local_sensor" && it.key == "steps_count"
        }
        assertThat(sentinel.firstTs).isEqualTo(fromTs + 1_000L)
        assertThat(sentinel.lastTs).isEqualTo(fromTs + 4L * MINUTE_MS)
        assertThat(sentinel.firstValue).isEqualTo(10.0)
        assertThat(sentinel.lastValue).isEqualTo(30.0)
        assertThat(sentinel.maxValue).isEqualTo(40.0)
        assertThat(sentinel.sampleCount).isEqualTo(4)
        assertThat(sentinel.qualityEvidence).isEqualTo("TRUSTED")
        assertThat(buckets).containsExactlyElementsIn(
            buckets.sortedWith(
                compareBy<PhysicalActivityMetricBucketProjection> { it.bucketTs }
                    .thenBy { it.source }
                    .thenBy { it.key }
            )
        ).inOrder()
    }

    @Test
    fun cleanupKeepsEveryTrustedPersistedPhysicalMetricFor45DaysButOrdinaryRowsFor7Days() = runBlocking {
        val nowTs = 200L * DAY_MS
        val exact45d = nowTs - 45L * DAY_MS
        db.telemetryDao().upsertAll(
            PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS.flatMapIndexed { index, key ->
                val unit = when (key) {
                    "activity_ratio" -> "ratio"
                    "steps_count" -> "steps"
                    "distance_km" -> "km"
                    "active_minutes" -> "min"
                    else -> "kcal"
                }
                listOf(
                    metric("physical-7d-$key", nowTs - 7L * DAY_MS, "local_sensor", key, 1.0 + index, unit, "OK"),
                    metric("physical-30d-$key", nowTs - 30L * DAY_MS, "health_connect", key, 2.0 + index, unit, "TRUSTED"),
                    metric("physical-45d-$key", exact45d, "local_sensor", key, 3.0 + index, unit, "OK"),
                    metric("physical-46d-$key", nowTs - 46L * DAY_MS, "health_connect", key, 4.0 + index, unit, "TRUSTED")
                )
            } + listOf(
                activity("physical-untrusted", nowTs - 8L * DAY_MS, "local_sensor", 1.5, "STALE"),
                activity("autosens-old", nowTs - 8L * DAY_MS, "aaps", 1.6),
                metric("derived-peak-old", nowTs - 8L * DAY_MS, "local_sensor", "activity_ratio_peak", 1.8, "ratio", "OK"),
                telemetry("ordinary-new", nowTs - 6L * DAY_MS),
                telemetry("ordinary-old", nowTs - 8L * DAY_MS)
            )
        )

        db.telemetryDao().deleteOlderThanWithReportProfileAndPhysicalActivityRetentionLimit(
            generalOlderThan = nowTs - 7L * DAY_MS,
            reportOlderThan = nowTs - 45L * DAY_MS,
            physicalActivityOlderThan = exact45d,
            physicalActivityKeys = PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS.sorted(),
            physicalActivitySources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.toList(),
            physicalActivityQualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.toList(),
            limit = 100
        )

        val ids = db.telemetryDao().since(0L).map { it.id }
        PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS.forEach { key ->
            assertThat(ids).containsAtLeast(
                "physical-7d-$key",
                "physical-30d-$key",
                "physical-45d-$key"
            )
            assertThat(ids).doesNotContain("physical-46d-$key")
        }
        assertThat(ids).contains("ordinary-new")
        assertThat(ids).containsNoneOf(
            "physical-untrusted",
            "autosens-old",
            "derived-peak-old",
            "ordinary-old"
        )
        Unit
    }

    @Test
    fun clinicalReportUsesTrustedSqlBucketsAndNeverTreatsAapsAutosensAsPhysicalActivity() = runBlocking {
        val nowTs = 300L * DAY_MS
        val bucketStart = nowTs - 10L * MINUTE_MS
        db.telemetryDao().upsertAll(
            (0 until 5).map { minute ->
                activity(
                    id = "local-report-$minute",
                    ts = bucketStart + minute * MINUTE_MS,
                    source = "local_sensor",
                    ratio = 1.1 + minute / 10.0,
                    quality = if (minute == 4) "TRUSTED" else "OK"
                )
            } + listOf(
                activity("aaps-autosens", bucketStart + 2L * MINUTE_MS, "aaps", 2.5),
                activity("stale-local", bucketStart + 3L * MINUTE_MS, "local_sensor", 2.4, "STALE")
            )
        )
        val gson = Gson()
        val calibration = GlucoseCalibrationRepository(
            db = db,
            gson = gson,
            auditLogger = AuditLogger(db.auditLogDao(), gson) { nowTs }
        )

        val payload = ClinicalReportDatasetBuilder(db, calibration).build(nowTs, ZoneId.of("UTC"))
        val activity = payload.dataset.detail24h.telemetry.filter {
            it.key == "activity_ratio" || it.key == "activity_ratio_peak"
        }

        assertThat(activity.map { it.key }).containsExactly("activity_ratio", "activity_ratio_peak")
        assertThat(activity.map { it.origin }.distinct())
            .containsExactly(io.aaps.copilot.data.repository.ClinicalTelemetryOrigin.LOCAL_ACTIVITY)
        assertThat(activity.map { it.quality }.distinct()).containsExactly("TRUSTED")
        assertThat(activity.single { it.key == "activity_ratio" }.value).isWithin(0.000_001).of(1.3)
        assertThat(activity.single { it.key == "activity_ratio_peak" }.value).isEqualTo(1.5)
        Unit
    }

    @Test
    fun clinicalReportAppliesExactSourceAndQualityPolicyToEveryPhysicalMetric() = runBlocking {
        val nowTs = 320L * DAY_MS
        val signalTs = nowTs - MINUTE_MS
        db.telemetryDao().upsertAll(
            listOf(
                metric("trusted-steps", signalTs, "local_sensor", "steps_count", 1_200.0, "steps", "TRUSTED"),
                metric("trusted-distance", signalTs, "health_connect", "distance_km", 3.5, "km", "OK"),
                metric("alias-steps", signalTs, "phone_activity_sensor", "steps_count", 99_999.0, "steps", "OK"),
                metric("aaps-active", signalTs, "aaps", "active_minutes", 900.0, "min", "OK"),
                metric("stale-calories", signalTs, "health_connect", "calories_active_kcal", 8_000.0, "kcal", "STALE"),
                metric("ordinary-basal", signalTs, "aaps", "basal_rate_u_h", 0.7, "U/h", "STALE")
            )
        )
        val gson = Gson()
        val calibration = GlucoseCalibrationRepository(
            db = db,
            gson = gson,
            auditLogger = AuditLogger(db.auditLogDao(), gson) { nowTs }
        )

        val detail = ClinicalReportDatasetBuilder(db, calibration)
            .build(nowTs, ZoneId.of("UTC"))
            .dataset.detail24h.telemetry

        assertThat(detail.filter { it.key in setOf("steps_count", "distance_km") }
            .map { Triple(it.key, it.value, it.quality) })
            .containsExactly(
                Triple("steps_count", 1_200.0, "TRUSTED"),
                Triple("distance_km", 3.5, "OK")
            )
        assertThat(detail.map { it.key }).contains("basal_rate_u_h")
        assertThat(detail.single { it.key == "basal_rate_u_h" }.quality).isEqualTo("STALE")
        assertThat(detail.map { it.key }).containsNoneOf("active_minutes", "calories_active_kcal")
        Unit
    }

    @Test
    fun productionThirtyDayWindowExcludesOldBoundaryAndKeepsNewestBoundary() = runBlocking {
        val nowTs = 360L * DAY_MS
        val from30d = nowTs - 30L * DAY_MS
        db.telemetryDao().upsertAll(
            listOf(
                activity("old-boundary", from30d, "local_sensor", 3.0),
                activity("first-admissible-bucket", from30d + FIVE_MINUTES_MS, "local_sensor", 1.0),
                activity("new-boundary", nowTs, "local_sensor", 2.0)
            )
        )
        val gson = Gson()
        val calibration = GlucoseCalibrationRepository(
            db = db,
            gson = gson,
            auditLogger = AuditLogger(db.auditLogDao(), gson) { nowTs }
        )

        val activity = ClinicalReportDatasetBuilder(db, calibration)
            .build(nowTs, ZoneId.of("UTC"))
            .dataset.summary30d.activity

        assertThat(activity.meanActivityRatio).isWithin(0.000_001).of(1.5)
        assertThat(activity.maxActivityRatio).isEqualTo(2.0)
        Unit
    }

    @Test
    fun clinicalReportPreservesPartialDayCumulativeTotalsWithoutCountingDetailTwice() = runBlocking {
        val nowTs = 500L * DAY_MS + 12L * 60L * MINUTE_MS
        val from30d = nowTs - 30L * DAY_MS
        val nextDayStart = from30d + 12L * 60L * MINUTE_MS
        db.telemetryDao().upsertAll(
            listOf(
                metric("partial-first", from30d + MINUTE_MS, "local_sensor", "steps_count", 100.0, "steps", "OK"),
                metric("partial-peak", from30d + 2L * MINUTE_MS, "local_sensor", "steps_count", 500.0, "steps", "TRUSTED"),
                metric("partial-last", from30d + 4L * MINUTE_MS, "local_sensor", "steps_count", 300.0, "steps", "OK"),
                metric("full-first", nextDayStart + MINUTE_MS, "local_sensor", "steps_count", 50.0, "steps", "OK"),
                metric("full-peak", nextDayStart + 2L * MINUTE_MS, "local_sensor", "steps_count", 650.0, "steps", "TRUSTED"),
                metric("full-last", nextDayStart + 4L * MINUTE_MS, "local_sensor", "steps_count", 600.0, "steps", "OK"),
                metric("today-first", nowTs - 10L * MINUTE_MS, "local_sensor", "steps_count", 70.0, "steps", "OK"),
                metric("today-last", nowTs - 5L * MINUTE_MS, "local_sensor", "steps_count", 320.0, "steps", "OK")
            )
        )
        val gson = Gson()
        val calibration = GlucoseCalibrationRepository(
            db = db,
            gson = gson,
            auditLogger = AuditLogger(db.auditLogDao(), gson) { nowTs }
        )

        val dataset = ClinicalReportDatasetBuilder(db, calibration)
            .build(nowTs, ZoneId.of("UTC"))
            .dataset

        assertThat(dataset.summary30d.activity.steps).isEqualTo(1_370.0)
        assertThat(dataset.summary7d.activity.steps).isEqualTo(320.0)
        assertThat(checkNotNull(dataset.summary24h).activity.steps).isEqualTo(320.0)
        assertThat(dataset.detail24h.telemetry.filter { it.key == "steps_count" }.map { it.value })
            .containsExactly(70.0, 320.0)
            .inOrder()
    }

    private fun activity(
        id: String,
        ts: Long,
        source: String,
        ratio: Double,
        quality: String = "OK"
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = ts,
        source = source,
        key = "activity_ratio",
        valueDouble = ratio,
        valueText = null,
        unit = "ratio",
        quality = quality
    )

    private fun telemetry(id: String, ts: Long) = TelemetrySampleEntity(
        id = id,
        timestamp = ts,
        source = "runtime",
        key = "other_metric",
        valueDouble = 1.0,
        valueText = null,
        unit = null,
        quality = "OK"
    )

    private fun collectorPayload(
        steps: Double,
        ratio: Double,
        label: String
    ): Map<String, String> = linkedMapOf(
        "steps" to steps.toString(),
        "distanceKm" to "1.25",
        "activeMinutes" to "18.0",
        "activeCalories" to "90.0",
        "activityRatio" to ratio.toString(),
        "activityType" to label
    )

    private fun metric(
        id: String,
        ts: Long,
        source: String,
        key: String,
        value: Double,
        unit: String?,
        quality: String
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = ts,
        source = source,
        key = key,
        valueDouble = value,
        valueText = null,
        unit = unit,
        quality = quality
    )

    private companion object {
        const val MINUTE_MS = 60_000L
        const val FIVE_MINUTES_MS = 5L * MINUTE_MS
        const val DAY_MS = 24L * 60L * MINUTE_MS
    }
}
