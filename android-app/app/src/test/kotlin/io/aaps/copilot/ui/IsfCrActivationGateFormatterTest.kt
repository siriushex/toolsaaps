package io.aaps.copilot.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsfCrActivationGateFormatterTest {

    @Test
    fun parseRollingGateWindows_parsesValidRows() {
        val raw = """
            [
              {
                "days": 14,
                "available": true,
                "eligible": false,
                "reason": "rolling_mae30_high",
                "matchedSamples": 420,
                "mae30Mmol": 1.24,
                "mae60Mmol": 1.55
              },
              {
                "days": 30,
                "available": true,
                "eligible": true,
                "reason": "ok",
                "matchedSamples": 900
              }
            ]
        """.trimIndent()

        val rows = parseRollingGateWindows(raw)

        assertEquals(2, rows.size)
        assertEquals(14, rows[0].days)
        assertTrue(rows[0].available)
        assertEquals(false, rows[0].eligible)
        assertEquals("rolling_mae30_high", rows[0].reason)
        assertEquals(420, rows[0].matchedSamples)
        assertEquals(1.24, rows[0].mae30Mmol ?: 0.0, 1e-6)
        assertEquals(1.55, rows[0].mae60Mmol ?: 0.0, 1e-6)

        assertEquals(30, rows[1].days)
        assertEquals("ok", rows[1].reason)
        assertEquals(900, rows[1].matchedSamples)
    }

    @Test
    fun parseRollingGateWindows_ignoresInvalidItemsAndMalformedJson() {
        val withInvalid = """
            [
              {"days": -1, "reason": "bad"},
              {"days": 0, "reason": "bad2"},
              {"days": 90, "available": false, "eligible": false, "reason": ""}
            ]
        """.trimIndent()

        val rows = parseRollingGateWindows(withInvalid)
        assertEquals(1, rows.size)
        assertEquals(90, rows.first().days)
        assertEquals("n/a", rows.first().reason)

        val malformed = parseRollingGateWindows("{not-json")
        assertTrue(malformed.isEmpty())
    }

    @Test
    fun formatRollingGateWindowLine_formatsStatusAndMae() {
        val fail = RollingGateWindowUi(
            days = 14,
            available = true,
            eligible = false,
            reason = "rolling_mae60_high",
            matchedSamples = 412,
            mae30Mmol = 1.22,
            mae60Mmol = 1.56
        )
        val missing = RollingGateWindowUi(
            days = 30,
            available = false,
            eligible = false,
            reason = "rolling_report_missing",
            matchedSamples = null,
            mae30Mmol = null,
            mae60Mmol = null
        )
        val pass = RollingGateWindowUi(
            days = 90,
            available = true,
            eligible = true,
            reason = "ok",
            matchedSamples = 1500,
            mae30Mmol = null,
            mae60Mmol = null
        )

        val failLine = formatRollingGateWindowLine(fail) { value ->
            if (value == null) "--" else String.format(Locale.US, "%.2f", value)
        }
        val missingLine = formatRollingGateWindowLine(missing) { value ->
            if (value == null) "--" else String.format(Locale.US, "%.2f", value)
        }
        val passLine = formatRollingGateWindowLine(pass) { value ->
            if (value == null) "--" else String.format(Locale.US, "%.2f", value)
        }

        assertEquals(
            "Rolling 14d gate: status=fail, reason=rolling_mae60_high, n=412, MAE30=1.22, MAE60=1.56",
            failLine
        )
        assertEquals(
            "Rolling 30d gate: status=missing, reason=rolling_report_missing, n=0",
            missingLine
        )
        assertEquals(
            "Rolling 90d gate: status=pass, reason=ok, n=1500",
            passLine
        )
    }
}
