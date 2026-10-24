package com.filtercam.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledFilterAssetsTest {
    private val dir = File("src/main/assets/filters")

    @Test fun everyBundledFilterParsesAndNamesAreUnique() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("expected bundled filters in ${dir.absolutePath}", files.isNotEmpty())
        val names = files.map { f ->
            val p = FilterParams.parse(f.readText())
            assertTrue("${f.name}: ${p.exceptionOrNull()?.message}", p.isSuccess)
            p.getOrThrow().name
        }
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)
        assertTrue(names.containsAll(listOf("Jade", "Fallen Angels", "Summer Blue", "Edgerunners")))
    }
}
