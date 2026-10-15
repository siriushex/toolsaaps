package io.aaps.copilot.data.local

import android.database.sqlite.SQLiteDatabase

internal fun SQLiteDatabase.dropGiColumnsForLegacyFixture() {
    for (table in listOf("meal_profile_overrides", "pending_meal_profile_intents")) {
        for (column in listOf("glycemicIndexValue", "glycemicIndexSource", "glycemicIndexReference")) {
            execSQL("ALTER TABLE `$table` DROP COLUMN `$column`")
        }
    }
}
