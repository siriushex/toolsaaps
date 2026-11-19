package io.aaps.copilot.service

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalTargetReceiptQueryTest {
    private val query = LocalTargetReceiptQuery("copilot:manual:meal:sample:eating-soon", 100_000_000L, 100_008_000L, 90.0, 30, "EATING_SOON")
    private fun answer(id: Long = 1, at: Long = query.issued) = Bundle().apply {
        putLong("rowId", id); putLong("startedAt", at)
    }
    @Test fun positiveRowOnlyWithinCommitWindow() {
        val now = query.issued + 9_000
        assertEquals(query.issued, AapsLocalObservation.decodeRecordedTarget(answer(), query, now))
        assertEquals(query.issued + 7_999, AapsLocalObservation.decodeRecordedTarget(answer(at = query.issued + 7_999), query, now))
        for (bad in listOf(Bundle(), answer(0), answer(at = query.issued - 1), answer(at = query.issued + 8_000),
            answer().apply { putBoolean("active", true) })) assertNull(AapsLocalObservation.decodeRecordedTarget(bad, query, now))
        assertNull(AapsLocalObservation.decodeRecordedTarget(answer(at = query.issued + 1), query, query.issued))
        assertNull(AapsLocalObservation.decodeRecordedTarget(answer(at = query.issued + 1), query.copy(ackAt = query.issued), now))
        assertNull(AapsLocalObservation.decodeRecordedTarget(answer(), query, query.issued + 86_400_001))
    }
}
