package io.aaps.copilot.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStorageInstrumentedTest {

    @Test
    fun cleanupAttemptsEveryArtifactAfterAnEarlierStepFails() {
        val attempts = mutableListOf<String>()

        val failure = assertThrows(IllegalStateException::class.java) {
            runIndependentCleanup(
                "preferences clear" to {
                    attempts += "preferences clear"
                    error("synthetic cleanup failure")
                },
                "preferences file delete" to {
                    attempts += "preferences file delete"
                },
                "keystore alias delete" to {
                    attempts += "keystore alias delete"
                }
            )
        }

        assertEquals("synthetic cleanup failure", failure.message)
        assertEquals(
            listOf(
                "preferences clear",
                "preferences file delete",
                "keystore alias delete"
            ),
            attempts
        )
    }

    @Test
    fun isolatedRealKeystoreLifecycleSurvivesRecreationDeletionAndAliasReplacement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val namespace = SecretStorageNamespace(
            preferencesFile = "credential_instrumentation_$suffix",
            keyAlias = "io.aaps.predictivecopilot.instrumentation.$suffix"
        )
        val preferences = context.getSharedPreferences(
            namespace.preferencesFile,
            Context.MODE_PRIVATE
        )
        val keyStore = androidKeyStore()
        val coordinator = DeletionDurabilityCoordinator()

        try {
            val first = isolatedStorage(preferences, keyStore, namespace, coordinator)
            first.stagePending("synthetic-device-a", "synthetic-legacy-a")
            assertEquals("synthetic-device-a", first.readPending())
            assertEquals("synthetic-legacy-a", first.readPendingLegacyCleanupIntent())
            first.commitPending()
            first.discardRollback()

            val promoted = isolatedStorage(preferences, keyStore, namespace, coordinator)
            assertEquals("synthetic-device-a", promoted.read())
            assertTrue(keyStore.containsAlias(namespace.keyAlias))

            promoted.markDeletionPending("synthetic-legacy-a")
            assertTrue(promoted.isDeletionPending())
            assertEquals(
                "synthetic-legacy-a",
                promoted.readPendingDeletionLegacyCleanupIntent()
            )
            promoted.clear()

            val deleted = isolatedStorage(preferences, keyStore, namespace, coordinator)
            assertNull(deleted.read())
            assertTrue(deleted.isDeletionPending())
            assertFalse(keyStore.containsAlias(namespace.keyAlias))

            deleted.stagePending("synthetic-device-b", null)
            assertEquals("synthetic-device-b", deleted.readPending())
            deleted.commitPending()
            deleted.discardRollback()

            val replacement = isolatedStorage(preferences, keyStore, namespace, coordinator)
            assertEquals("synthetic-device-b", replacement.read())
            assertFalse(replacement.isDeletionPending())
            assertTrue(keyStore.containsAlias(namespace.keyAlias))
        } finally {
            runIndependentCleanup(
                "preferences clear" to {
                    check(preferences.edit().clear().commit()) {
                        "Unable to clear isolated credential test preferences"
                    }
                },
                "preferences file delete" to {
                    check(context.deleteSharedPreferences(namespace.preferencesFile)) {
                        "Unable to delete isolated credential test preferences file"
                    }
                },
                "keystore alias delete" to {
                    if (keyStore.containsAlias(namespace.keyAlias)) {
                        keyStore.deleteEntry(namespace.keyAlias)
                    }
                    check(!keyStore.containsAlias(namespace.keyAlias)) {
                        "Unable to delete isolated credential test Keystore alias"
                    }
                }
            )
        }
    }

    private fun isolatedStorage(
        preferences: SharedPreferences,
        keyStore: KeyStore,
        namespace: SecretStorageNamespace,
        coordinator: DeletionDurabilityCoordinator
    ): KeystoreSecretStorage = KeystoreSecretStorageTestFactory.create(
        preferences = preferences,
        keyStore = keyStore,
        namespace = namespace,
        deletionDurabilityCoordinator = coordinator
    )

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
