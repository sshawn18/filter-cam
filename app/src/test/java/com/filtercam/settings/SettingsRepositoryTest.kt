package com.filtercam.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsRepositoryTest {
    private class Fake : KeyValueStore {
        val m = HashMap<String, Any?>()
        override fun getBoolean(k: String, def: Boolean) = m[k] as? Boolean ?: def
        override fun putBoolean(k: String, v: Boolean) { m[k] = v }
        override fun getInt(k: String, def: Int) = m[k] as? Int ?: def
        override fun putInt(k: String, v: Int) { m[k] = v }
        override fun getString(k: String, def: String?) = if (m.containsKey(k)) m[k] as? String else def
        override fun putString(k: String, v: String?) { m[k] = v }
    }

    @Test fun defaults() {
        val s = SettingsRepository(Fake())
        assertFalse(s.gridDefault); assertEquals(FlashSetting.OFF, s.flashDefault)
        assertEquals(95, s.jpegQuality); assertFalse(s.mirrorFront); assertNull(s.lastFilter)
    }

    @Test fun roundTripAndQualityClamp() {
        val kv = Fake(); val s = SettingsRepository(kv)
        s.gridDefault = true; s.flashDefault = FlashSetting.AUTO; s.mirrorFront = true; s.lastFilter = "Mine"
        s.jpegQuality = 10
        val again = SettingsRepository(kv)
        assertTrue(again.gridDefault); assertEquals(FlashSetting.AUTO, again.flashDefault)
        assertTrue(again.mirrorFront); assertEquals("Mine", again.lastFilter); assertEquals(70, again.jpegQuality)
        again.jpegQuality = 500
        assertEquals(100, again.jpegQuality)
    }

    @Test fun unknownFlashValueFallsBackToOff() {
        val kv = Fake(); kv.putString("flash", "TURBO")
        assertEquals(FlashSetting.OFF, SettingsRepository(kv).flashDefault)
    }
}
