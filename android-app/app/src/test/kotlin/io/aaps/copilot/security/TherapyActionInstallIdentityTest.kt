package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TherapyActionInstallIdentityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun identityPersistsInsideNoBackupDirectory() {
        val directory = temporaryFolder.newFolder("no-backup")
        val identity = TherapyActionInstallIdentity(directory)

        val first = identity.loadOrCreate()
        val second = TherapyActionInstallIdentity(directory).loadOrCreate()

        assertThat(first).isNotNull()
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun corruptedIdentityIsReplacedInsteadOfTrusted() {
        val directory = temporaryFolder.newFolder("corrupt")
        File(directory, TherapyActionInstallIdentity.FILE_NAME).writeText("restored-or-corrupt")

        val identity = TherapyActionInstallIdentity(directory).loadOrCreate()

        assertThat(identity).isNotNull()
        assertThat(identity).isNotEqualTo("restored-or-corrupt")
    }

    @Test
    fun unusableNoBackupPathFailsClosed() {
        val notDirectory = temporaryFolder.newFile("not-a-directory")

        assertThat(TherapyActionInstallIdentity(notDirectory).loadOrCreate()).isNull()
    }
}
