package io.aaps.copilot.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val UPDATE_ORIGIN = "https://diai.centv.ru"
const val UPDATE_MANIFEST_URL = "$UPDATE_ORIGIN/app-updates/latest.json"
internal const val MAX_APK_BYTES = 200L * 1024 * 1024
internal const val MAX_MANIFEST_BYTES = 16 * 1024

@Serializable
data class AppUpdateManifest(
    val schema: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sizeBytes: Long,
    val sha256: String,
    val apkUrl: String,
    val notes: String = ""
) {
    fun validate(expectedPackage: String): AppUpdateManifest = apply {
        require(schema == 1 && packageName == expectedPackage)
        require(versionCode in 1..2_100_000_000L)
        require(versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,39}")))
        require(minSdk in 31..100 && sizeBytes in 1..MAX_APK_BYTES)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(apkUrl == "$UPDATE_ORIGIN/app-updates/$sha256.apk")
        require(notes.length <= 2000 && notes.none { it.isISOControl() && it != '\n' })
    }

    companion object {
        fun parse(bytes: ByteArray, expectedPackage: String): AppUpdateManifest {
            require(bytes.size <= MAX_MANIFEST_BYTES)
            return Json.decodeFromString<AppUpdateManifest>(bytes.decodeToString(throwOnInvalidSequence = true))
                .validate(expectedPackage)
        }
    }
}

enum class UpdateFailure { NETWORK, INVALID_RELEASE, INCOMPATIBLE, STORAGE, INVALID_APK, INSTALLER }
class AppUpdateException(val reason: UpdateFailure) : Exception(reason.name)

internal fun requireMatchingApk(
    release: AppUpdateManifest, installedVersion: Long, archivePackage: String,
    archiveVersion: Long, archiveVersionName: String?, archiveMinSdk: Int,
    installedSigners: Set<String>, archiveSigners: Set<String>
) {
    if (release.packageName != archivePackage || release.versionCode != archiveVersion ||
        release.versionName != archiveVersionName || release.minSdk != archiveMinSdk ||
        archiveVersion <= installedVersion || installedSigners.isEmpty() || archiveSigners != installedSigners
    ) throw AppUpdateException(UpdateFailure.INVALID_APK)
}
