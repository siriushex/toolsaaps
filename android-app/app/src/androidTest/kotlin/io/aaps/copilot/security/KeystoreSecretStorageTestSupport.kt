package io.aaps.copilot.security

import android.content.SharedPreferences
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.security.KeyStore

internal object KeystoreSecretStorageTestFactory {
    private val primaryConstructor: Constructor<*> by lazy {
        KeystoreSecretStorage::class.java.declaredConstructors.single { constructor ->
            val parameterTypes = constructor.parameterTypes
            parameterTypes.size == 4 &&
                parameterTypes[2] == SecretStorageNamespace::class.java &&
                parameterTypes[3] == DeletionDurabilityCoordinator::class.java
        }.also { constructor ->
            check(Modifier.isPrivate(constructor.modifiers)) {
                "Isolated credential test storage must use the private primary constructor"
            }
            constructor.isAccessible = true
        }
    }

    fun create(
        preferences: SharedPreferences,
        keyStore: KeyStore,
        namespace: SecretStorageNamespace,
        deletionDurabilityCoordinator: DeletionDurabilityCoordinator
    ): KeystoreSecretStorage {
        val preferencesProvider: () -> SharedPreferences = { preferences }
        val keyStoreProvider: () -> KeyStore = { keyStore }
        return primaryConstructor.newInstance(
            preferencesProvider,
            keyStoreProvider,
            namespace,
            deletionDurabilityCoordinator
        ) as KeystoreSecretStorage
    }
}

internal fun runIndependentCleanup(vararg cleanupSteps: Pair<String, () -> Unit>) {
    var firstFailure: Throwable? = null
    cleanupSteps.forEach { (label, cleanup) ->
        try {
            cleanup()
        } catch (failure: Throwable) {
            if (firstFailure == null) {
                firstFailure = failure
            } else {
                firstFailure?.addSuppressed(
                    IllegalStateException("Cleanup step failed: $label", failure)
                )
            }
        }
    }
    firstFailure?.let { throw it }
}
