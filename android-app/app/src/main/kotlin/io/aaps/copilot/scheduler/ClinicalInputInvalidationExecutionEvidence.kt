package io.aaps.copilot.scheduler

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.Data
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

internal enum class ClinicalInvalidationExecutionOutcome {
    STARTED_UNKNOWN,
    ACCEPTED,
    UNACCEPTED,
    FATAL
}

internal data class ClinicalInvalidationExecutionEvidence(
    val generation: Long,
    val dispatchToken: String,
    val outcome: ClinicalInvalidationExecutionOutcome
) {
    fun matches(dispatch: ClinicalInputInvalidationDispatch): Boolean =
        generation == dispatch.generation && dispatchToken == dispatch.dispatchToken

    fun toWorkData(): Data = Data.Builder()
        .putLong(WORK_EVIDENCE_GENERATION, generation)
        .putString(WORK_EVIDENCE_TOKEN, dispatchToken)
        .putString(WORK_EVIDENCE_OUTCOME, outcome.name)
        .build()

    companion object {
        fun fromWorkData(data: Data): ClinicalInvalidationExecutionEvidence? {
            val generation = data.getLong(WORK_EVIDENCE_GENERATION, 0L)
            val token = data.getString(WORK_EVIDENCE_TOKEN)?.takeIf(String::isNotBlank)
                ?: return null
            val outcome = data.getString(WORK_EVIDENCE_OUTCOME)
                ?.let { encoded ->
                    ClinicalInvalidationExecutionOutcome.entries.firstOrNull { it.name == encoded }
                }
                ?: return null
            if (generation <= 0L) return null
            return ClinicalInvalidationExecutionEvidence(generation, token, outcome)
        }

        fun hasEvidenceFields(data: Data): Boolean =
            data.keyValueMap.keys.any { it in WORK_EVIDENCE_KEYS }
    }
}

internal fun ClinicalInputInvalidationDispatch.executionEvidence(
    outcome: ClinicalInvalidationExecutionOutcome
): ClinicalInvalidationExecutionEvidence = ClinicalInvalidationExecutionEvidence(
    generation = generation,
    dispatchToken = dispatchToken,
    outcome = outcome
)

internal fun ClinicalInvalidationWorkData.executionEvidence(
    outcome: ClinicalInvalidationExecutionOutcome
): ClinicalInvalidationExecutionEvidence = ClinicalInvalidationExecutionEvidence(
    generation = generation,
    dispatchToken = dispatchToken,
    outcome = outcome
)

internal interface ClinicalInvalidationExecutionEvidenceStore {
    suspend fun read(): ClinicalInvalidationExecutionEvidence?
    suspend fun write(evidence: ClinicalInvalidationExecutionEvidence)
}

internal class MalformedClinicalInvalidationExecutionEvidenceException :
    IllegalStateException("malformed clinical invalidation execution evidence")

internal class InMemoryClinicalInvalidationExecutionEvidenceStore :
    ClinicalInvalidationExecutionEvidenceStore {
    private var evidence: ClinicalInvalidationExecutionEvidence? = null

    override suspend fun read(): ClinicalInvalidationExecutionEvidence? = evidence

    override suspend fun write(evidence: ClinicalInvalidationExecutionEvidence) {
        this.evidence = evidence
    }
}

internal class ReactiveClinicalCycleState {
    var invocationStarted: Boolean = false
        private set
    var accepted: Boolean? = null
        private set

    fun markInvocationStarted() {
        invocationStarted = true
    }

    fun recordResult(accepted: Boolean) {
        this.accepted = accepted
    }
}

internal suspend fun runClinicalCycleWithExecutionEvidence(
    reactiveWork: ClinicalInvalidationWorkData,
    store: ClinicalInvalidationExecutionEvidenceStore,
    cycleState: ReactiveClinicalCycleState? = null,
    runCycle: suspend () -> Boolean
): Boolean {
    store.write(reactiveWork.executionEvidence(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN))
    cycleState?.markInvocationStarted()
    val accepted = try {
        runCycle()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (fatal: ThreadDeath) {
        throw fatal
    } catch (fatal: LinkageError) {
        throw fatal
    } catch (fatal: Error) {
        writeEvidencePreservingFailure(
            store,
            reactiveWork.executionEvidence(ClinicalInvalidationExecutionOutcome.FATAL),
            fatal
        )
        throw fatal
    } catch (failure: Exception) {
        throw failure
    }
    cycleState?.recordResult(accepted)
    store.write(
        reactiveWork.executionEvidence(
            if (accepted) {
                ClinicalInvalidationExecutionOutcome.ACCEPTED
            } else {
                ClinicalInvalidationExecutionOutcome.UNACCEPTED
            }
        )
    )
    return accepted
}

internal fun Error.isProcessFatalError(): Boolean =
    this is VirtualMachineError || this is ThreadDeath || this is LinkageError

internal enum class ReactiveExecutionEvidenceRecovery {
    NONE,
    REDRIVE_ALLOWED,
    ACKNOWLEDGED,
    AMBIGUOUSLY_TERMINATED,
    FATALLY_TERMINATED,
    FAIL_CLOSED_UNKNOWN,
    FAIL_CLOSED_STALE_OWNER
}

internal suspend fun recoverReactiveWorkFromExecutionEvidence(
    reactiveWork: ClinicalInvalidationWorkData,
    evidence: ClinicalInvalidationExecutionEvidence?,
    acknowledge: suspend (Long, String) -> Boolean,
    terminateAmbiguous: suspend (Long, String) -> Boolean = { _, _ -> false },
    terminateFatal: suspend (Long, String) -> Boolean
): ReactiveExecutionEvidenceRecovery {
    if (evidence == null ||
        evidence.generation != reactiveWork.generation ||
        evidence.dispatchToken != reactiveWork.dispatchToken
    ) {
        return ReactiveExecutionEvidenceRecovery.NONE
    }
    return when (evidence.outcome) {
        ClinicalInvalidationExecutionOutcome.ACCEPTED -> {
            if (acknowledge(reactiveWork.generation, reactiveWork.dispatchToken)) {
                ReactiveExecutionEvidenceRecovery.ACKNOWLEDGED
            } else {
                ReactiveExecutionEvidenceRecovery.FAIL_CLOSED_STALE_OWNER
            }
        }
        ClinicalInvalidationExecutionOutcome.FATAL -> {
            if (terminateFatal(reactiveWork.generation, reactiveWork.dispatchToken)) {
                ReactiveExecutionEvidenceRecovery.FATALLY_TERMINATED
            } else {
                ReactiveExecutionEvidenceRecovery.FAIL_CLOSED_STALE_OWNER
            }
        }
        ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN -> {
            if (terminateAmbiguous(reactiveWork.generation, reactiveWork.dispatchToken)) {
                ReactiveExecutionEvidenceRecovery.AMBIGUOUSLY_TERMINATED
            } else {
                ReactiveExecutionEvidenceRecovery.FAIL_CLOSED_STALE_OWNER
            }
        }
        ClinicalInvalidationExecutionOutcome.UNACCEPTED ->
            ReactiveExecutionEvidenceRecovery.REDRIVE_ALLOWED
    }
}

private suspend fun writeEvidencePreservingFailure(
    store: ClinicalInvalidationExecutionEvidenceStore,
    evidence: ClinicalInvalidationExecutionEvidence,
    original: Throwable
) {
    try {
        store.write(evidence)
    } catch (cancelled: CancellationException) {
        original.addSuppressed(cancelled)
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (fatal: ThreadDeath) {
        throw fatal
    } catch (fatal: LinkageError) {
        throw fatal
    } catch (failure: Throwable) {
        original.addSuppressed(failure)
    }
}

internal class DataStoreClinicalInvalidationExecutionEvidenceStore internal constructor(
    private val dataStore: DataStore<Preferences>
) : ClinicalInvalidationExecutionEvidenceStore {
    constructor(context: Context) : this(
        PreferenceDataStoreFactory.create(
            produceFile = { File(context.noBackupFilesDir, DATASTORE_FILE_NAME) }
        )
    )

    override suspend fun read(): ClinicalInvalidationExecutionEvidence? {
        val preferences = dataStore.data.first()
        val generation = preferences[GENERATION]
        val encodedToken = preferences[TOKEN]
        val encodedOutcome = preferences[OUTCOME]
        if (generation == null && encodedToken == null && encodedOutcome == null) return null
        val token = encodedToken?.takeIf(String::isNotBlank)
            ?: throw MalformedClinicalInvalidationExecutionEvidenceException()
        val outcome = encodedOutcome?.let { encoded ->
            ClinicalInvalidationExecutionOutcome.entries.firstOrNull { it.name == encoded }
        } ?: throw MalformedClinicalInvalidationExecutionEvidenceException()
        if (generation == null || generation <= 0L) {
            throw MalformedClinicalInvalidationExecutionEvidenceException()
        }
        return ClinicalInvalidationExecutionEvidence(generation, token, outcome)
    }

    override suspend fun write(evidence: ClinicalInvalidationExecutionEvidence) {
        require(evidence.generation > 0L)
        require(evidence.dispatchToken.isNotBlank())
        dataStore.edit { preferences ->
            preferences.clear()
            preferences[GENERATION] = evidence.generation
            preferences[TOKEN] = evidence.dispatchToken
            preferences[OUTCOME] = evidence.outcome.name
        }
    }

    private companion object {
        const val DATASTORE_FILE_NAME = "clinical_input_invalidation_execution.preferences_pb"
        val GENERATION = longPreferencesKey("generation")
        val TOKEN = stringPreferencesKey("dispatch_token")
        val OUTCOME = stringPreferencesKey("outcome")
    }
}

private const val WORK_EVIDENCE_GENERATION = "clinical_invalidation_evidence_generation"
private const val WORK_EVIDENCE_TOKEN = "clinical_invalidation_evidence_token"
private const val WORK_EVIDENCE_OUTCOME = "clinical_invalidation_evidence_outcome"
private val WORK_EVIDENCE_KEYS = setOf(
    WORK_EVIDENCE_GENERATION,
    WORK_EVIDENCE_TOKEN,
    WORK_EVIDENCE_OUTCOME
)
