package io.aaps.copilot.ui.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import io.aaps.copilot.R
import java.util.concurrent.ConcurrentHashMap

/** Immutable bounded artwork cache, never an alert scheduler or state owner. */
object OwlNotificationArtwork {
    private val images = ConcurrentHashMap<OwlColorBand, Bitmap>()

    fun resource(band: OwlColorBand): Int = when (band) {
        OwlColorBand.BRAND -> R.drawable.owl_status_brand
        OwlColorBand.WHITE -> R.drawable.owl_status_white
        OwlColorBand.YELLOW -> R.drawable.owl_status_yellow
        OwlColorBand.GREEN -> R.drawable.owl_status_green
        OwlColorBand.RED -> R.drawable.owl_status_red
        OwlColorBand.PURPLE -> R.drawable.owl_status_purple
        OwlColorBand.UNKNOWN -> R.drawable.owl_status_unknown
    }

    fun bitmap(context: Context, band: OwlColorBand): Bitmap = images.computeIfAbsent(band) {
        checkNotNull(BitmapFactory.decodeResource(context.resources,resource(it),
            BitmapFactory.Options().apply { inScaled = false }))
    }

    fun decorate(builder: NotificationCompat.Builder, context: Context, channel: String,
        glucose: Double?, fresh: Boolean) {
        fun optionalImage(band: OwlColorBand): Bitmap? = try { bitmap(context,band) }
            catch (_: RuntimeException) { null }
        builder.setLargeIcon(optionalImage(owlColorBand(glucose,fresh)))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context,channel)
                .setSmallIcon(R.drawable.ic_copilot_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setLargeIcon(optionalImage(OwlColorBand.UNKNOWN))
                .setSilent(true).build())
    }
}
