package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ForecastEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ForecastRetentionRoomTest {
    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun retentionBoundsEachTransactionAndPreservesBoundaryAndRecentRows() = runBlocking {
        val dao = db.forecastDao()
        dao.insertAll((1L..600L).map(::forecast) + listOf(forecast(1_000), forecast(1_001)))

        assertThat(dao.deleteOlderThan(1_000)).isEqualTo(256)
        assertThat(dao.since(0).map { it.timestamp }).containsExactlyElementsIn(
            (257L..600L).toList() + listOf(1_000L, 1_001L)
        ).inOrder()
        assertThat(dao.deleteOlderThan(1_000)).isEqualTo(256)
        assertThat(dao.deleteOlderThan(1_000)).isEqualTo(88)
        assertThat(dao.deleteOlderThan(1_000)).isEqualTo(0)
        assertThat(dao.since(0).map { it.timestamp }).containsExactly(1_000L, 1_001L).inOrder()
    }

    private fun forecast(timestamp: Long) = ForecastEntity(
        timestamp = timestamp, horizonMinutes = 30,
        valueMmol = 6.0, ciLow = 5.0, ciHigh = 7.0, modelVersion = "retention-test"
    )
}
