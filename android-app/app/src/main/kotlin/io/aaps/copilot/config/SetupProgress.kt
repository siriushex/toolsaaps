package io.aaps.copilot.config

enum class SetupStatus { LEGACY, NOT_STARTED, IN_PROGRESS, DEFERRED, COMPLETE }
enum class SetupStep { EQUIPMENT, DATA, PROFILE, ALERTS, REVIEW }
enum class SetupGlucoseSource { MANUAL, SENSOR }
enum class SetupInsulinSource { NONE, INJECTIONS, PUMP, AAPS }

/** Equipment describes the setup checklist, never permission to issue therapy commands. */
data class SetupProgress(
    val status: SetupStatus = SetupStatus.LEGACY,
    val step: SetupStep = SetupStep.EQUIPMENT,
    val glucoseSource: SetupGlucoseSource = SetupGlucoseSource.MANUAL,
    val insulinSource: SetupInsulinSource = SetupInsulinSource.NONE
) {
    val shouldOffer: Boolean get() = status == SetupStatus.NOT_STARTED || status == SetupStatus.IN_PROGRESS
    fun moveTo(step: SetupStep) = copy(status = SetupStatus.IN_PROGRESS, step = step)
}

internal fun offerSetupForInstall(firstInstallTime: Long, lastUpdateTime: Long): Boolean =
    firstInstallTime > 0 && firstInstallTime == lastUpdateTime

internal fun shouldShowLegacyTherapyBootstrap(setup: SetupProgress, armed: Boolean, evaluated: Boolean): Boolean =
    setup.status == SetupStatus.LEGACY && !armed && !evaluated

internal fun shouldAutoOpenSetup(setup: SetupProgress, atOverview: Boolean, pendingAlert: Boolean, criticalAlert: Boolean): Boolean =
    setup.shouldOffer && atOverview && !pendingAlert && !criticalAlert
