package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.SensitivityMetricKind
import io.aaps.copilot.service.aapsLoopFactorCandidate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalAapsLoopInputSyncTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
        .allowMainThreadQueries().build()
    private val now = 1_800_000_000_000L
    private val nonce = "00112233-4455-4677-8899-aabbccddeeff"
    private val fixture = javaClass.getResource("/fixtures/local-loop-inputs.json")!!.readText().trim()
    private var body: String? = fixture
    private var enabled = true
    private var accepted = true
    private var signals = 0
    private fun reader() = LocalAapsLoopInputSync(db, { body?.toByteArray() }, { enabled }, { signals++; accepted }, { now }, { nonce })
    @After fun close() = db.close()
    @Test fun forecastSelectionSeparatesMissingInputsFromZeroAndDisabledScope() = runBlocking {
        val empty = LocalLoopForecastSelection.capture(db, true, now)
        empty.requireCurrent(db, true, now)
        assertTrue(runCatching { empty.requireCurrent(db, false, now) }.exceptionOrNull() is IllegalArgumentException)
        val off = LocalLoopForecastSelection.capture(db, false, now)
        body = fixture.replace("12.5", "0.0")
        reader().sync()
        off.requireCurrent(db, false, now)
        assertTrue(runCatching { empty.requireCurrent(db, true, now) }.exceptionOrNull() is IllegalArgumentException)

        val current = LocalLoopForecastSelection.capture(db, true, now)
        reader().sync()
        current.requireCurrent(db, true, now + 250_000)
        assertTrue(runCatching { current.requireCurrent(db, true, now + 250_001) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun exactLoopFactorInputsReachTheExistingAapsSelectorOnlyOnceConverted() = runBlocking {
        body = javaClass.getResource("/fixtures/local-loop-factors.json")!!.readText().trim()
        reader().sync()
        val rows = LocalAapsLoopInputSync.activeSamples(db, now)
        val isf = requireNotNull(AutomationRepository.selectStrictAapsIsfStatic(rows))
        val cr = requireNotNull(AutomationRepository.selectStrictAapsCrStatic(rows))
        assertEquals(2.5, isf.valueDouble!!, 0.0); assertEquals(11.5, cr.valueDouble!!, 0.0)
        assertEquals(now - 40_000, isf.timestamp); assertEquals(isf.timestamp, cr.timestamp)
        assertEquals(0, rows.count { it.key in setOf("isf_value", "cr_value") })
        val legacy = listOf(isf.copy(source = "aaps_broadcast", key = "isf_value", valueDouble = 1.8, timestamp = isf.timestamp - 1))
        assertEquals(2.5, aapsLoopFactorCandidate(legacy, rows, now, SensitivityMetricKind.ISF).value!!, 0.0)
        assertEquals(11.5, aapsLoopFactorCandidate(legacy, rows, now, SensitivityMetricKind.CR).value!!, 0.0)
        assertEquals(1.8, aapsLoopFactorCandidate(legacy, rows, now + 300_001, SensitivityMetricKind.ISF).value!!, 0.0)
        body = fixture
        reader().sync(); assertNull(AutomationRepository.selectStrictAapsIsfStatic(LocalAapsLoopInputSync.activeSamples(db, now)))
        assertNull(aapsLoopFactorCandidate(db.telemetryDao().since(1), LocalAapsLoopInputSync.activeSamples(db, now),
            now, SensitivityMetricKind.ISF).value)
        assertEquals(4, db.telemetryDao().since(1).size)
    }
    @Test fun retainedValuesReachRuntimeWithoutFabricatingBolusOrBasalComponents() = runBlocking {
        reader().sync()
        val rows = LocalAapsLoopInputSync.activeSamples(db, now)
        val cob = requireNotNull(AutomationRepository.selectStrictAapsCobStatic(rows))
        val net = requireNotNull(AutomationRepository.selectStrictAapsIobStatic(rows))
        assertEquals(now - 100_000, cob.timestamp)
        assertEquals(12.5, cob.valueDouble!!, 0.0)
        fun resolve(samples: List<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>, at: Long = now) =
            AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(samples, at, 600_000).snapshot
        val native = requireNotNull(resolve(rows))
        assertEquals(resolve(rows.map { it.copy(source = "aaps_broadcast", key = if (it == net) "iob_units" else it.key) }), native)
        assertEquals(InsulinRuntimeSource.LEGACY_AAPS, native.source)
        assertEquals(-0.75, native.netIobUnits, 0.0); assertNull(native.bolusIobUnits); assertNull(native.basalIobUnits)
        assertEquals(now - 50_000, native.timestamp)
        assertNull(resolve(rows, native.timestamp + 300_001))
        assertFalse(LocalAapsLoopInputSync.current(cob, cob.timestamp + 600_001))
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertTrue(db.actionCommandDao().latest(1).isEmpty())
    }
    @Test fun withdrawnCobDoesNotReturnThroughRuntimeDiagnostics() = runBlocking {
        reader().sync()
        val observed = LocalAapsLoopInputSync.activeSamples(db, now).single { it.key == "cob_grams" }
        val echo = listOf("cob_effective_grams", "cob_external_raw_grams", "cob_grams").map {
            observed.copy(source = "copilot_runtime_cob_iob", key = it, timestamp = now)
        }
        fun select(rows: List<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>) =
            AutomationRepository.selectExternalCobInput(rows, now)
        assertEquals(observed, select(echo + observed))
        body = fixture.replace(",\"cobG\":12.5,\"cobAt\":1799999900000", "")
        reader().sync()
        assertNull(select(echo + LocalAapsLoopInputSync.activeSamples(db, now)))
        val zero = observed.copy(key = "raw_cob", source = "aaps_broadcast", valueDouble = 0.0)
        assertEquals(zero, select(echo + zero))
        assertNull(select(echo + zero.copy(timestamp = now + 1)))
    }
    @Test fun correctionsReplayPendingInvalidationAndRejectWrongReplyAtomically() = runBlocking {
        accepted = false; reader().sync()
        accepted = true; body = null; reader().sync()
        assertEquals(2, signals)
        body = fixture; reader().sync()
        assertEquals(2, signals)
        body = fixture.replace("12.5", "0.0"); reader().sync()
        assertEquals(3, signals)
        val before = db.telemetryDao().since(1)
        assertEquals(2, before.size); assertEquals(0.0, before.single { it.key == "cob_grams" }.valueDouble!!, 0.0)
        for (bad in listOf(fixture.replace(nonce, "mismatch"), fixture.replace("1799999900000", "1800000000001"),
                fixture.replace("\"cobG\":12.5,", ""))) {
            body = bad
            assertTrue(runCatching { reader().sync() }.isFailure)
        }
        enabled = false; body = fixture; reader().sync()
        assertEquals(before, db.telemetryDao().since(1))
        assertEquals(3, signals)
    }

    @Test fun successfulPartialResponseWithdrawsOnlyAbsentMetricWithoutErasingHistory() = runBlocking {
        reader().sync()
        body = null; reader().sync()
        assertEquals(2, LocalAapsLoopInputSync.activeSamples(db, now).size)
        body = fixture.replace("\"netIobU\":-0.75,\"iobAt\":1799999950000,", "")
        reader().sync()
        assertEquals("cob_grams", LocalAapsLoopInputSync.activeSamples(db, now).single().key)
        assertEquals(2, db.telemetryDao().since(1).size)
        assertEquals(2, signals)
        reader().sync(); assertEquals(2, signals)
        body = fixture; reader().sync()
        assertEquals(2, LocalAapsLoopInputSync.activeSamples(db, now).size)
        assertEquals(3, signals)
    }
}
