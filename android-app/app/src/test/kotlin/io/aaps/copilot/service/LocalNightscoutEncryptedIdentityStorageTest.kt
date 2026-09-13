package io.aaps.copilot.service

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.security.DeletionDurabilityCoordinator
import io.aaps.copilot.security.RuntimeSecretStorageNamespaces
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalNightscoutEncryptedIdentityStorageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferenceFiles = mutableListOf<String>()

    @After
    fun tearDown() {
        preferenceFiles.forEach(context::deleteSharedPreferences)
    }

    @Test
    fun binaryPlaintextIsEncryptedAndRestartReusesExactRecord() {
        val fixture = fixture()
        val plaintext = "pkcs12-password-and-local-api-secret".toByteArray()

        val first = fixture.storage.readOrCreate { plaintext.copyOf() }
        val restarted = fixture.recreate().readOrCreate { error("must reuse") }

        try {
            assertThat(first).isEqualTo(plaintext)
            assertThat(restarted).isEqualTo(plaintext)
            assertThat(fixture.preferences.all.keys)
                .containsExactly("active_version", "active_iv", "active_ciphertext")
            fixture.preferences.all.values.filterIsInstance<String>().forEach { encoded ->
                assertThat(encoded).doesNotContain("pkcs12-password-and-local-api-secret")
            }
        } finally {
            first.fill(0)
            restarted.fill(0)
            plaintext.fill(0)
        }
    }

    @Test
    fun concurrentFirstAccessPersistsOnlyOneAcceptedRecord() = runBlocking {
        val fixture = fixture()
        val generations = AtomicInteger()

        val records = List(12) {
            async(Dispatchers.Default) {
                fixture.storage.readOrCreate {
                    "record-${generations.incrementAndGet()}".toByteArray()
                }
            }
        }.awaitAll()

        try {
            assertThat(generations.get()).isEqualTo(1)
            assertThat(records.map { it.toList() }.distinct()).hasSize(1)
        } finally {
            records.forEach { it.fill(0) }
        }
    }

    @Test
    fun corruptCiphertextFailsClosedWithoutRotation() {
        val fixture = fixture()
        fixture.storage.readOrCreate { "accepted".toByteArray() }.fill(0)
        assertThat(
            fixture.preferences.edit().putString("active_ciphertext", "AAAA").commit()
        ).isTrue()
        val generations = AtomicInteger()

        val failure = runCatching {
            fixture.recreate().readOrCreate {
                generations.incrementAndGet()
                "replacement".toByteArray()
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(generations.get()).isEqualTo(0)
        assertThat(fixture.preferences.getString("active_ciphertext", null)).isEqualTo("AAAA")
    }

    @Test
    fun confirmedResetClearsRecordAndAliasThenAllowsFreshIdentity() {
        val fixture = fixture()
        fixture.storage.readOrCreate { "old".toByteArray() }.fill(0)
        assertThat(fixture.keyStore.containsAlias(fixture.alias)).isTrue()

        fixture.storage.reset()

        assertThat(fixture.preferences.all).isEmpty()
        assertThat(fixture.keyStore.containsAlias(fixture.alias)).isFalse()
        val fresh = fixture.storage.readOrCreate { "new".toByteArray() }
        try {
            assertThat(String(fresh)).isEqualTo("new")
        } finally {
            fresh.fill(0)
        }
    }

    @Test
    fun partialResetFailureLeavesFailClosedMarkerAndCannotRegenerate() {
        val fixture = fixture(deleteKey = { error("keystore_delete_failed") })
        fixture.storage.readOrCreate { "old".toByteArray() }.fill(0)

        val resetFailure = runCatching(fixture.storage::reset).exceptionOrNull()
        val generations = AtomicInteger()
        val readFailure = runCatching {
            fixture.recreate(deleteKey = { error("keystore_delete_failed") }).readOrCreate {
                generations.incrementAndGet()
                "replacement".toByteArray()
            }
        }.exceptionOrNull()

        assertThat(resetFailure).isNotNull()
        assertThat(fixture.preferences.getBoolean("reset_pending", false)).isTrue()
        assertThat(readFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(generations.get()).isEqualTo(0)
    }

    @Test
    fun failedActiveCommitQuarantinesAppliedProcessStateAndCannotBeReused() {
        val fixture = fixture(
            decoratePreferences = { preferences ->
                CommitInterceptingPreferences(preferences) { operation ->
                    operation.writesActiveRecord
                }
            }
        )

        val firstFailure = runCatching {
            fixture.storage.readOrCreate { "never-accepted".toByteArray() }
        }.exceptionOrNull()
        val generations = AtomicInteger()
        val retryFailure = runCatching {
            fixture.recreate().readOrCreate {
                generations.incrementAndGet()
                "replacement".toByteArray()
            }
        }.exceptionOrNull()

        assertThat(firstFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(retryFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(generations.get()).isEqualTo(0)
    }

    @Test
    fun failedClearAfterAliasDeletionIsQuarantinedAndCannotRegenerate() {
        val fixture = fixture(
            decoratePreferences = { preferences ->
                CommitInterceptingPreferences(preferences) { operation -> operation.clearsAll }
            }
        )
        fixture.storage.readOrCreate { "accepted".toByteArray() }.fill(0)

        val resetFailure = runCatching(fixture.storage::reset).exceptionOrNull()
        val generations = AtomicInteger()
        val retryFailure = runCatching {
            fixture.recreate().readOrCreate {
                generations.incrementAndGet()
                "replacement".toByteArray()
            }
        }.exceptionOrNull()

        assertThat(resetFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.keyStore.containsAlias(fixture.alias)).isFalse()
        assertThat(retryFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(generations.get()).isEqualTo(0)
    }

    @Test
    fun failedResetMarkerCommitLeavesDurableTombstoneAcrossFreshProcessState() {
        val fixture = fixture(
            decoratePreferences = { preferences ->
                CommitInterceptingPreferences(
                    delegate = preferences,
                    failBeforeApplying = { operation -> operation.writesResetPending }
                )
            }
        )
        fixture.storage.readOrCreate { "accepted".toByteArray() }.fill(0)

        val resetFailure = runCatching(fixture.storage::reset).exceptionOrNull()
        val generations = AtomicInteger()
        val freshProcessStorage = fixture.recreate(
            preferences = context.getSharedPreferences(
                fixture.preferenceName,
                Context.MODE_PRIVATE
            ),
            resetTombstonePreferences = context.getSharedPreferences(
                fixture.resetTombstonePreferenceName,
                Context.MODE_PRIVATE
            ),
            coordinator = DeletionDurabilityCoordinator()
        )
        val retryFailure = runCatching {
            freshProcessStorage.readOrCreate {
                generations.incrementAndGet()
                "replacement".toByteArray()
            }
        }.exceptionOrNull()

        assertThat(resetFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.preferences.getBoolean("reset_pending", false)).isFalse()
        assertThat(
            fixture.resetTombstonePreferences.getBoolean("reset_tombstone", false)
        ).isTrue()
        assertThat(retryFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(generations.get()).isEqualTo(0)
    }

    private fun fixture(
        decoratePreferences: (SharedPreferences) -> SharedPreferences = { it },
        deleteKey: (() -> Unit)? = null
    ): Fixture {
        val namespace = RuntimeSecretStorageNamespaces.LOCAL_NIGHTSCOUT_TLS
        val preferenceName = "${namespace.preferencesFile}_${UUID.randomUUID()}"
        preferenceFiles += preferenceName
        val resetTombstonePreferenceName = "${preferenceName}_reset_tombstone"
        preferenceFiles += resetTombstonePreferenceName
        val preferences = decoratePreferences(
            context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        )
        val resetTombstonePreferences = context.getSharedPreferences(
            resetTombstonePreferenceName,
            Context.MODE_PRIVATE
        )
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val alias = namespace.keyAlias
        val createKey = {
            val generator = KeyGenerator.getInstance("AES").apply { init(256) }
            val secret = generator.generateKey()
            keyStore.setEntry(
                alias,
                KeyStore.SecretKeyEntry(secret),
                KeyStore.PasswordProtection(charArrayOf())
            )
            secret
        }
        val actualDelete = deleteKey ?: { keyStore.deleteEntry(alias) }
        val coordinator = DeletionDurabilityCoordinator()
        val storage = LocalNightscoutEncryptedIdentityStorage(
            preferences = preferences,
            resetTombstonePreferences = resetTombstonePreferences,
            keyStore = keyStore,
            namespace = namespace,
            coordinator = coordinator,
            createKey = createKey,
            deleteKey = actualDelete
        )
        return Fixture(
            preferenceName,
            resetTombstonePreferenceName,
            preferences,
            resetTombstonePreferences,
            keyStore,
            alias,
            coordinator,
            createKey,
            actualDelete,
            storage
        )
    }

    private data class Fixture(
        val preferenceName: String,
        val resetTombstonePreferenceName: String,
        val preferences: android.content.SharedPreferences,
        val resetTombstonePreferences: android.content.SharedPreferences,
        val keyStore: KeyStore,
        val alias: String,
        val coordinator: DeletionDurabilityCoordinator,
        val createKey: () -> SecretKey,
        val deleteKey: () -> Unit,
        val storage: LocalNightscoutEncryptedIdentityStorage
    ) {
        fun recreate(
            deleteKey: () -> Unit = this.deleteKey,
            preferences: SharedPreferences = this.preferences,
            resetTombstonePreferences: SharedPreferences = this.resetTombstonePreferences,
            coordinator: DeletionDurabilityCoordinator = this.coordinator
        ) =
            LocalNightscoutEncryptedIdentityStorage(
                preferences = preferences,
                resetTombstonePreferences = resetTombstonePreferences,
                keyStore = keyStore,
                namespace = RuntimeSecretStorageNamespaces.LOCAL_NIGHTSCOUT_TLS,
                coordinator = coordinator,
                createKey = createKey,
                deleteKey = deleteKey
            )
    }

    private data class CommitOperation(
        var writesActiveRecord: Boolean = false,
        var writesResetPending: Boolean = false,
        var clearsAll: Boolean = false
    )

    private class CommitInterceptingPreferences(
        private val delegate: SharedPreferences,
        private val failAfterApplying: (CommitOperation) -> Boolean = { false },
        private val failBeforeApplying: (CommitOperation) -> Boolean = { false }
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            val operation = CommitOperation()
            return object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?) = apply {
                    editor.putString(key, value)
                }

                override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                    editor.putStringSet(key, values)
                }

                override fun putInt(key: String?, value: Int) = apply {
                    if (key == "active_version") operation.writesActiveRecord = true
                    editor.putInt(key, value)
                }

                override fun putLong(key: String?, value: Long) = apply {
                    editor.putLong(key, value)
                }

                override fun putFloat(key: String?, value: Float) = apply {
                    editor.putFloat(key, value)
                }

                override fun putBoolean(key: String?, value: Boolean) = apply {
                    if (key == "reset_pending" && value) operation.writesResetPending = true
                    editor.putBoolean(key, value)
                }

                override fun remove(key: String?) = apply { editor.remove(key) }

                override fun clear() = apply {
                    operation.clearsAll = true
                    editor.clear()
                }

                override fun commit(): Boolean {
                    if (failBeforeApplying(operation)) return false
                    val committed = editor.commit()
                    return committed && !failAfterApplying(operation)
                }

                override fun apply() = editor.apply()
            }
        }
    }
}
