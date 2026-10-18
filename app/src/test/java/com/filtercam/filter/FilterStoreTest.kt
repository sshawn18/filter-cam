package com.filtercam.filter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilterStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val jade = FilterParams(name = "Jade")
    private fun store() = FilterStore(File(tmp.root, "filters"), listOf(jade))
    private fun fp(n: String) = FilterParams(name = n, grain = 0.3f)

    @Test fun saveThenAllListsBuiltInFirstThenUser() {
        val s = store(); s.save(fp("Mine")).getOrThrow()
        val all = s.all()
        assertEquals(listOf("Jade", "Mine"), all.map { it.params.name })
        assertEquals(listOf(true, false), all.map { it.builtIn })
    }

    @Test fun namesAreValidated() {
        val s = store()
        for (bad in listOf("", "   ", "a".repeat(25), "../x", "x/y", "\uD83D\uDE00", "bad*name", "a.b")) {
            assertTrue("'$bad' should be rejected", s.validateName(bad).isFailure)
        }
        assertEquals("Ok Name_1-x", s.validateName("  Ok Name_1-x ").getOrThrow())
        assertTrue(s.validateName("jade").isFailure)
        assertTrue(s.save(fp("JADE")).isFailure)
    }

    @Test fun duplicateNeedsOverwriteAndIsCaseInsensitive() {
        val s = store(); s.save(fp("Mine")).getOrThrow()
        assertTrue(s.save(fp("mine")).isFailure)
        assertTrue(s.nameExistsAsUser("MINE"))
        s.save(fp("mine").copy(grain = 0.9f), overwrite = true).getOrThrow()
        val users = s.all().filter { !it.builtIn }
        assertEquals(1, users.size)
        assertEquals(0.9f, users[0].params.grain, 0.0001f)
    }

    @Test fun importRejectsBadHugeAndEmptyInput() {
        val s = store()
        assertEquals("Not a valid filter file", s.importJson("").exceptionOrNull()?.message)
        assertEquals("Not a valid filter file", s.importJson("\u0000\u0001binary").exceptionOrNull()?.message)
        assertEquals("That file is too large to be a filter", s.importJson("x".repeat(200_001)).exceptionOrNull()?.message)
        assertEquals(1, s.all().size)
    }

    @Test fun importAddsValidFilterAndRefusesDuplicate() {
        val s = store()
        assertEquals("Imp", s.importJson(fp("Imp").toJson()).getOrThrow().name)
        assertTrue(s.importJson(fp("Imp").toJson()).isFailure)
    }

    @Test fun corruptUserFileIsIgnoredAndUnknownKeysTolerated() {
        val s = store()
        s.save(fp("Good")).getOrThrow()
        File(tmp.root, "filters/broken.json").writeText("{ not json")
        File(tmp.root, "filters/Extra.json").writeText("""{"name":"Extra","grain":0.2,"newThing":1}""")
        File(tmp.root, "filters/.tmp-1").writeText("junk")
        assertEquals(listOf("Jade", "Extra", "Good"), s.all().map { it.params.name })
    }

    @Test fun renameAndDeleteOnlyForUserFilters() {
        val s = store(); s.save(fp("Old")).getOrThrow()
        assertEquals("New", s.rename("Old", "New").getOrThrow().name)
        assertEquals(listOf("Jade", "New"), s.all().map { it.params.name })
        assertTrue(s.rename("Jade", "Other").isFailure)
        assertTrue(s.rename("New", "jade").isFailure)
        s.save(fp("Taken")).getOrThrow()
        assertTrue(s.rename("New", "taken").isFailure)
        assertTrue(s.delete("Jade").isFailure)
        s.delete("New").getOrThrow()
        assertEquals(listOf("Jade", "Taken"), s.all().map { it.params.name })
    }

    @Test fun writeExportProducesParsableFile() {
        val out = File(tmp.root, "exports")
        val f = store().writeExport(fp("Ex Port"), out)
        assertTrue(f.name.endsWith(".json"))
        assertEquals("Ex Port", FilterParams.parse(f.readText()).getOrThrow().name)
    }

    @Test fun pickSelectionFallsBackToFirst() {
        val e = store().all()
        assertEquals("Jade", pickSelection(e, "Deleted").name)
        assertEquals("Jade", pickSelection(e, null).name)
    }
}
