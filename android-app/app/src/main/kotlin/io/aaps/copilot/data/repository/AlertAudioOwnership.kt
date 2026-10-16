package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.LocalAlarmCycle

internal enum class AlertAudioOwnerKind { LEGACY, PREVIEW, LOCAL }
internal class AlertAudioRequest(val kind: AlertAudioOwnerKind, val cycle: LocalAlarmCycle? = null)
internal class AlertAudioStamp(val revision: Any)

/** Request fencing only, never clinical admission. Physical ownership lasts until exact release. */
internal class AlertAudioOwnership {
    private var revision = Any()
    private var requested: AlertAudioRequest? = null
    private var active: AlertAudioRequest? = null

    @Synchronized fun stamp() = AlertAudioStamp(revision)

    @Synchronized fun begin(kind: AlertAudioOwnerKind): AlertAudioRequest {
        require(kind != AlertAudioOwnerKind.LOCAL)
        return reserve(AlertAudioRequest(kind))
    }

    @Synchronized fun beginLocalIfCurrent(stamp: AlertAudioStamp, cycle: LocalAlarmCycle): AlertAudioRequest? =
        if (stamp.revision !== revision) null else reserve(AlertAudioRequest(AlertAudioOwnerKind.LOCAL, cycle))

    @Synchronized fun isCurrent(request: AlertAudioRequest) = requested === request

    @Synchronized fun activate(request: AlertAudioRequest): Boolean {
        if (requested !== request || active != null) return false
        active = request
        return true
    }

    @Synchronized fun release(request: AlertAudioRequest) {
        if (active === request) active = null
        revoke(request)
    }

    @Synchronized fun revoke(request: AlertAudioRequest) {
        if (requested === request) {
            requested = null
            revision = Any()
        }
    }

    @Synchronized fun revokeAll(): Set<AlertAudioRequest> {
        val revoked = setOfNotNull(requested, active)
        requested = null
        revision = Any()
        return revoked
    }

    @Synchronized fun revokePreview(): Set<AlertAudioRequest> = revokeMatching { it.kind == AlertAudioOwnerKind.PREVIEW }

    @Synchronized fun revokeCycle(cycle: LocalAlarmCycle): Set<AlertAudioRequest> = revokeMatching { it.cycle == cycle }

    private fun reserve(request: AlertAudioRequest): AlertAudioRequest {
        requested = request
        revision = Any()
        return request
    }

    private inline fun revokeMatching(matches: (AlertAudioRequest) -> Boolean): Set<AlertAudioRequest> {
        val revoked = setOfNotNull(requested?.takeIf(matches), active?.takeIf(matches))
        requested?.takeIf(matches)?.let(::revoke)
        return revoked
    }
}
