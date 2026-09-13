package io.aaps.copilot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationPermissionPolicyTest {

    @Test
    fun requestsRuntimePermissionOnlyOnAndroidThirteenAndNewerWhenMissing() {
        assertThat(shouldRequestPostNotifications(sdkInt = 32, granted = false)).isFalse()
        assertThat(shouldRequestPostNotifications(sdkInt = 33, granted = false)).isTrue()
        assertThat(shouldRequestPostNotifications(sdkInt = 36, granted = true)).isFalse()
    }

    @Test
    fun defersActivityPermissionWhileNotificationPermissionRequestIsStarting() {
        assertThat(
            shouldRequestActivityRecognitionNow(
                notificationPermissionRequestStarted = true,
                activityRecognitionGranted = false
            )
        ).isFalse()
        assertThat(
            shouldRequestActivityRecognitionNow(
                notificationPermissionRequestStarted = false,
                activityRecognitionGranted = false
            )
        ).isTrue()
    }
}
