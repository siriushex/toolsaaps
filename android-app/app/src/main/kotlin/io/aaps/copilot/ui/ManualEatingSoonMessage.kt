package io.aaps.copilot.ui

import android.content.Context
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.ManualMealResult
import io.aaps.copilot.data.repository.MealDeliveryStatus
import java.util.Locale

internal fun manualMealSubmissionMessage(context: Context, carbsGrams: Double, result: ManualMealResult?): String {
    if (result?.carbs != MealDeliveryStatus.SENT) return context.getString(R.string.overview_meal_unconfirmed)
    return buildString {
        append(context.getString(R.string.overview_meal_sent, String.format(Locale.getDefault(), "%.1f", carbsGrams)))
        when (result.eatingSoon.status) {
            MealDeliveryStatus.SENT -> append("\n" + context.getString(R.string.overview_eating_soon_sent))
            MealDeliveryStatus.BLOCKED -> append("\n" + manualEatingSoonBlockedMessage(context, result.eatingSoon.reason))
            MealDeliveryStatus.UNKNOWN -> append("\n" + context.getString(R.string.overview_eating_soon_unconfirmed))
            MealDeliveryStatus.NOT_REQUESTED -> Unit
        }
        val profileFailureExplained = result.eatingSoon.status == MealDeliveryStatus.BLOCKED &&
            result.eatingSoon.reason?.removePrefix("managed_preflight:") == "meal_profile_not_saved"
        if (!result.profileSaved && !profileFailureExplained) {
            append("\n" + context.getString(R.string.overview_meal_profile_failed))
        }
    }
}

internal fun manualEatingSoonBlockedMessage(context: Context, reason: String?): String {
    val code = reason?.removePrefix("managed_preflight:")
    val explanation = when (code) {
        "kill_switch_active" -> context.getString(R.string.eating_soon_reason_kill_switch)
        "actions_disarmed", "therapy_actions_not_armed" -> context.getString(R.string.eating_soon_reason_disarmed)
        "target_out_of_bounds", "target_bounds_invalid" -> context.getString(R.string.eating_soon_reason_bounds)
        "sensor_untrusted" -> context.getString(R.string.eating_soon_reason_sensor)
        "glucose_too_low" -> context.getString(R.string.eating_soon_reason_low_glucose)
        "glucose_stale", "glucose_timestamp_missing", "glucose_timestamp_invalid",
        "glucose_timestamp_future", "glucose_missing", "glucose_invalid" -> context.getString(R.string.eating_soon_reason_glucose)
        "accepted_forecast_unavailable", "forecast_timestamp_missing", "forecast_timestamp_invalid",
        "forecast_timestamp_future" -> context.getString(R.string.eating_soon_reason_forecast)
        "forecast_stale" -> context.getString(R.string.eating_soon_reason_forecast_stale)
        "chronology_unresolved" -> context.getString(R.string.eating_soon_reason_chronology)
        "settings_changed" -> context.getString(R.string.eating_soon_reason_settings)
        "target_authority_read_timeout", "safety_read_expired", "safety_lookup_failed" ->
            context.getString(R.string.eating_soon_reason_safety_read)
        "meal_profile_not_saved" -> context.getString(R.string.eating_soon_reason_profile)
        "missing_nightscout_url" -> context.getString(R.string.eating_soon_reason_endpoint)
        else -> forecastBlockExplanation(context, code)
    }
    val message = context.getString(R.string.overview_eating_soon_blocked)
    return if (explanation == null) message else "$message $explanation"
}

private fun forecastBlockExplanation(context: Context, code: String?): String? {
    for (minutes in listOf(5, 30, 60)) {
        when (code) {
            "forecast_${minutes}m_ci_low" -> return context.getString(R.string.eating_soon_reason_low_ci, minutes)
            "forecast_${minutes}m_too_low" -> return context.getString(R.string.eating_soon_reason_low_forecast, minutes)
            "forecast_${minutes}m_missing", "forecast_${minutes}m_invalid" ->
                return context.getString(R.string.eating_soon_reason_forecast)
        }
    }
    return null
}
