package io.aaps.copilot.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.pump.InfusionSetPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InfusionSetSourceBindingTest {
    private lateinit var db: CopilotDatabase
    private val start = 1_800_000_000_000L
    private val due = start + InfusionSetPolicy.INTERVAL_MS
    private var posts = 0
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }
    private fun row(ts: Long, id: String = "site-$ts", type: String = "infusion_set_change") =
        TherapyEventEntity(id, ts, type, "{\"date\":$ts,\"source\":\"nightscout_treatment\"}")
    private suspend fun post(now: Long) = postCurrentInfusionReminder(db, "infusion-$start", due, { now }) {
        posts++; InfusionPostResult.POSTED
    }
    @Test fun finalSourceCheckWorksWithoutCgmAndRejectsEarlyExecution() = runBlocking {
        db.therapyDao().upsertAll(listOf(row(start), row(due - 1, type = "sensor_change")))
        assertEquals(InfusionPostResult.STALE, post(due - 1))
        assertEquals(InfusionPostResult.POSTED, post(due))
        assertEquals(1, posts)
    }
    @Test fun committedReplacementOrDeletionBlocksOldWorker() = runBlocking {
        db.therapyDao().upsertAll(listOf(row(start), row(due - 1000)))
        assertEquals(InfusionPostResult.STALE, post(due))
        db.therapyDao().deleteByIds(listOf("site-$start", "site-${due - 1000}"))
        assertEquals(InfusionPostResult.STALE, post(due))
        assertEquals(0, posts)
    }
    @Test fun boundedQueryIncludesOverflowSentinelWithoutUnrelatedEvents() = runBlocking {
        db.therapyDao().upsertAll(List(40) { row(start - it * 1000L) } + List(50) { row(start, "sensor-$it", "sensor_change") })
        val rows = db.therapyDao().latestInfusionChangeCandidates(InfusionSetEvidenceReader.TYPES, 33)
        assertEquals(33, rows.size)
        assertEquals(start, rows.first().timestamp)
        assertNull(InfusionSetEvidenceReader.read(rows, due))
        assertEquals(InfusionPostResult.STALE, post(due))
    }
}
