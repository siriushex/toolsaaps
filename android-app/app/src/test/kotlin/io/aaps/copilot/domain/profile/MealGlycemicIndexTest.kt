package io.aaps.copilot.domain.profile

import org.junit.Assert.*
import org.junit.Test

class MealGlycemicIndexTest {
    @Test fun missingAndInvalidManualValuesDoNotInventGi() {
        for (raw in listOf("", " ", "NaN", "Infinity", "-1", "201", "bad", "1".repeat(100))) {
            assertNull(MealGlycemicIndex.fromManualInput(raw))
        }
        assertEquals(55.5, MealGlycemicIndex.fromManualInput("55,5")!!.value, 0.0)
        assertEquals(GlycemicIndexSource.USER, MealGlycemicIndex.fromManualInput("80")!!.source)
    }

    @Test fun catalogNeedsExplicitSourceAndUnsupportedSourceIsNotTrusted() {
        assertNull(MealGlycemicIndex.fromStored(50.0, "CATALOG", null))
        assertNull(MealGlycemicIndex.fromStored(50.0, "AI_ESTIMATE", "guess"))
        assertEquals("catalog/item/1", MealGlycemicIndex.fromStored(50.0, "CATALOG", "catalog/item/1")!!.reference)
    }

    @Test fun contextRequiresExactCanonicalRevisionAndIsImmutable() {
        val index = MealGlycemicIndex.fromManualInput("80")!!
        val map = mutableMapOf("12" to MealGlycemicIndexOverride("r1", index))
        val context = MealGlycemicIndexContext(map)
        map.clear()
        assertSame(index, context.indexFor(MealTherapyReference("12", "r1", MealTherapyReferenceTrust.TRUSTED)))
        assertNull(context.indexFor(MealTherapyReference("12", "r2", MealTherapyReferenceTrust.TRUSTED)))
        assertNull(context.indexFor(MealTherapyReference("12", "r1", MealTherapyReferenceTrust.CONFLICT)))
        assertNull(context.indexFor(MealTherapyReference("12", "r1")))
    }
}
