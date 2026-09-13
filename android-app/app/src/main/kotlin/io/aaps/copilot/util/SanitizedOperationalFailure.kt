package io.aaps.copilot.util

internal interface SanitizedOperationalFailure {
    val errorCode: String
    val errorType: String
}

internal fun boundedOperationalErrorType(error: Throwable): String = error::class.java.simpleName
    .filter { character -> character.isLetterOrDigit() || character == '_' || character == '$' }
    .take(MAX_OPERATIONAL_ERROR_TYPE_LENGTH)
    .ifBlank { "Exception" }

private const val MAX_OPERATIONAL_ERROR_TYPE_LENGTH = 80
