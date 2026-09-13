package io.aaps.copilot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.config.sensitivityRuntimeIdentity
import io.aaps.copilot.MainActivity
import io.aaps.copilot.R
import io.aaps.copilot.util.SanitizedOperationalFailure
import io.aaps.copilot.util.boundedOperationalErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object CopilotGlucoseWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val publicationCoordinator = CopilotGlucoseWidgetPublicationCoordinator(
        scope = scope,
        onFailure = ::logPublicationFailure
    )

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        enqueueAll(
            context = appContext,
            failureReporting = CopilotGlucoseWidgetFailureReporting.FIRE_AND_FORGET
        )
    }

    suspend fun updateAll(context: Context) {
        val appContext = context.applicationContext
        awaitPublication(
            enqueueAll(
                context = appContext,
                failureReporting = CopilotGlucoseWidgetFailureReporting.AWAITED
            )
        )
    }

    private fun enqueueAll(
        context: Context,
        failureReporting: CopilotGlucoseWidgetFailureReporting
    ): Deferred<CopilotGlucoseWidgetPublicationResult> =
        publicationCoordinator.enqueue(
            failureReporting = failureReporting,
            load = {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, CopilotGlucoseWidgetProvider::class.java)
                )
                loadPublication(context, manager, ids)
            },
            publish = ::publish
        )

    suspend fun updateWidgets(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        val appContext = context.applicationContext
        val stableIds = ids.copyOf()
        awaitPublication(
            publicationCoordinator.enqueue(
                failureReporting = CopilotGlucoseWidgetFailureReporting.AWAITED,
                load = { loadPublication(appContext, manager, stableIds) },
                publish = ::publish
            )
        )
    }

    private suspend fun awaitPublication(
        receipt: Deferred<CopilotGlucoseWidgetPublicationResult>
    ) {
        when (val result = receipt.await()) {
            CopilotGlucoseWidgetPublicationResult.Published,
            CopilotGlucoseWidgetPublicationResult.Superseded -> Unit
            is CopilotGlucoseWidgetPublicationResult.Failed -> {
                throw result.failure.toAwaitedException()
            }
        }
    }

    private suspend fun loadPublication(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ): WidgetPublication? {
        if (ids.isEmpty()) return null
        val app = context as? CopilotApp ?: return null
        val snapshot = CopilotGlucoseWidgetRepository(
            db = app.container.db,
            currentSettingsIdentity = {
                app.container.settingsStore.settings.first().sensitivityRuntimeIdentity()
            }
        ).loadSnapshot()
        val text = CopilotGlucoseWidgetFormatter.format(snapshot)
        return WidgetPublication(context, manager, ids, text)
    }

    private fun publish(publication: WidgetPublication?) {
        publication ?: return
        publication.ids.forEach { appWidgetId ->
            publication.manager.updateAppWidget(
                appWidgetId,
                buildRemoteViews(publication.context, publication.text)
            )
        }
    }

    private fun logPublicationFailure(failure: CopilotGlucoseWidgetPublicationFailure) {
        Log.w(LOG_TAG, copilotGlucoseWidgetPublicationFailureDiagnostic(failure))
    }

    private fun buildRemoteViews(
        context: Context,
        text: CopilotGlucoseWidgetText
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.widget_glucose_cockpit).apply {
            setTextViewText(R.id.widget_glucose_value, text.current)
            setTextViewText(R.id.widget_glucose_pred30_value, text.predicted30)
            setTextViewText(R.id.widget_glucose_iob_value, text.iob)
            setTextViewText(R.id.widget_glucose_cob_value, text.cob)
            setTextViewText(R.id.widget_glucose_trend_value, text.trend)
            setTextViewText(R.id.widget_glucose_age, text.age)
            setTextViewText(R.id.widget_glucose_status, text.status)
            setOnClickPendingIntent(R.id.widget_glucose_root, launchPendingIntent(context))
        }
    }

    private fun launchPendingIntent(context: Context): PendingIntent {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private data class WidgetPublication(
        val context: Context,
        val manager: AppWidgetManager,
        val ids: IntArray,
        val text: CopilotGlucoseWidgetText
    )

    private const val LOG_TAG = "CopilotGlucoseWidget"
}

internal fun copilotGlucoseWidgetPublicationFailureDiagnostic(error: Exception): String =
    copilotGlucoseWidgetPublicationFailureDiagnostic(
        CopilotGlucoseWidgetPublicationFailure.from(error)
    )

internal fun copilotGlucoseWidgetPublicationFailureDiagnostic(
    failure: CopilotGlucoseWidgetPublicationFailure
): String = "${failure.errorCode}:${failure.errorType}"

internal enum class CopilotGlucoseWidgetFailureReporting {
    FIRE_AND_FORGET,
    AWAITED
}

internal data class CopilotGlucoseWidgetPublicationFailure(
    val errorCode: String,
    val errorType: String
) {
    fun toAwaitedException(): CopilotGlucoseWidgetUpdateException =
        CopilotGlucoseWidgetUpdateException(
            errorCode = errorCode,
            errorType = errorType
        )

    companion object {
        fun from(error: Exception): CopilotGlucoseWidgetPublicationFailure =
            CopilotGlucoseWidgetPublicationFailure(
                errorCode = WIDGET_PUBLICATION_FAILURE_CODE,
                errorType = boundedOperationalErrorType(error)
            )
    }
}

internal class CopilotGlucoseWidgetUpdateException(
    override val errorCode: String,
    override val errorType: String
) : Exception("$errorCode:$errorType"), SanitizedOperationalFailure

private const val WIDGET_PUBLICATION_FAILURE_CODE = "WIDGET_PUBLICATION_FAILED"

internal sealed interface CopilotGlucoseWidgetPublicationResult {
    data object Published : CopilotGlucoseWidgetPublicationResult
    data object Superseded : CopilotGlucoseWidgetPublicationResult
    data class Failed(
        val failure: CopilotGlucoseWidgetPublicationFailure
    ) : CopilotGlucoseWidgetPublicationResult
}

internal class CopilotGlucoseWidgetPublicationCoordinator(
    private val scope: CoroutineScope,
    private val onFailure: (CopilotGlucoseWidgetPublicationFailure) -> Unit = {}
) {
    private val stateLock = Any()
    private var latestGeneration = 0L
    private var active: PublicationRequest? = null
    private var pending: PublicationRequest? = null
    private var drainRunning = false

    fun <T> enqueue(
        failureReporting: CopilotGlucoseWidgetFailureReporting =
            CopilotGlucoseWidgetFailureReporting.AWAITED,
        load: suspend () -> T,
        publish: (T) -> Unit
    ): Deferred<CopilotGlucoseWidgetPublicationResult> {
        val receipt = CompletableDeferred<CopilotGlucoseWidgetPublicationResult>()
        val shouldLaunchDrain: Boolean
        synchronized(stateLock) {
            latestGeneration += 1L
            val generation = latestGeneration
            active?.receipt?.complete(CopilotGlucoseWidgetPublicationResult.Superseded)
            pending?.receipt?.complete(CopilotGlucoseWidgetPublicationResult.Superseded)
            pending = PublicationRequest(
                generation = generation,
                receipt = receipt,
                failureReporting = failureReporting,
                load = {
                    val publication = load()
                    val publishLoaded: () -> Unit = { publish(publication) }
                    publishLoaded
                }
            )
            shouldLaunchDrain = !drainRunning
            if (shouldLaunchDrain) drainRunning = true
        }
        if (shouldLaunchDrain) launchDrain()
        return receipt
    }

    private fun launchDrain() {
        scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val request = synchronized(stateLock) {
                pending?.also {
                    pending = null
                    active = it
                }
                    ?: run {
                        active = null
                        drainRunning = false
                        null
                    }
            } ?: return
            try {
                val publish = request.load()
                synchronized(stateLock) {
                    val result = if (request.generation == latestGeneration) {
                        publish()
                        CopilotGlucoseWidgetPublicationResult.Published
                    } else {
                        CopilotGlucoseWidgetPublicationResult.Superseded
                    }
                    request.receipt.complete(result)
                }
            } catch (cancellation: CancellationException) {
                request.receipt.completeExceptionally(cancellation)
                if (!currentCoroutineContext().isActive) {
                    abortDrainAndCompletePending(cancellation)
                    throw cancellation
                }
            } catch (operational: Exception) {
                val failure = CopilotGlucoseWidgetPublicationFailure.from(operational)
                val shouldReport = synchronized(stateLock) {
                    if (request.generation == latestGeneration) {
                        request.receipt.complete(
                            CopilotGlucoseWidgetPublicationResult.Failed(failure)
                        )
                        request.failureReporting ==
                            CopilotGlucoseWidgetFailureReporting.FIRE_AND_FORGET
                    } else {
                        request.receipt.complete(CopilotGlucoseWidgetPublicationResult.Superseded)
                        false
                    }
                }
                if (shouldReport) reportFailureBestEffort(failure)
            } catch (fatal: Throwable) {
                request.receipt.completeExceptionally(fatal)
                restartDrainAfterFatal()
                throw fatal
            } finally {
                synchronized(stateLock) {
                    if (active === request) active = null
                }
            }
        }
    }

    private fun reportFailureBestEffort(failure: CopilotGlucoseWidgetPublicationFailure) {
        try {
            onFailure(failure)
        } catch (_: Throwable) {
            // This catch is intentionally limited to the non-clinical diagnostic sink.
        }
    }

    private fun abortDrainAndCompletePending(cancellation: CancellationException) {
        val abandoned = synchronized(stateLock) {
            drainRunning = false
            pending.also { pending = null }
        }
        abandoned?.receipt?.completeExceptionally(cancellation)
    }

    private fun restartDrainAfterFatal() {
        val restart = synchronized(stateLock) {
            drainRunning = false
            if (pending != null && scope.isActive) {
                drainRunning = true
                true
            } else {
                false
            }
        }
        if (restart) launchDrain()
    }

    private data class PublicationRequest(
        val generation: Long,
        val receipt: CompletableDeferred<CopilotGlucoseWidgetPublicationResult>,
        val failureReporting: CopilotGlucoseWidgetFailureReporting,
        val load: suspend () -> () -> Unit
    )
}
