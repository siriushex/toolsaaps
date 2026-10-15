package io.aaps.copilot.domain.alerts

import org.junit.Assert.*
import org.junit.Test

class LocalAlarmPersistenceCodecTest {
    private val key = LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "episode-a")
    private val env = LocalAlarmEnvironment(true, true, 1_000, 10_000, 2)
    private val evidence = LocalAlarmEvidence(key, 1, LocalAlarmLevel.WARNING_30, 2, 1_000, 900_000, true)
    private fun active() = LocalAlarmPolicy.evaluate(evidence, null, env, LocalAlarmTiming()).state!!

    @Test fun activeAndPausedStatesRoundTripWithoutAuthority() {
        val active = active()
        val paused = LocalAlarmPolicy.acknowledge(evidence, active, env, LocalAlarmTiming(),
            LocalAlarmAcknowledgement(key, 1, 1, evidence.level))!!
        for (state in listOf(active, paused, active.copy(cycleActive = false),
            LocalAlarmState(key, 2, LocalAlarmLevel.NONE, 2, 1_000, 2_000, ordinal = 8))) {
            assertEquals(state, LocalAlarmPersistenceCodec.decodeState(LocalAlarmPersistenceCodec.encodeState(state)!!))
        }
    }

    @Test fun corruptOrUnsupportedStateIsNeverDecoded() {
        val json = LocalAlarmPersistenceCodec.encodeState(active())!!
        val bad = listOf(
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":{}"),
            json.replace("\"ordinal\":1", "\"ordinal\":1.0"),
            json.replace("\"ordinal\":1", "\"ordinal\":1e0"),
            json.replace("\"ordinal\":1", "\"ordinal\":9223372036854775808"),
            json.replace("\"ordinal\":1", "\"ordinal\":\"1\""),
            json.replace("\"cycleActive\":true", "\"cycleActive\":1"),
            json.replace("\"lastCycleLevel\":\"WARNING_30\"", "\"lastCycleLevel\":null"),
            json.replace("episode-a", ""),
            json.replace("WARNING_30", "PUMP_LINK"),
            json.dropLast(1), json + "{}", "{}", "[]", "null", " ".repeat(4097),
            "{\"extra\":" + "[".repeat(5000) + "0" + "]".repeat(5000) + "}"
        )
        for (value in bad) assertNull(value.take(80), LocalAlarmPersistenceCodec.decodeState(value))
        assertNull(LocalAlarmPersistenceCodec.encodeState(active().copy(ordinal = 0)))
    }

    @Test fun independentChannelsAndFourStepsRoundTrip() {
        val result = LocalAlarmCycleResult((0..3).map { index ->
            LocalAlarmStepResult(index, 1_000L + index * 15_000,
                LocalAlarmNotificationResult.BLOCKED, LocalAlarmAudioResult.STARTED,
                LocalAlarmVibrationResult.REQUESTED, (index + 1) * 25)
        })
        assertEquals(result, LocalAlarmPersistenceCodec.decodeResult(LocalAlarmPersistenceCodec.encodeResult(result)!!))
        assertEquals(LocalAlarmCycleResult(), LocalAlarmPersistenceCodec.decodeResult(LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult())!!))
    }

    @Test fun resultBoundsAndTypesAreStrict() {
        val step = LocalAlarmStepResult(0, 1_000, LocalAlarmNotificationResult.POSTED,
            LocalAlarmAudioResult.UNAVAILABLE, LocalAlarmVibrationResult.FAILED, null)
        val json = LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult(listOf(step)))!!
        for (bad in listOf(json.replace("\"schemaVersion\":1", "\"schemaVersion\":9"),
            json.replace("\"index\":0", "\"index\":0,\"index\":0"),
            json.replace("\"index\":0", "\"index\":4"),
            json.replace("\"atElapsedMs\":1000", "\"atElapsedMs\":-1"),
            json.replace("\"reachedPercent\":null", "\"reachedPercent\":101"),
            json.replace("\"audio\":\"UNAVAILABLE\"", "\"audio\":\"HEARD\""),
            json.replace("\"steps\":[", "\"steps\":{\"nested\":["), " ".repeat(2049))) {
            assertNull(bad.take(80), LocalAlarmPersistenceCodec.decodeResult(bad))
        }
        assertNull(LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult(listOf(step, step))))
        assertNull(LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult((0..4).map { step.copy(index = it) })))
        assertNull(LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult(listOf(step.copy(reachedPercent = 0)))))
    }
}
