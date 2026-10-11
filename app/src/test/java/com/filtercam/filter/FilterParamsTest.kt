package com.filtercam.filter

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterParamsTest {
    @Test fun parsesJsonAndAppliesDefaults() {
        val p = FilterParams.fromJson("""{"name":"X","contrast":1.2}""")
        assertEquals("X", p.name)
        assertEquals(1.2f, p.contrast, 0.0001f)
        assertEquals(0f, p.grain, 0.0001f)
        assertEquals(listOf(0f, 0f, 0f), p.shadowColor)
    }

    @Test fun clampedLimitsEveryScalarToItsRange() {
        val p = FilterParams(name = "X", contrast = 9f, grain = -3f, shadowColor = listOf(5f, -1f, 0.5f)).clamped()
        assertEquals(ParamKey.CONTRAST.range.endInclusive, p.contrast, 0.0001f)
        assertEquals(ParamKey.GRAIN.range.start, p.grain, 0.0001f)
        assertEquals(listOf(1f, 0f, 0.5f), p.shadowColor)
    }

    @Test fun withThenGetRoundTripsForEveryKey() {
        var p = FilterParams(name = "X")
        for (k in ParamKey.values()) {
            val v = k.range.endInclusive
            p = p.with(k, v)
            assertEquals(k.name, v, p.get(k), 0.0001f)
        }
    }
}
