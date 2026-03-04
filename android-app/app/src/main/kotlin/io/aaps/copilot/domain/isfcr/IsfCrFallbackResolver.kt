package io.aaps.copilot.domain.isfcr

class IsfCrFallbackResolver {

    fun resolve(
        snapshot: IsfCrRealtimeSnapshot,
        settings: IsfCrSettings,
        fallbackIsf: Double?,
        fallbackCr: Double?
    ): IsfCrRealtimeSnapshot {
        if (snapshot.confidence >= settings.confidenceThreshold) {
            return if (settings.shadowMode) {
                snapshot.copy(mode = IsfCrRuntimeMode.SHADOW)
            } else {
                snapshot.copy(mode = IsfCrRuntimeMode.ACTIVE)
            }
        }

        val nextIsf = fallbackIsf ?: snapshot.isfBase
        val nextCr = fallbackCr ?: snapshot.crBase
        return snapshot.copy(
            isfEff = nextIsf.coerceIn(0.8, 18.0),
            crEff = nextCr.coerceIn(2.0, 60.0),
            mode = IsfCrRuntimeMode.FALLBACK,
            reasons = snapshot.reasons + "low_confidence_fallback"
        )
    }
}

