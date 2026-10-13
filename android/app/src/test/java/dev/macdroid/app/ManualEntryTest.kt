package dev.macdroid.app

import dev.macdroid.app.ui.setup.ManualEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Review Focus #4
class ManualEntryTest {
    @Test fun acceptsSpacesDashesAndLowercase() {
        assertEquals("MZXW6YTBOI2DGNBVGY3TQOJQGE", ManualEntry.normalizedCodeOrNull(" mzxw 6ytb-oi2d gnbv gy3t qojq ge "))
    }

    @Test fun rejectsWrongLength() {
        assertNull(ManualEntry.normalizedCodeOrNull("MZXW6YTB"))
        assertNull(ManualEntry.normalizedCodeOrNull("MZXW6YTBOI2DGNBVGY3TQOJQGEA"))
    }

    @Test fun rejectsCharactersOutsideBase32() {
        assertNull(ManualEntry.normalizedCodeOrNull("MZXW6YTBOI2DGNBVGY3TQOJQG1"))
    }

    @Test fun rejectsEmpty() {
        assertNull(ManualEntry.normalizedCodeOrNull(""))
    }
}
