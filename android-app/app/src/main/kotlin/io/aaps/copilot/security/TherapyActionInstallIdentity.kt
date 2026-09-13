package io.aaps.copilot.security

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class TherapyActionInstallIdentity(
    private val noBackupDirectory: File
) {
    @Synchronized
    fun loadOrCreate(): String? = runCatching {
        if (!noBackupDirectory.exists() && !noBackupDirectory.mkdirs()) {
            return@runCatching null
        }
        if (!noBackupDirectory.isDirectory) {
            return@runCatching null
        }

        val marker = File(noBackupDirectory, FILE_NAME)
        marker.readValidIdentityOrNull()?.let { return@runCatching it }

        val generated = UUID.randomUUID().toString()
        val temporary = File(noBackupDirectory, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(generated.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        if (!temporary.renameTo(marker)) {
            marker.writeText(generated, Charsets.US_ASCII)
            temporary.delete()
        }
        marker.readValidIdentityOrNull()
    }.getOrNull()

    private fun File.readValidIdentityOrNull(): String? {
        if (!isFile) return null
        val candidate = readText(Charsets.US_ASCII).trim()
        return candidate.takeIf { INSTALL_ID_PATTERN.matches(it) }
    }

    companion object {
        const val FILE_NAME = "therapy_action_install_id_v1"
        private val INSTALL_ID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
