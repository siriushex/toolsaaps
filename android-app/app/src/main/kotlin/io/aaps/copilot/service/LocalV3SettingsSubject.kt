package io.aaps.copilot.service

internal const val LOCAL_SUBJECT_FIELD = "_localPairSubjectV1"

internal fun isLocalClientSubject(value: String): Boolean =
    value.length in 6..96 && value.startsWith("aaps-") && value == value.trim() &&
        value.substring(5).isNotBlank() && value.all { it.isLetterOrDigit() || it in " ._-" }

/** Only the private journal can persist this metadata; the public settings API rejects client-authored subjects. */
internal fun localV3WireSubject(collection: String, source: String, stored: String?): String =
    if (collection == "settings" && source == "local-v3-target" && stored != null)
        stored.also { require(isLocalClientSubject(it)) }
    else source
