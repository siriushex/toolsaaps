package io.aaps.copilot.data.repository

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class AapsClinicalSummaryRequest(
    val nonce: String,
    val detailFromTs: Long,
    val from7d: Long,
    val from30d: Long,
    val throughTs: Long
)

internal object AapsClinicalSummaryContract {
    const val REQUEST_PERMISSION =
        "info.nightscout.androidaps.permission.READ_COPILOT_CLINICAL_SUMMARY"
    const val RESPONSE_PERMISSION =
        "info.nightscout.androidaps.permission.RELAY_COPILOT_DATA"
    const val REQUEST_ACTION =
        "info.nightscout.androidaps.action.READ_COPILOT_CLINICAL_SUMMARY"
    const val RESPONSE_ACTION =
        "io.aaps.predictivecopilot.action.AAPS_CLINICAL_SUMMARY"

    const val EXTRA_NONCE = "nonce"
    const val EXTRA_THROUGH_TS = "throughTs"
    const val EXTRA_FROM_24H_TS = "from24hTs"
    const val EXTRA_FROM_7D_TS = "from7dTs"
    const val EXTRA_FROM_30D_TS = "from30dTs"
    const val EXTRA_GENERATED_AT = "generatedAt"

    private const val AAPS_PACKAGE = "info.nightscout.androidaps"
    private const val RECEIVER_CLASS = "app.aaps.receivers.CopilotClinicalSummaryReceiver"
    private const val DAY_MS = 24L * 60L * 60L * 1_000L
    private const val WINDOW_TOLERANCE_MS = 1_000L

    fun requestIntent(request: AapsClinicalSummaryRequest): Intent =
        Intent(REQUEST_ACTION)
            .setComponent(ComponentName(AAPS_PACKAGE, RECEIVER_CLASS))
            .putExtra(EXTRA_NONCE, request.nonce)
            .putExtra(EXTRA_THROUGH_TS, request.throughTs)
            .putExtra(EXTRA_FROM_24H_TS, request.detailFromTs)
            .putExtra(EXTRA_FROM_7D_TS, request.from7d)
            .putExtra(EXTRA_FROM_30D_TS, request.from30d)

    fun validateSnapshot(
        request: AapsClinicalSummaryRequest,
        snapshot: ClinicalAapsTddSnapshot
    ): ClinicalAapsTddSnapshot? {
        if (snapshot.generatedAt !in request.throughTs..request.throughTs + MAX_RESPONSE_SKEW_MS) {
            return null
        }
        val detail = validatePeriod(
            snapshot.detail24h,
            request.detailFromTs,
            request.throughTs,
            DAY_MS
        )
        val period7d = validatePeriod(
            snapshot.period7d,
            request.from7d,
            request.throughTs,
            7L * DAY_MS
        )
        val period30d = validatePeriod(
            snapshot.period30d,
            request.from30d,
            request.throughTs,
            30L * DAY_MS
        )
        // Each period is calculated from the same read-only AAPS history, but a
        // missing historical profile can make only an older window unavailable.
        // Preserve every independently validated period instead of discarding a
        // complete recent window and falling back to incomplete Copilot events.
        if (detail == null && period7d == null && period30d == null) return null
        return snapshot.copy(
            detail24h = detail,
            period7d = period7d,
            period30d = period30d
        )
    }

    fun periodFromIntent(intent: Intent, prefix: String): ClinicalAapsTddPeriod? {
        if (!intent.getBooleanExtra("${prefix}Available", false)) return null
        return ClinicalAapsTddPeriod(
            fromTs = intent.getLongExtra("${prefix}FromTs", Long.MIN_VALUE),
            throughTs = intent.getLongExtra("${prefix}ThroughTs", Long.MIN_VALUE),
            basalInsulinU = intent.getDoubleExtra("${prefix}BasalU", Double.NaN),
            bolusInsulinU = intent.getDoubleExtra("${prefix}BolusU", Double.NaN),
            totalInsulinU = intent.getDoubleExtra("${prefix}TotalU", Double.NaN),
            carbsG = intent.getDoubleExtra("${prefix}CarbsG", Double.NaN)
        )
    }

    private fun validatePeriod(
        period: ClinicalAapsTddPeriod?,
        expectedFromTs: Long,
        expectedThroughTs: Long,
        expectedDurationMs: Long
    ): ClinicalAapsTddPeriod? {
        period ?: return null
        if (period.fromTs != expectedFromTs || period.throughTs != expectedThroughTs) return null
        val duration = runCatching {
            Math.subtractExact(period.throughTs, period.fromTs)
        }.getOrNull() ?: return null
        if (kotlin.math.abs(duration - expectedDurationMs) > WINDOW_TOLERANCE_MS) return null
        val values = listOf(
            period.basalInsulinU,
            period.bolusInsulinU,
            period.totalInsulinU,
            period.carbsG
        )
        if (values.any { !it.isFinite() || it < 0.0 }) return null
        if (kotlin.math.abs(
                period.totalInsulinU - (period.basalInsulinU + period.bolusInsulinU)
            ) > 0.05
        ) {
            return null
        }
        return period
    }

    private const val MAX_RESPONSE_SKEW_MS = 10L * 60L * 1_000L
}

internal class AapsClinicalSummarySource(
    context: Context
) : ClinicalAapsTddSource {
    private val appContext = context.applicationContext

    override suspend fun load(
        detailFromTs: Long,
        from7d: Long,
        from30d: Long,
        throughTs: Long
    ): ClinicalAapsTddSnapshot? {
        val request = AapsClinicalSummaryRequest(
            nonce = UUID.randomUUID().toString(),
            detailFromTs = detailFromTs,
            from7d = from7d,
            from30d = from30d,
            throughTs = throughTs
        )
        return withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            awaitResponse(request)
        }
    }

    private suspend fun awaitResponse(
        request: AapsClinicalSummaryRequest
    ): ClinicalAapsTddSnapshot? = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        lateinit var receiver: BroadcastReceiver

        fun finish(value: ClinicalAapsTddSnapshot?) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { appContext.unregisterReceiver(receiver) }
            if (continuation.isActive) continuation.resume(value)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AapsClinicalSummaryContract.RESPONSE_ACTION) return
                if (intent.getStringExtra(AapsClinicalSummaryContract.EXTRA_NONCE) != request.nonce) {
                    return
                }
                val snapshot = ClinicalAapsTddSnapshot(
                    generatedAt = intent.getLongExtra(
                        AapsClinicalSummaryContract.EXTRA_GENERATED_AT,
                        Long.MIN_VALUE
                    ),
                    detail24h = AapsClinicalSummaryContract.periodFromIntent(intent, "p24"),
                    period7d = AapsClinicalSummaryContract.periodFromIntent(intent, "p7"),
                    period30d = AapsClinicalSummaryContract.periodFromIntent(intent, "p30")
                )
                finish(AapsClinicalSummaryContract.validateSnapshot(request, snapshot))
            }
        }

        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(AapsClinicalSummaryContract.RESPONSE_ACTION),
                AapsClinicalSummaryContract.RESPONSE_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            appContext.sendBroadcast(AapsClinicalSummaryContract.requestIntent(request))
        }.onFailure {
            finish(null)
        }
        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
        }
    }

    private companion object {
        const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
