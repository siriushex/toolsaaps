package io.aaps.copilot.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, VERIFYING, ERROR }
data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: AppUpdateManifest? = null,
    val percent: Int = 0,
    val file: File? = null,
    val failure: UpdateFailure? = null,
    internal val operation: Long = 0
) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

class AppUpdateController(
    private val source: AppUpdateSource,
    private val installedVersion: Long,
    private val sdk: Int,
    private val scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null

    fun check() {
        if (state.value.busy) return
        val operation = state.value.operation + 1
        mutableState.value = AppUpdateState(UpdatePhase.CHECKING, operation = operation)
        task = scope.launch {
            try {
                val release = source.check()
                if (release.versionCode > installedVersion && release.minSdk > sdk) {
                    throw AppUpdateException(UpdateFailure.INCOMPATIBLE)
                }
                mutableState.value = AppUpdateState(
                    if (release.versionCode > installedVersion) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT,
                    release, operation = operation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { fail(e, operation) }
        }
    }

    fun download() {
        val current = state.value
        if (current.phase != UpdatePhase.AVAILABLE) return
        val release = current.release ?: return
        mutableState.value = current.copy(phase = UpdatePhase.DOWNLOADING)
        task = scope.launch {
            try {
                val file = source.download(release) { percent ->
                    mutableState.update { value ->
                        if (value.operation == current.operation && value.phase == UpdatePhase.DOWNLOADING)
                            value.copy(percent = percent.coerceIn(0, 100)) else value
                    }
                }
                mutableState.value = current.copy(phase = UpdatePhase.READY, file = file, percent = 100)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { fail(e, current.operation) }
        }
    }

    fun cancel() {
        if (state.value.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) return
        task?.cancel()
        mutableState.value = AppUpdateState(operation = state.value.operation + 1)
    }

    suspend fun verifiedFile(): File? {
        val current = state.value
        if (current.phase != UpdatePhase.READY) return null
        val release = current.release ?: return null
        val file = current.file ?: return null
        mutableState.value = current.copy(phase = UpdatePhase.VERIFYING, failure = null)
        try {
            source.verify(file, release)
            mutableState.value = current.copy(failure = null)
            return file
        } catch (cancelled: CancellationException) {
            mutableState.value = current
            throw cancelled
        } catch (e: Exception) { fail(e, current.operation); return null }
    }

    fun installerFailed() { mutableState.update { it.copy(failure = UpdateFailure.INSTALLER) } }

    private fun fail(e: Exception, operation: Long) {
        mutableState.value = AppUpdateState(UpdatePhase.ERROR,
            failure = (e as? AppUpdateException)?.reason ?: UpdateFailure.NETWORK, operation = operation)
    }
}
