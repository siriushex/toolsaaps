package io.aaps.copilot.scheduler

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.io.File

internal data class ClinicalInputInvalidationPending(
    val generation: Long,
    val sources: Set<ClinicalInputInvalidationSource>,
    val attempt: Int
)

internal data class ClinicalInputInvalidationLedgerState(
    val latestGeneration: Long = 0L,
    val acknowledgedGeneration: Long = 0L,
    val pending: ClinicalInputInvalidationPending? = null,
    val inFlight: ClinicalInputInvalidationDispatch? = null
)

internal interface ClinicalInputInvalidationLedger {
    suspend fun read(): ClinicalInputInvalidationLedgerState
    suspend fun write(state: ClinicalInputInvalidationLedgerState)
}

internal class InMemoryClinicalInputInvalidationLedger : ClinicalInputInvalidationLedger {
    private var state = ClinicalInputInvalidationLedgerState()

    override suspend fun read(): ClinicalInputInvalidationLedgerState = state

    override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
        this.state = state
    }
}

internal class DataStoreClinicalInputInvalidationLedger internal constructor(
    private val dataStore: DataStore<Preferences>
) : ClinicalInputInvalidationLedger {
    constructor(context: Context) : this(
        PreferenceDataStoreFactory.create(
            produceFile = {
                File(context.noBackupFilesDir, DATASTORE_FILE_NAME)
            }
        )
    )

    override suspend fun read(): ClinicalInputInvalidationLedgerState {
        val preferences = dataStore.data.first()
        val pending = decodePending(preferences)
        val inFlight = decodeInFlight(preferences)
        val latest = maxOf(
            (preferences[LATEST_GENERATION] ?: 0L).coerceAtLeast(0L),
            pending?.generation ?: 0L,
            inFlight?.generation ?: 0L
        )
        return normalizeRecoveredState(ClinicalInputInvalidationLedgerState(
            latestGeneration = latest,
            acknowledgedGeneration = (preferences[ACKNOWLEDGED_GENERATION] ?: 0L)
                .coerceAtLeast(0L)
                .coerceAtMost(latest),
            pending = pending,
            inFlight = inFlight
        ))
    }

    override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
        dataStore.edit { preferences ->
            preferences.asMap().keys.forEach { key -> preferences.remove(key) }
            preferences[LATEST_GENERATION] = state.latestGeneration
            preferences[ACKNOWLEDGED_GENERATION] = state.acknowledgedGeneration
            state.pending?.let { pending ->
                preferences[PENDING_GENERATION] = pending.generation
                preferences[PENDING_SOURCES] = pending.sources.encode()
                preferences[PENDING_ATTEMPT] = pending.attempt
            }
            state.inFlight?.let { inFlight ->
                preferences[IN_FLIGHT_GENERATION] = inFlight.generation
                preferences[IN_FLIGHT_TOKEN] = inFlight.dispatchToken
                preferences[IN_FLIGHT_MODE] = inFlight.mode.name
                preferences[IN_FLIGHT_SOURCES] = inFlight.sources.encode()
                preferences[IN_FLIGHT_ATTEMPT] = inFlight.attempt
            }
        }
    }

    private fun decodePending(preferences: Preferences): ClinicalInputInvalidationPending? {
        val generation = preferences[PENDING_GENERATION] ?: return null
        val sources = preferences[PENDING_SOURCES].decodeSources()
        val attempt = preferences[PENDING_ATTEMPT] ?: return null
        if (
            generation <= 0L ||
            sources.isEmpty() ||
            attempt !in 0..MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT
        ) return null
        return ClinicalInputInvalidationPending(generation, sources, attempt)
    }

    private fun decodeInFlight(preferences: Preferences): ClinicalInputInvalidationDispatch? {
        val generation = preferences[IN_FLIGHT_GENERATION] ?: return null
        val token = preferences[IN_FLIGHT_TOKEN]?.takeIf(String::isNotBlank) ?: return null
        val mode = preferences[IN_FLIGHT_MODE]
            ?.let { encoded -> ClinicalInputInvalidationMode.entries.firstOrNull { it.name == encoded } }
            ?: return null
        val sources = preferences[IN_FLIGHT_SOURCES].decodeSources()
        val attempt = preferences[IN_FLIGHT_ATTEMPT] ?: return null
        if (
            generation <= 0L ||
            sources.isEmpty() ||
            attempt !in 0..MAX_CLINICAL_INVALIDATION_REDISPATCH_ATTEMPTS
        ) return null
        return ClinicalInputInvalidationDispatch(generation, token, mode, sources, attempt)
    }

    private fun Set<ClinicalInputInvalidationSource>.encode(): String =
        sortedBy { it.name }.joinToString(separator = SOURCE_SEPARATOR) { it.name }

    private fun String?.decodeSources(): Set<ClinicalInputInvalidationSource> = this
        ?.split(SOURCE_SEPARATOR)
        ?.mapNotNullTo(linkedSetOf()) { encoded ->
            ClinicalInputInvalidationSource.entries.firstOrNull { it.name == encoded }
        }
        .orEmpty()

    private fun normalizeRecoveredState(
        restored: ClinicalInputInvalidationLedgerState
    ): ClinicalInputInvalidationLedgerState {
        if (restored.latestGeneration <= restored.acknowledgedGeneration) return restored
        val latestHasOwner = restored.pending?.generation == restored.latestGeneration ||
            restored.inFlight?.generation == restored.latestGeneration
        if (latestHasOwner) return restored
        val recoverySources = buildSet {
            add(ClinicalInputInvalidationSource.LEDGER_RECOVERY)
            addAll(restored.pending?.sources.orEmpty())
            addAll(restored.inFlight?.sources.orEmpty())
        }
        return restored.copy(
            pending = ClinicalInputInvalidationPending(
                generation = restored.latestGeneration,
                sources = recoverySources,
                attempt = 0
            )
        )
    }

    private companion object {
        const val DATASTORE_FILE_NAME = "clinical_input_invalidation.preferences_pb"
        const val SOURCE_SEPARATOR = ","
        val LATEST_GENERATION = longPreferencesKey("latest_generation")
        val ACKNOWLEDGED_GENERATION = longPreferencesKey("acknowledged_generation")
        val PENDING_GENERATION = longPreferencesKey("pending_generation")
        val PENDING_SOURCES = stringPreferencesKey("pending_sources")
        val PENDING_ATTEMPT = intPreferencesKey("pending_attempt")
        val IN_FLIGHT_GENERATION = longPreferencesKey("in_flight_generation")
        val IN_FLIGHT_TOKEN = stringPreferencesKey("in_flight_token")
        val IN_FLIGHT_MODE = stringPreferencesKey("in_flight_mode")
        val IN_FLIGHT_SOURCES = stringPreferencesKey("in_flight_sources")
        val IN_FLIGHT_ATTEMPT = intPreferencesKey("in_flight_attempt")
    }
}
