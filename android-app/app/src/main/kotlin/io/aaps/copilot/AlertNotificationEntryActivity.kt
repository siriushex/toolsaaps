package io.aaps.copilot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.aaps.copilot.data.repository.GlucoseAlertNotifier

internal interface AlertNavigationHost {
    fun enqueueAlertNavigation(candidate: String?): Boolean
}

class AlertNotificationEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val candidate = intent
            ?.takeIf { it.action == GlucoseAlertNotifier.ACTION_OPEN_ALERTS }
            ?.getStringExtra(GlucoseAlertNotifier.EXTRA_OPEN_ALERT_EPISODE_ID)
        (application as? AlertNavigationHost)?.enqueueAlertNavigation(candidate)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }
}
