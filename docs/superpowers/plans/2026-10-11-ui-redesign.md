# FilterCam UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the prototype camera screen into a polished, stock-style camera app with an in-app gallery, settings, and importable/exportable user filters.

**Architecture:** Camera stays the home screen; Tune/Manage-filters are bottom sheets; Gallery, Viewer and Settings are screens switched by a `sealed interface Screen` held in state (no Navigation library). Filter logic lives in a JVM-testable `FilterStore` (takes a `File` dir); one `FilterViewModel` ties store + settings to the UI. The GL engine and capture pipeline are unchanged.

**Tech Stack:** Kotlin 2.0.20, Jetpack Compose (BOM 2024.09.00) + Material3 + material-icons-extended, CameraX 1.3.4, kotlinx-serialization-json 1.7.2, JUnit4, Android SharedPreferences, MediaStore, FileProvider.

**Spec:** `docs/superpowers/specs/2026-10-11-ui-redesign-design.md`

## Global Constraints

- Package `com.filtercam`; minSdk 29; portrait-only; project root `X:\CC\filter-cam`; env: `. scripts\env.ps1` before Gradle; build/test: `.\gradlew.bat assembleDebug testDebugUnitTest`.
- Dark Material 3 theme only (no light theme in v1). App name/label "FilterCam".
- Unchanged: `Shaders.kt`, `FilterProgram`, `PhotoProcessor`, capture resolution cap (4032x3024), `FilterParams` JSON schema (only validation/`toJson` are added).
- Filter names: trimmed, 1-24 chars, only letters/digits/space/`-`/`_`; unique case-insensitively; cannot equal a built-in name; built-in filters (assets) are read-only (no rename/delete).
- User filters live in `filesDir/filters/*.json`, written to a temp file then moved into place; import limit 200_000 characters.
- Gallery shows only images in `Pictures/FilterCam`; known limit: after an uninstall+reinstall, photos from the old install are not visible to the app (MediaStore ownership); `installDebug` updates keep them.
- No new libraries except `androidx.compose.material:material-icons-extended` (BOM-managed). Ruling vs spec text: the spec said icons-core, but flash/grid/flip/tune icons only exist in extended.
- Every commit message ends with the line `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Existing tests (`FilterParamsTest`, `PhotoProcessorGoldenTest`) must keep passing/compiling.
- The phone (Galaxy S24 Ultra, `adb`) is connected; UI tasks are verified on it (Task 11) in addition to builds.

## Review Focus

1. Importing a huge, binary, empty or non-filter file (e.g. a photo renamed `.json`): clear message, no crash, no memory blowup (Task 2 + Task 3 tests).
2. Odd filter names: emoji, 200 characters, only spaces, `../x`, case-only differences, a built-in name (Task 3 tests).
3. A corrupt or hand-edited user filter file in `filesDir/filters` must not break the filter list; unknown extra JSON keys are tolerated (Task 3 tests).
4. Deleting or renaming the currently selected filter: selection falls back to a valid filter and the camera keeps working (Task 3 `pickSelection` test, Task 6).
5. Gallery with zero photos, a photo deleted outside the app, or a thumbnail that fails to load: placeholder/empty text, no crash (Task 9).

## File Structure

```
app/src/main/java/com/filtercam/
  MainActivity.kt                      # theme + permission gate + AppRoot (rewritten, Task 10)
  filter/FilterParams.kt               # + parse(), toJson() (Task 2)
  filter/FilterStore.kt                # NEW user/bundled filter store + pickSelection (Task 3)
  filter/BundledFilters.kt             # NEW load assets/filters (Task 3); FilterRepository.kt deleted (Task 6)
  settings/SettingsRepository.kt       # NEW prefs wrapper + FlashSetting (Task 4)
  camera/CameraController.kt           # + flash/zoom/focus/unbind/mirror (Task 5)
  storage/PhotoSaver.kt                # + quality param (Task 5)
  gallery/GalleryRepository.kt         # NEW (Task 9)
  ui/AppRoot.kt                        # NEW Screen state + scaffold + snackbar (Task 10)
  ui/FilterViewModel.kt                # rewritten (Task 6)
  ui/DevPanel.kt                       # themed + taller (Task 7)
  ui/theme/Theme.kt                    # NEW (Task 1)
  ui/camera/CameraScreen.kt, FilterStrip.kt, CameraControls.kt   # NEW/rewritten (Task 8); old ui/CameraScreen.kt deleted
  ui/filters/FilterSheets.kt           # NEW TuneSheet, ManageFiltersSheet, NameDialog, share helper (Task 7)
  ui/gallery/GalleryScreen.kt          # NEW grid + viewer (Task 9)
  ui/settings/SettingsScreen.kt, PermissionScreen.kt             # NEW (Task 10)
app/src/main/res/{values/themes.xml, values/colors.xml, drawable/ic_launcher_foreground.xml, mipmap-anydpi-v26/ic_launcher.xml, xml/file_paths.xml}
app/src/test/java/com/filtercam/{filter/FilterParamsTest.kt (extend), filter/FilterStoreTest.kt, settings/SettingsRepositoryTest.kt}
```

---

### Task 1: Theme, app icon, manifest, icons dependency

**Files:**
- Create: `app/src/main/java/com/filtercam/ui/theme/Theme.kt`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values/colors.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/build.gradle.kts` (dependency), `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `@Composable fun FilterCamTheme(content: @Composable () -> Unit)`; `object Palette { val Accent: Color; val Panel: Color; val PanelSelected: Color }` (all in `com.filtercam.ui.theme`).

- [ ] **Step 1: Add the dependency** in `app/build.gradle.kts` inside `dependencies { }`:

```kotlin
    implementation("androidx.compose.material:material-icons-extended")
```

- [ ] **Step 2: `Theme.kt`**

```kotlin
package com.filtercam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Accent = Color(0xFFFFC83D)
    val Panel = Color(0xFF161616)
    val PanelSelected = Color(0xFF2A2A2A)
}

private val Dark = darkColorScheme(
    primary = Palette.Accent, onPrimary = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF121212), onSurface = Color.White,
    surfaceVariant = Palette.Panel, onSurfaceVariant = Color(0xFFB8B8B8),
    error = Color(0xFFFF6B6B),
)

@Composable
fun FilterCamTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Dark, content = content)
```

- [ ] **Step 3: Resources**

`res/values/colors.xml`:
```xml
<resources><color name="ic_launcher_background">#0B0B0B</color></resources>
```
`res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.FilterCam" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/black</item>
        <item name="android:statusBarColor">@android:color/black</item>
        <item name="android:navigationBarColor">@android:color/black</item>
    </style>
</resources>
```
`res/drawable/ic_launcher_foreground.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFC83D" android:pathData="M54,54m-26,0a26,26 0,1 1,52 0a26,26 0,1 1,-52 0"/>
    <path android:fillColor="#FF0B0B0B" android:pathData="M54,54m-17,0a17,17 0,1 1,34 0a17,17 0,1 1,-34 0"/>
    <path android:fillColor="#FF1F6F5C" android:pathData="M54,54m-9,0a9,9 0,1 1,18 0a9,9 0,1 1,-18 0"/>
</vector>
```
`res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```

- [ ] **Step 4: Manifest** — on `<application>` replace the `android:theme` and add icon attributes:

```xml
    <application android:label="FilterCam"
        android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher"
        android:theme="@style/Theme.FilterCam">
```

- [ ] **Step 5: Build** — Run: `. scripts\env.ps1; .\gradlew.bat assembleDebug` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: dark theme, app icon, icons dependency"` (+ trailer).

---

### Task 2: FilterParams validation and `toJson` (TDD)

**Files:**
- Modify: `app/src/main/java/com/filtercam/filter/FilterParams.kt`
- Test: `app/src/test/java/com/filtercam/filter/FilterParamsTest.kt` (append tests)

**Interfaces:**
- Produces: `FilterParams.parse(json: String): Result<FilterParams>` (failure message is user-presentable), `FilterParams.fromJson(json): FilterParams` (throws), `FilterParams.toJson(): String`.

- [ ] **Step 1: Append failing tests** inside `class FilterParamsTest`:

```kotlin
    @Test fun parseRejectsGarbageWithFriendlyMessage() {
        val r = FilterParams.parse("hello")
        assertEquals("Not a valid filter file", r.exceptionOrNull()?.message)
    }

    @Test fun parseRejectsShortColorList() {
        val r = FilterParams.parse("""{"name":"X","shadowColor":[0.1]}""")
        assertEquals("shadowColor and highlightColor must each have exactly 3 numbers", r.exceptionOrNull()?.message)
    }

    @Test fun parseRejectsBlankName() {
        val r = FilterParams.parse("""{"name":"   "}""")
        assertEquals("The filter has no name", r.exceptionOrNull()?.message)
    }

    @Test fun parseToleratesUnknownKeysAndClamps() {
        val p = FilterParams.parse("""{"name":"X","contrast":9,"future":true}""").getOrThrow()
        assertEquals(ParamKey.CONTRAST.range.endInclusive, p.contrast, 0.0001f)
    }

    @Test fun toJsonRoundTrips() {
        val p = FilterParams(name = "Rt", exposure = 0.5f, shadowColor = listOf(0.1f, 0.2f, 0.3f))
        assertEquals(p, FilterParams.parse(p.toJson()).getOrThrow())
    }
```
- [ ] **Step 2: Run** `.\gradlew.bat testDebugUnitTest` — Expected: FAIL (compilation: `parse`/`toJson` unresolved).
- [ ] **Step 3: Implement** — in `FilterParams.kt` add the import `import kotlinx.serialization.SerializationException`, add inside the data class (after `clamped()`):

```kotlin
    fun toJson(): String = prettyJson.encodeToString(this)
```
and replace the companion object with:

```kotlin
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

        fun parse(s: String): Result<FilterParams> = runCatching {
            val p = try {
                json.decodeFromString<FilterParams>(s)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Not a valid filter file")
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Not a valid filter file")
            }
            require(p.name.isNotBlank()) { "The filter has no name" }
            require(p.shadowColor.size == 3 && p.highlightColor.size == 3) {
                "shadowColor and highlightColor must each have exactly 3 numbers"
            }
            p.clamped()
        }

        fun fromJson(s: String): FilterParams = parse(s).getOrThrow()
    }
```
(`SerializationException` extends `IllegalArgumentException`; the first catch must stay first or be merged. Keep as written: both map to the same message.)
- [ ] **Step 4: Run** `.\gradlew.bat testDebugUnitTest` — Expected: all FilterParamsTest tests PASS (8 total).
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: validated FilterParams.parse and toJson"` (+ trailer).

---

### Task 3: FilterStore (TDD) and bundled loader

**Files:**
- Create: `app/src/main/java/com/filtercam/filter/FilterStore.kt`, `app/src/main/java/com/filtercam/filter/BundledFilters.kt`
- Test: `app/src/test/java/com/filtercam/filter/FilterStoreTest.kt`

**Interfaces:**
- Consumes: `FilterParams.parse/toJson/clamped` (Task 2).
- Produces:
  - `class FilterStore(dir: File, bundled: List<FilterParams>)` with `data class Entry(val params: FilterParams, val builtIn: Boolean)`; `all(): List<Entry>`; `validateName(name: String): Result<String>` (returns trimmed name); `nameExistsAsUser(name: String): Boolean`; `save(params, overwrite: Boolean = false): Result<FilterParams>`; `importJson(text: String): Result<FilterParams>`; `rename(old: String, new: String): Result<FilterParams>`; `delete(name: String): Result<Unit>`; `writeExport(params: FilterParams, outDir: File): File`; `fun pickSelection(entries: List<FilterStore.Entry>, current: String?): FilterParams` (top-level).
  - `fun loadBundledFilters(context: Context): List<FilterParams>`.

- [ ] **Step 1: Write the failing tests** `FilterStoreTest.kt`:

```kotlin
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
        for (bad in listOf("", "   ", "a".repeat(25), "../x", "x/y", "😀", "bad*name", "a.b")) {
            assertTrue("'$bad' should be rejected", s.validateName(bad).isFailure)
        }
        assertEquals("Ok Name_1-x", s.validateName("  Ok Name_1-x ").getOrThrow())
        assertTrue(s.validateName("jade").isFailure)       // built-in, case-insensitive
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
        val f = store().writeExport(fp("Ex/Port"), out)
        assertTrue(f.name.endsWith(".json"))
        assertEquals("Ex/Port", FilterParams.parse(f.readText()).getOrThrow().name)
    }

    @Test fun pickSelectionFallsBackToFirst() {
        val e = store().all()
        assertEquals("Jade", pickSelection(e, "Deleted").name)
        assertEquals("Jade", pickSelection(e, null).name)
    }
}
```
- [ ] **Step 2: Run** `.\gradlew.bat testDebugUnitTest` — Expected: FAIL (compilation: `FilterStore` unresolved).
- [ ] **Step 3: Implement `FilterStore.kt`**

```kotlin
package com.filtercam.filter

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FilterStore(private val dir: File, private val bundled: List<FilterParams>) {
    data class Entry(val params: FilterParams, val builtIn: Boolean)

    init { dir.mkdirs() }

    fun all(): List<Entry> = bundled.map { Entry(it, true) } + users().map { Entry(it, false) }

    fun nameExistsAsUser(name: String): Boolean = userFile(name.trim()) != null

    fun validateName(name: String): Result<String> {
        val n = name.trim()
        val error = when {
            n.isEmpty() -> "Enter a name"
            n.length > MAX_NAME -> "Name must be $MAX_NAME characters or fewer"
            !NAME_RE.matches(n) -> "Use letters, numbers, spaces, - and _ only"
            bundled.any { it.name.equals(n, ignoreCase = true) } -> "\"$n\" is a built-in filter name"
            else -> null
        }
        return if (error == null) Result.success(n) else Result.failure(IllegalArgumentException(error))
    }

    fun save(params: FilterParams, overwrite: Boolean = false): Result<FilterParams> = runCatching {
        val name = validateName(params.name).getOrThrow()
        val existing = userFile(name)
        if (existing != null && !overwrite) throw IllegalArgumentException("A filter named \"$name\" already exists")
        val saved = params.copy(name = name).clamped()
        write(File(dir, "$name.json"), saved)
        if (existing != null && existing.name != "$name.json") existing.delete()
        saved
    }

    fun importJson(text: String): Result<FilterParams> {
        if (text.length > MAX_IMPORT_CHARS) return Result.failure(IllegalArgumentException("That file is too large to be a filter"))
        return FilterParams.parse(text).mapCatching { save(it).getOrThrow() }
    }

    fun rename(old: String, new: String): Result<FilterParams> = runCatching {
        val oldFile = userFile(old) ?: throw IllegalArgumentException("Built-in filters can't be renamed")
        val name = validateName(new).getOrThrow()
        val clash = userFile(name)
        if (clash != null && clash != oldFile) throw IllegalArgumentException("A filter named \"$name\" already exists")
        val p = FilterParams.parse(oldFile.readText()).getOrThrow().copy(name = name)
        write(File(dir, "$name.json"), p)
        if (oldFile.name != "$name.json") oldFile.delete()
        p
    }

    fun delete(name: String): Result<Unit> = runCatching {
        val f = userFile(name) ?: throw IllegalArgumentException("Built-in filters can't be deleted")
        if (!f.delete()) throw IOException("Could not delete the filter")
    }

    fun writeExport(params: FilterParams, outDir: File): File {
        outDir.mkdirs()
        val f = File(outDir, params.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "_") + ".json")
        f.writeText(params.toJson())
        return f
    }

    private fun userFiles(): List<File> = dir.listFiles { f -> !f.name.startsWith(".") && f.extension == "json" }
        .orEmpty().sortedBy { it.name.lowercase() }

    private fun users(): List<FilterParams> = userFiles().mapNotNull { FilterParams.parse(it.readText()).getOrNull() }
        .sortedBy { it.name.lowercase() }

    private fun userFile(name: String): File? = userFiles().firstOrNull { f ->
        FilterParams.parse(f.readText()).getOrNull()?.name.equals(name, ignoreCase = true)
    }

    private fun write(file: File, p: FilterParams) {
        val tmp = File(dir, ".tmp-${System.nanoTime()}")
        tmp.writeText(p.toJson())
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete(); throw e
        }
    }

    private companion object {
        const val MAX_NAME = 24
        const val MAX_IMPORT_CHARS = 200_000
        val NAME_RE = Regex("^[\\p{L}\\p{N} _-]+$")
    }
}

fun pickSelection(entries: List<FilterStore.Entry>, current: String?): FilterParams =
    entries.firstOrNull { it.params.name == current }?.params ?: entries.first().params
```
Note: `userFile` matches on the name stored inside the JSON, so a user file named `Extra.json` whose JSON name is `Extra` is found by `"Extra"`.
- [ ] **Step 4: `BundledFilters.kt`**

```kotlin
package com.filtercam.filter

import android.content.Context

fun loadBundledFilters(context: Context): List<FilterParams> =
    (context.assets.list("filters") ?: emptyArray())
        .filter { it.endsWith(".json") }.sorted()
        .map { f -> FilterParams.fromJson(context.assets.open("filters/$f").bufferedReader().use { it.readText() }) }
```
- [ ] **Step 5: Run** `.\gradlew.bat testDebugUnitTest` — Expected: all tests PASS (FilterParamsTest 8, FilterStoreTest 9). If the "Extra.json" sort-order assertion fails, adjust only the expected list order to match `sortedBy { it.name.lowercase() }` (`Extra` before `Good`).
- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: FilterStore with import, rename, delete, export"` (+ trailer).

---

### Task 4: SettingsRepository (TDD)

**Files:**
- Create: `app/src/main/java/com/filtercam/settings/SettingsRepository.kt`
- Test: `app/src/test/java/com/filtercam/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `enum class FlashSetting { OFF, ON, AUTO }`; `interface KeyValueStore { getBoolean(k: String, def: Boolean): Boolean; putBoolean(k, v); getInt(k, def: Int): Int; putInt(k, v); getString(k, def: String?): String?; putString(k, v: String?) }`; `class SharedPrefsStore(prefs: SharedPreferences) : KeyValueStore`; `class SettingsRepository(kv: KeyValueStore)` with `var gridDefault: Boolean` (false), `var flashDefault: FlashSetting` (OFF), `var jpegQuality: Int` (95, clamped 70..100), `var mirrorFront: Boolean` (false), `var lastFilter: String?` (null).

- [ ] **Step 1: Failing test**

```kotlin
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
```
- [ ] **Step 2: Run** `.\gradlew.bat testDebugUnitTest` — Expected: FAIL (compilation).
- [ ] **Step 3: Implement**

```kotlin
package com.filtercam.settings

import android.content.SharedPreferences

enum class FlashSetting { OFF, ON, AUTO }

interface KeyValueStore {
    fun getBoolean(k: String, def: Boolean): Boolean
    fun putBoolean(k: String, v: Boolean)
    fun getInt(k: String, def: Int): Int
    fun putInt(k: String, v: Int)
    fun getString(k: String, def: String?): String?
    fun putString(k: String, v: String?)
}

class SharedPrefsStore(private val p: SharedPreferences) : KeyValueStore {
    override fun getBoolean(k: String, def: Boolean) = p.getBoolean(k, def)
    override fun putBoolean(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    override fun getInt(k: String, def: Int) = p.getInt(k, def)
    override fun putInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    override fun getString(k: String, def: String?) = p.getString(k, def)
    override fun putString(k: String, v: String?) = p.edit().putString(k, v).apply()
}

class SettingsRepository(private val kv: KeyValueStore) {
    var gridDefault: Boolean
        get() = kv.getBoolean("grid", false); set(v) = kv.putBoolean("grid", v)
    var flashDefault: FlashSetting
        get() = runCatching { FlashSetting.valueOf(kv.getString("flash", "OFF") ?: "OFF") }.getOrDefault(FlashSetting.OFF)
        set(v) = kv.putString("flash", v.name)
    var jpegQuality: Int
        get() = kv.getInt("quality", 95).coerceIn(70, 100); set(v) = kv.putInt("quality", v.coerceIn(70, 100))
    var mirrorFront: Boolean
        get() = kv.getBoolean("mirror", false); set(v) = kv.putBoolean("mirror", v)
    var lastFilter: String?
        get() = kv.getString("lastFilter", null); set(v) = kv.putString("lastFilter", v)
}
```
- [ ] **Step 4: Run** `.\gradlew.bat testDebugUnitTest` — Expected: all PASS.
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: SettingsRepository"` (+ trailer).

---

### Task 5: CameraController (flash/zoom/focus/unbind/mirror) and PhotoSaver quality

**Files:**
- Modify: `app/src/main/java/com/filtercam/camera/CameraController.kt`, `app/src/main/java/com/filtercam/storage/PhotoSaver.kt`

**Interfaces:**
- Consumes: `FlashSetting` (Task 4).
- Produces: `CameraController.setFlash(mode: FlashSetting)`, `zoomBy(factor: Float)`, `focusAt(display: Display?, x: Float, y: Float, w: Float, h: Float)`, `unbind()`, `capture(mirror: Boolean = false, onResult: (Result<Bitmap>) -> Unit)` (existing callers using a trailing lambda keep compiling; `onResult` is still on a background thread), `PhotoSaver.save(context, bitmap, quality: Int = 95): Uri`.

- [ ] **Step 1: `CameraController.kt` edits.** Add imports: `android.view.Display`, `androidx.camera.core.Camera`, `androidx.camera.core.DisplayOrientedMeteringPointFactory`, `androidx.camera.core.FocusMeteringAction`, `com.filtercam.settings.FlashSetting`. Add a field `@Volatile private var camera: Camera? = null` and change the `bind` body so `camera = cp.bindToLifecycle(...)`. Add these members:

```kotlin
    fun unbind() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ camera = null; future.get().unbindAll() }, ContextCompat.getMainExecutor(context))
    }

    fun setFlash(mode: FlashSetting) {
        imageCapture.flashMode = when (mode) {
            FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
            FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    /** Multiplies the current zoom ratio by [factor], clamped to the camera's range. Main thread. */
    fun zoomBy(factor: Float) {
        val c = camera ?: return
        val z = c.cameraInfo.zoomState.value ?: return
        c.cameraControl.setZoomRatio((z.zoomRatio * factor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    /** x,y are view pixels inside a view of size w x h that shows the whole preview. */
    fun focusAt(display: Display?, x: Float, y: Float, w: Float, h: Float) {
        val c = camera ?: return
        val d = display ?: return
        val point = DisplayOrientedMeteringPointFactory(d, c.cameraInfo, w, h).createPoint(x, y)
        c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }
```
and change `capture` to `fun capture(mirror: Boolean = false, onResult: (Result<Bitmap>) -> Unit)`; in the `Matrix().apply { ... }` line add `if (mirror) postScale(-1f, 1f)` after `postRotate(...)`.
- [ ] **Step 2: `PhotoSaver.kt`:** change the signature to `fun save(context: Context, bitmap: Bitmap, quality: Int = 95): Uri` and replace the `95` in `bitmap.compress(Bitmap.CompressFormat.JPEG, 95, ...)` with `quality.coerceIn(70, 100)`.
- [ ] **Step 3: Build + tests** `.\gradlew.bat assembleDebug testDebugUnitTest` — Expected: BUILD SUCCESSFUL (old `CameraScreen` still compiles because the new params have defaults).
- [ ] **Step 4: Commit** `git add -A && git commit -m "feat: camera flash, zoom, focus, mirror, unbind; JPEG quality"` (+ trailer).

---

### Task 6: FilterViewModel rewrite

**Files:**
- Modify: `app/src/main/java/com/filtercam/ui/FilterViewModel.kt` (full replace), `app/src/main/java/com/filtercam/ui/CameraScreen.kt` (preset strip block only)
- Delete: `app/src/main/java/com/filtercam/filter/FilterRepository.kt`

**Interfaces:**
- Consumes: `FilterStore`, `pickSelection`, `loadBundledFilters` (Task 3), `SettingsRepository`/`SharedPrefsStore` (Task 4).
- Produces: `class FilterViewModel(app: Application) : AndroidViewModel` with `val store: FilterStore`, `val settings: SettingsRepository`, `val entries: StateFlow<List<FilterStore.Entry>>`, `val params: StateFlow<FilterParams>`, `val strength: StateFlow<Float>`, `val snackbar: SnackbarHostState`; `select(name: String)`, `setStrength(v)`, `setParam(k, v)`, `nameError(name: String): String?`, `nameExists(name: String): Boolean`, `saveCurrentAs(name: String)`, `importFilter(text: String)`, `renameFilter(old: String, new: String)`, `deleteFilter(name: String)`, `exportFile(params: FilterParams): File`, `message(text: String)`.

- [ ] **Step 1: Replace `FilterViewModel.kt`**

```kotlin
package com.filtercam.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filtercam.filter.FilterParams
import com.filtercam.filter.FilterStore
import com.filtercam.filter.ParamKey
import com.filtercam.filter.loadBundledFilters
import com.filtercam.filter.pickSelection
import com.filtercam.settings.SettingsRepository
import com.filtercam.settings.SharedPrefsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class FilterViewModel(app: Application) : AndroidViewModel(app) {
    val store = FilterStore(File(app.filesDir, "filters"), loadBundledFilters(app))
    val settings = SettingsRepository(SharedPrefsStore(app.getSharedPreferences("settings", Context.MODE_PRIVATE)))
    val snackbar = SnackbarHostState()

    private val _entries = MutableStateFlow(store.all())
    private val _params = MutableStateFlow(pickSelection(_entries.value, settings.lastFilter))
    private val _strength = MutableStateFlow(1f)
    val entries: StateFlow<List<FilterStore.Entry>> = _entries
    val params: StateFlow<FilterParams> = _params
    val strength: StateFlow<Float> = _strength

    fun message(text: String) { viewModelScope.launch { snackbar.showSnackbar(text) } }
    private fun refresh() { _entries.value = store.all() }

    fun select(name: String) {
        _entries.value.firstOrNull { it.params.name == name }?.let {
            _params.value = it.params; settings.lastFilter = name
        }
    }
    fun setStrength(v: Float) { _strength.value = v.coerceIn(0f, 1f) }
    fun setParam(k: ParamKey, v: Float) { _params.value = _params.value.with(k, v.coerceIn(k.range)).clamped() }

    fun nameError(name: String): String? = store.validateName(name).exceptionOrNull()?.message
    fun nameExists(name: String): Boolean = store.nameExistsAsUser(name)

    fun saveCurrentAs(name: String) {
        store.save(_params.value.copy(name = name), overwrite = true)
            .onSuccess { refresh(); select(it.name); message("Saved filter \"${it.name}\"") }
            .onFailure { message(it.message ?: "Could not save the filter") }
    }

    fun importFilter(text: String) {
        store.importJson(text)
            .onSuccess { refresh(); select(it.name); message("Imported \"${it.name}\"") }
            .onFailure { message(it.message ?: "Could not import the filter") }
    }

    fun renameFilter(old: String, new: String) {
        store.rename(old, new)
            .onSuccess { refresh(); if (_params.value.name == old) select(it.name); message("Renamed to \"${it.name}\"") }
            .onFailure { message(it.message ?: "Could not rename the filter") }
    }

    fun deleteFilter(name: String) {
        store.delete(name)
            .onSuccess {
                refresh()
                if (_params.value.name == name) { _params.value = pickSelection(_entries.value, null); settings.lastFilter = _params.value.name }
                message("Deleted \"$name\"")
            }
            .onFailure { message(it.message ?: "Could not delete the filter") }
    }

    fun exportFile(params: FilterParams): File =
        store.writeExport(params, File(getApplication<Application>().cacheDir, "exports"))
}
```
- [ ] **Step 2: Fix the old `ui/CameraScreen.kt` preset strip** (it is replaced entirely in Task 8; this keeps the build green). Replace the `LazyRow(...) { itemsIndexed(vm.presets) { i, p -> ... } }` block with:

```kotlin
        val entries by vm.entries.collectAsState()
        LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(entries) { _, e ->
                Text(e.params.name, color = if (e.params.name == params.name) Color.White else Color.Gray,
                    modifier = Modifier.clickable { vm.select(e.params.name) })
            }
        }
```
- [ ] **Step 3: Delete `filter/FilterRepository.kt`.**
- [ ] **Step 4: Build + tests** `.\gradlew.bat assembleDebug testDebugUnitTest` — Expected: BUILD SUCCESSFUL, all unit tests PASS.
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: FilterViewModel backed by FilterStore and settings"` (+ trailer).

---

### Task 7: Filter sheets (Tune, Manage, name dialog, share)

**Files:**
- Create: `app/src/main/java/com/filtercam/ui/filters/FilterSheets.kt`, `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/java/com/filtercam/ui/DevPanel.kt`, `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `FilterViewModel` (Task 6), `Palette` (Task 1).
- Produces: `@Composable TuneSheet(vm: FilterViewModel, onDismiss: () -> Unit)`, `@Composable ManageFiltersSheet(vm: FilterViewModel, onDismiss: () -> Unit)`, `@Composable NameDialog(title, initial, confirmLabel, validate: (String) -> String?, exists: (String) -> Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit)`, `fun shareFile(ctx: Context, file: File, mime: String)`.

- [ ] **Step 1: FileProvider.** `res/xml/file_paths.xml`:

```xml
<paths><cache-path name="exports" path="exports/"/></paths>
```
In the manifest inside `<application>`:
```xml
        <provider android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider" android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths"/>
        </provider>
```
- [ ] **Step 2: `DevPanel.kt`** — change `heightIn(max = 240.dp)` to `heightIn(max = 420.dp)` and the two `color = Color.White` to `color = MaterialTheme.colorScheme.onSurface` (add `import androidx.compose.material3.MaterialTheme`).
- [ ] **Step 3: `FilterSheets.kt`**

```kotlin
package com.filtercam.ui.filters

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.filtercam.filter.FilterParams
import com.filtercam.ui.DevPanel
import com.filtercam.ui.FilterViewModel
import java.io.File

fun shareFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share filter").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

@Composable
fun NameDialog(
    title: String, initial: String, confirmLabel: String,
    validate: (String) -> String?, exists: (String) -> Boolean,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val error = if (text.isBlank()) null else validate(text)
    val replacing = error == null && text.isNotBlank() && exists(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    isError = error != null, supportingText = { Text(error ?: if (replacing) "This replaces your existing filter" else "") },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && error == null, onClick = { onConfirm(text.trim()) }) {
                Text(if (replacing) "Replace" else confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneSheet(vm: FilterViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val params by vm.params.collectAsState()
    var showSave by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Box {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Tune: ${params.name}", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showSave = true }) { Text("Save as new filter") }
                    OutlinedButton(onClick = { shareFile(ctx, vm.exportFile(params), "application/json") }) {
                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Export")
                    }
                }
                DevPanel(params, vm::setParam)
            }
            SnackbarHost(vm.snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (showSave) {
        NameDialog(
            title = "Save as new filter", initial = "", confirmLabel = "Save",
            validate = vm::nameError, exists = vm::nameExists,
            onConfirm = { vm.saveCurrentAs(it); showSave = false }, onDismiss = { showSave = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFiltersSheet(vm: FilterViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val entries by vm.entries.collectAsState()
    var renaming by remember { mutableStateOf<FilterParams?>(null) }
    var deleting by remember { mutableStateOf<FilterParams?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { r ->
                    val buf = CharArray(200_001); val n = r.read(buf)
                    if (n <= 0) "" else String(buf, 0, n)
                }
            }.getOrNull()
            if (text == null) vm.message("Could not read that file") else vm.importFilter(text)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Box {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Filters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Import filter")
                    }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.params.name }) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(e.params.name + if (e.builtIn) "  (built-in)" else "", modifier = Modifier.weight(1f))
                            if (!e.builtIn) {
                                IconButton(onClick = { shareFile(ctx, vm.exportFile(e.params), "application/json") }) { Icon(Icons.Default.Share, "Export") }
                                IconButton(onClick = { renaming = e.params }) { Icon(Icons.Default.Edit, "Rename") }
                                IconButton(onClick = { deleting = e.params }) { Icon(Icons.Default.Delete, "Delete") }
                            }
                        }
                    }
                }
            }
            SnackbarHost(vm.snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    renaming?.let { p ->
        NameDialog("Rename filter", p.name, "Rename", validate = vm::nameError, exists = { it != p.name && vm.nameExists(it) },
            onConfirm = { vm.renameFilter(p.name, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete \"${p.name}\"?") },
            text = { Text("This removes the filter from this phone. Photos already taken are not affected.") },
            confirmButton = { TextButton(onClick = { vm.deleteFilter(p.name); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
```
Note: renaming to a name that already exists as another user filter shows "Replace" in the dialog but `renameFilter` will fail with the store's "already exists" message (rename never overwrites). To avoid the misleading label, the `exists` lambda for rename returns `false` always: replace it with `exists = { false }`.
- [ ] **Step 4: Build** `.\gradlew.bat assembleDebug testDebugUnitTest` — Expected: BUILD SUCCESSFUL (sheets are not yet wired into a screen; they compile standalone).
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: tune, manage-filters sheets, name dialog, share"` (+ trailer).

---

### Task 8: Camera screen redesign

**Files:**
- Create: `app/src/main/java/com/filtercam/ui/camera/CameraScreen.kt`, `app/src/main/java/com/filtercam/ui/camera/FilterStrip.kt`, `app/src/main/java/com/filtercam/ui/camera/CameraControls.kt`
- Delete: `app/src/main/java/com/filtercam/ui/CameraScreen.kt`
- Modify: `app/src/main/java/com/filtercam/MainActivity.kt` (import + call site only)

**Interfaces:**
- Consumes: `FilterViewModel`, `CameraController` (Task 5), `TuneSheet`/`ManageFiltersSheet` (Task 7), `GlFilterRenderer`, `PhotoProcessor`, `PhotoSaver`, `FlashSetting`, `Palette`.
- Produces: `@Composable fun CameraScreen(vm: FilterViewModel, lastPhoto: ImageBitmap?, onOpenGallery: () -> Unit, onOpenSettings: () -> Unit, onPhotoSaved: () -> Unit)`.

- [ ] **Step 1: `FilterStrip.kt`**

```kotlin
package com.filtercam.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filtercam.filter.FilterParams
import com.filtercam.filter.FilterStore
import com.filtercam.ui.theme.Palette

fun swatchColor(p: FilterParams): Color {
    fun ch(i: Int) = (p.shadowColor[i] * 0.6f + p.highlightColor[i] * 0.4f + 0.15f).coerceIn(0f, 1f)
    return Color(ch(0), ch(1), ch(2))
}

@Composable
fun FilterStrip(
    entries: List<FilterStore.Entry>, selected: String,
    onSelect: (String) -> Unit, onTune: () -> Unit, onManage: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onTune) { Icon(Icons.Default.Tune, "Tune filter", tint = Color.White) }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.params.name }) { e ->
                val sel = e.params.name == selected
                val shape = RoundedCornerShape(20.dp)
                Row(
                    Modifier.clip(shape).background(if (sel) Palette.PanelSelected else Palette.Panel)
                        .border(1.dp, if (sel) Palette.Accent else Color.Transparent, shape)
                        .clickable { onSelect(e.params.name) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(swatchColor(e.params)))
                    Spacer(Modifier.width(6.dp))
                    Text(e.params.name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        IconButton(onClick = onManage) { Icon(Icons.Default.Add, "Manage filters", tint = Color.White) }
    }
}
```
- [ ] **Step 2: `CameraControls.kt`**

```kotlin
package com.filtercam.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.filtercam.settings.FlashSetting
import com.filtercam.ui.theme.Palette

@Composable
fun TopBar(flash: FlashSetting, showFlash: Boolean, grid: Boolean, onFlash: () -> Unit, onGrid: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showFlash) {
            IconButton(onClick = onFlash) {
                Icon(when (flash) { FlashSetting.OFF -> Icons.Default.FlashOff; FlashSetting.ON -> Icons.Default.FlashOn; FlashSetting.AUTO -> Icons.Default.FlashAuto },
                    "Flash: ${flash.name.lowercase()}", tint = if (flash == FlashSetting.OFF) Color.White else Palette.Accent)
            }
        }
        IconButton(onClick = onGrid) {
            Icon(if (grid) Icons.Default.GridOn else Icons.Default.GridOff, "Grid", tint = if (grid) Palette.Accent else Color.White)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
    }
}

@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
            drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
        }
    }
}

@Composable
fun FocusRing(at: Offset, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawCircle(Palette.Accent, radius = 36.dp.toPx(), center = at, style = Stroke(2.dp.toPx())) }
}

@Composable
fun ShutterRow(lastPhoto: ImageBitmap?, busy: Boolean, onGallery: () -> Unit, onShutter: () -> Unit, onFlip: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Panel).clickable(onClick = onGallery),
            contentAlignment = Alignment.Center) {
            if (lastPhoto != null) Image(lastPhoto, "Open gallery", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.PhotoLibrary, "Open gallery", tint = Color.White)
        }
        Box(Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
            .background(if (busy) Color.Gray else Color.White).clickable(enabled = !busy, onClick = onShutter))
        IconButton(onClick = onFlip, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.FlipCameraAndroid, "Flip camera", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}
```
- [ ] **Step 2b: Delete the old `ui/CameraScreen.kt`** (its `worker` executor and `toast` helper move into the new file; the old `toast` Toast helper is replaced by snackbars).
- [ ] **Step 3: `ui/camera/CameraScreen.kt`**

```kotlin
package com.filtercam.ui.camera

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.filtercam.camera.CameraController
import com.filtercam.gl.GlFilterRenderer
import com.filtercam.gl.PhotoProcessor
import com.filtercam.settings.FlashSetting
import com.filtercam.storage.PhotoSaver
import com.filtercam.ui.FilterViewModel
import com.filtercam.ui.filters.ManageFiltersSheet
import com.filtercam.ui.filters.TuneSheet
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private val worker = Executors.newSingleThreadExecutor()

@Composable
fun CameraScreen(
    vm: FilterViewModel, lastPhoto: ImageBitmap?,
    onOpenGallery: () -> Unit, onOpenSettings: () -> Unit, onPhotoSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val params by vm.params.collectAsState()
    val strength by vm.strength.collectAsState()
    val entries by vm.entries.collectAsState()
    var front by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(vm.settings.flashDefault) }
    var grid by remember { mutableStateOf(vm.settings.gridDefault) }
    var showStrength by remember { mutableStateOf(false) }
    var showTune by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember { GlFilterRenderer(ctx) }
    val controller = remember { CameraController(ctx) }
    val main = remember { Handler(Looper.getMainLooper()) }

    renderer.params = params
    renderer.strength = strength
    SideEffect {
        renderer.onSurfaceRecreated = { controller.bind(owner, renderer.surfaceProvider, front) }
        controller.setFlash(if (front) FlashSetting.OFF else flash)
    }
    LaunchedEffect(front) { controller.bind(owner, renderer.surfaceProvider, front) }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(900); focusPoint = null } }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.unbind() }
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val p = params; val s = strength
        val quality = vm.settings.jpegQuality; val mirror = front && vm.settings.mirrorFront
        controller.capture(mirror) { result ->
            result.onSuccess { bmp ->
                worker.execute {
                    var out: Bitmap? = null
                    try {
                        runCatching { out = PhotoProcessor().process(bmp, p, s); PhotoSaver.save(ctx, out!!, quality) }
                            .onSuccess { main.post { vm.message("Saved to Pictures/FilterCam"); onPhotoSaved() } }
                            .onFailure { main.post { vm.message("Save failed: ${it.message}") } }
                    } finally {
                        out?.recycle(); bmp.recycle(); main.post { busy = false }
                    }
                }
            }.onFailure { main.post { vm.message("Capture failed: ${it.message}"); busy = false } }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopBar(flash, showFlash = !front, grid = grid,
            onFlash = { flash = FlashSetting.values()[(flash.ordinal + 1) % 3] },
            onGrid = { grid = !grid }, onSettings = onOpenSettings)
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            controller.focusAt(view.display, o.x, o.y, size.width.toFloat(), size.height.toFloat())
                            focusPoint = o
                        }
                    }
                    .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> controller.zoomBy(zoom) } },
                factory = { c ->
                    GLSurfaceView(c).apply {
                        glView = this
                        setEGLContextClientVersion(3)
                        renderer.glView = this
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    }
                },
                update = { it.requestRender() },
            )
            if (grid) GridOverlay(Modifier.fillMaxSize())
            focusPoint?.let { FocusRing(it, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.weight(1f))
        if (showStrength) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Strength ${(strength * 100).toInt()}%", color = Color.White)
                Slider(strength, vm::setStrength, Modifier.weight(1f).padding(start = 12.dp))
            }
        }
        FilterStrip(entries, params.name,
            onSelect = { name -> if (name == params.name) showStrength = !showStrength else { vm.select(name); showStrength = false } },
            onTune = { showTune = true }, onManage = { showManage = true })
        ShutterRow(lastPhoto, busy, onGallery = onOpenGallery, onShutter = ::takePhoto, onFlip = { front = !front })
    }
    if (showTune) TuneSheet(vm) { showTune = false }
    if (showManage) ManageFiltersSheet(vm) { showManage = false }
}
```
Note: `vm.message` is called from the main thread via `main.post`. The `front` flip also resets focus/zoom because `bind` creates a fresh camera.
- [ ] **Step 4: Temporary call site in `MainActivity.kt`** (replaced in Task 10): change the import to `com.filtercam.ui.camera.CameraScreen` and the call to `CameraScreen(viewModel<FilterViewModel>(), lastPhoto = null, onOpenGallery = {}, onOpenSettings = {}, onPhotoSaved = {})`, wrapped in `Box { ... }` with `com.filtercam.ui.theme.FilterCamTheme { ... }` around it and a `SnackbarHost(vm.snackbar)` inside a `Box(Modifier.fillMaxSize())` aligned bottom (so messages are visible): 

```kotlin
val vm = viewModel<FilterViewModel>()
FilterCamTheme {
    Box(Modifier.fillMaxSize()) {
        CameraScreen(vm, lastPhoto = null, onOpenGallery = {}, onOpenSettings = {}, onPhotoSaved = {})
        SnackbarHost(vm.snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
```
- [ ] **Step 5: Build + tests** `.\gradlew.bat assembleDebug testDebugUnitTest` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: On the phone** `.\gradlew.bat installDebug`, then screenshot (`adb exec-out screencap -p`) and confirm: dark UI, top bar icons, viewfinder with grid toggle, filter strip with Jade chip, big shutter, flip works, Tune sheet opens with Save/Export, Manage sheet opens with Import. Record what is wrong in the report; do not hand-edit around layout problems beyond obvious fixes.
- [ ] **Step 7: Commit** `git add -A && git commit -m "feat: redesigned camera screen"` (+ trailer).

---

### Task 9: Gallery and photo viewer

**Files:**
- Create: `app/src/main/java/com/filtercam/gallery/GalleryRepository.kt`, `app/src/main/java/com/filtercam/ui/gallery/GalleryScreen.kt`

**Interfaces:**
- Consumes: `Palette`.
- Produces: `data class Photo(val id: Long, val uri: Uri)`; `class GalleryRepository(context)` with `list(): List<Photo>` (newest first), `thumbnail(uri: Uri, w: Int, h: Int): ImageBitmap?`, `latestThumbnail(): ImageBitmap?`, `delete(photo: Photo): Boolean`; `@Composable GalleryScreen(photos: List<Photo>, repo: GalleryRepository, onBack: () -> Unit, onOpen: (Int) -> Unit)`; `@Composable PhotoViewer(photos: List<Photo>, startIndex: Int, repo: GalleryRepository, onBack: () -> Unit, onDelete: (Photo) -> Unit)`.

- [ ] **Step 1: `GalleryRepository.kt`**

```kotlin
package com.filtercam.gallery

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class Photo(val id: Long, val uri: Uri)

class GalleryRepository(private val context: Context) {
    fun list(): List<Photo> {
        val out = ArrayList<Photo>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        runCatching {
            context.contentResolver.query(
                base, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/FilterCam%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
            )?.use { c ->
                while (c.moveToNext()) { val id = c.getLong(0); out += Photo(id, ContentUris.withAppendedId(base, id)) }
            }
        }
        return out
    }

    fun thumbnail(uri: Uri, w: Int, h: Int): ImageBitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(w, h), null).asImageBitmap() }.getOrNull()

    fun latestThumbnail(): ImageBitmap? = list().firstOrNull()?.let { thumbnail(it.uri, 160, 160) }

    fun delete(photo: Photo): Boolean =
        runCatching { context.contentResolver.delete(photo.uri, null, null) > 0 }.getOrDefault(false)
}
```
- [ ] **Step 2: `GalleryScreen.kt`**

```kotlin
package com.filtercam.ui.gallery

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filtercam.gallery.GalleryRepository
import com.filtercam.gallery.Photo
import com.filtercam.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun Thumb(photo: Photo, repo: GalleryRepository, w: Int, h: Int, modifier: Modifier, scale: ContentScale) {
    val bmp by produceState<ImageBitmap?>(null, photo.uri) { value = withContext(Dispatchers.IO) { repo.thumbnail(photo.uri, w, h) } }
    Box(modifier.background(Palette.Panel), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) } ?: Text("…", color = Color.Gray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(photos: List<Photo>, repo: GalleryRepository, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(title = { Text("Gallery") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White, navigationIconContentColor = Color.White))
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No photos yet. Take one!", color = Color.Gray) }
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
                    Thumb(p, repo, 320, 320, Modifier.aspectRatio(1f).clickable { onOpen(i) }, ContentScale.Crop)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewer(photos: List<Photo>, startIndex: Int, repo: GalleryRepository, onBack: () -> Unit, onDelete: (Photo) -> Unit) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    var confirm by remember { mutableStateOf(false) }
    val current = photos.getOrNull(pager.currentPage)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(title = { Text("${pager.currentPage + 1} / ${photos.size}") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = {
                    current?.let {
                        val send = Intent(Intent.ACTION_SEND).apply { type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, it.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        ctx.startActivity(Intent.createChooser(send, "Share photo"))
                    }
                }) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White,
                navigationIconContentColor = Color.White, actionIconContentColor = Color.White))
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            photos.getOrNull(page)?.let { Thumb(it, repo, 1600, 2133, Modifier.fillMaxSize(), ContentScale.Fit) }
        }
    }
    if (confirm && current != null) {
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete this photo?") },
            text = { Text("It will be removed from your phone.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete(current) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    }
}
```
- [ ] **Step 3: Build** `.\gradlew.bat assembleDebug testDebugUnitTest` — Expected: BUILD SUCCESSFUL (screens wired in Task 10; `HorizontalPager` needs foundation 1.5+, included in the BOM; if `rememberPagerState { }` lambda form fails to compile, use `pageCount = { photos.size }`).
- [ ] **Step 4: Commit** `git add -A && git commit -m "feat: gallery grid and photo viewer"` (+ trailer).

---

### Task 10: Settings, permission screen, AppRoot navigation

**Files:**
- Create: `app/src/main/java/com/filtercam/ui/settings/SettingsScreen.kt`, `app/src/main/java/com/filtercam/ui/settings/PermissionScreen.kt`, `app/src/main/java/com/filtercam/ui/AppRoot.kt`
- Modify: `app/src/main/java/com/filtercam/MainActivity.kt` (full replace)

**Interfaces:**
- Consumes: everything above.
- Produces: `sealed interface Screen { data object Camera; data object Gallery; data class Viewer(val index: Int); data object Settings }`; `@Composable AppRoot(vm: FilterViewModel)`; `@Composable SettingsScreen(vm: FilterViewModel, onBack: () -> Unit)`; `@Composable PermissionScreen(permanentlyDenied: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit)`.

- [ ] **Step 1: `PermissionScreen.kt`**

```kotlin
package com.filtercam.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun PermissionScreen(permanentlyDenied: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("FilterCam needs the camera", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            if (permanentlyDenied) "Camera access is turned off. Open the app settings and allow Camera to use FilterCam."
            else "Allow camera access to see the live filtered preview and take photos.",
            color = Color.LightGray, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (permanentlyDenied) Button(onClick = onOpenSettings) { Text("Open settings") }
        else Button(onClick = onRetry) { Text("Allow camera") }
    }
}
```
- [ ] **Step 2: `SettingsScreen.kt`**

```kotlin
package com.filtercam.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filtercam.settings.FlashSetting
import com.filtercam.ui.FilterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: FilterViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current
    var grid by remember { mutableStateOf(s.gridDefault) }
    var flash by remember { mutableStateOf(s.flashDefault) }
    var quality by remember { mutableStateOf(s.jpegQuality.toFloat()) }
    var mirror by remember { mutableStateOf(s.mirrorFront) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White, navigationIconContentColor = Color.White))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SwitchRow("Grid lines on startup", grid) { grid = it; s.gridDefault = it }
            Text("Default flash", color = Color.White, modifier = Modifier.padding(top = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlashSetting.values().forEach { f ->
                    FilterChip(selected = flash == f, onClick = { flash = f; s.flashDefault = f }, label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            Text("Photo quality: ${quality.toInt()}%", color = Color.White, modifier = Modifier.padding(top = 16.dp))
            Slider(quality, { quality = it }, valueRange = 70f..100f, onValueChangeFinished = { s.jpegQuality = quality.toInt() })
            SwitchRow("Mirror front-camera photos", mirror) { mirror = it; s.mirrorFront = it }
            Text("When on, selfies are saved mirrored like the preview. When off, they match the stock camera.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Text("About", color = Color.White, modifier = Modifier.padding(top = 24.dp))
            Text("FilterCam $version. Photos are saved to Pictures/FilterCam.", color = Color.Gray)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
```
(Remove the unused `PackageManager` import if the compiler warns.)
- [ ] **Step 3: `AppRoot.kt`**

```kotlin
package com.filtercam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.filtercam.gallery.GalleryRepository
import com.filtercam.gallery.Photo
import com.filtercam.ui.camera.CameraScreen
import com.filtercam.ui.gallery.GalleryScreen
import com.filtercam.ui.gallery.PhotoViewer
import com.filtercam.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Camera : Screen
    data object Gallery : Screen
    data class Viewer(val index: Int) : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(vm: FilterViewModel) {
    val ctx = LocalContext.current
    val repo = remember { GalleryRepository(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    var version by remember { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf(emptyList<Photo>()) }
    LaunchedEffect(version) { photos = withContext(Dispatchers.IO) { repo.list() } }
    val lastPhoto by produceState<ImageBitmap?>(null, photos) { value = withContext(Dispatchers.IO) { photos.firstOrNull()?.let { repo.thumbnail(it.uri, 160, 160) } } }

    BackHandler(screen != Screen.Camera) {
        screen = if (screen is Screen.Viewer) Screen.Gallery else Screen.Camera
    }
    Scaffold(containerColor = Color.Black, snackbarHost = { SnackbarHost(vm.snackbar) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val s = screen) {
                Screen.Camera -> CameraScreen(vm, lastPhoto,
                    onOpenGallery = { version++; screen = Screen.Gallery },
                    onOpenSettings = { screen = Screen.Settings },
                    onPhotoSaved = { version++ })
                Screen.Gallery -> GalleryScreen(photos, repo, onBack = { screen = Screen.Camera }, onOpen = { screen = Screen.Viewer(it) })
                is Screen.Viewer -> PhotoViewer(photos, s.index, repo, onBack = { screen = Screen.Gallery }, onDelete = { p ->
                    if (repo.delete(p)) {
                        val left = photos - p
                        photos = left
                        version++
                        screen = if (left.isEmpty()) Screen.Gallery else Screen.Viewer(s.index.coerceAtMost(left.size - 1))
                        vm.message("Photo deleted")
                    } else vm.message("Could not delete the photo")
                })
                Screen.Settings -> SettingsScreen(vm, onBack = { screen = Screen.Camera })
            }
        }
    }
}
```
- [ ] **Step 4: `MainActivity.kt` (full replace)**

```kotlin
package com.filtercam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.filtercam.ui.AppRoot
import com.filtercam.ui.FilterViewModel
import com.filtercam.ui.settings.PermissionScreen
import com.filtercam.ui.theme.FilterCamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FilterCamTheme {
                val ctx = LocalContext.current
                val owner = LocalLifecycleOwner.current
                fun has() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                var granted by remember { mutableStateOf(has()) }
                var asked by remember { mutableStateOf(false) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
                LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
                DisposableEffect(owner) {
                    val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = has() }
                    owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
                }
                if (granted) {
                    AppRoot(viewModel<FilterViewModel>())
                } else if (asked) {
                    val permanent = !ActivityCompat.shouldShowRequestPermissionRationale(ctx as Activity, Manifest.permission.CAMERA)
                    PermissionScreen(permanent, onRetry = { launcher.launch(Manifest.permission.CAMERA) }, onOpenSettings = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    })
                }
            }
        }
    }
}
```
- [ ] **Step 5: Build + tests** `.\gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest` — Expected: BUILD SUCCESSFUL, all unit tests PASS.
- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: app navigation, settings, permission screen, gallery wiring"` (+ trailer).

---

### Task 11: On-device verification and polish (controller + user)

**Files:** fixes only as findings require.

- [ ] **Step 1: Install and open:** `. scripts\env.ps1; .\gradlew.bat installDebug`; `adb shell am start -n com.filtercam/.MainActivity`; screenshot via `adb exec-out screencap -p`.
- [ ] **Step 2: Camera screen check** (screenshot each): top bar icons; flash cycle Off/On/Auto (hidden on front camera); grid toggle; tap-to-focus ring; Jade chip selected with accent border; shutter saves a photo and the left thumbnail shows the new photo; flip camera works.
- [ ] **Step 3: Filters check:** Tune sheet opens, sliders change the live preview; "Save as new filter" with name `Test1` adds a chip and persists after force-stop + relaunch; Manage sheet: rename `Test1`→`Test2`, export opens the share sheet, delete removes it (Jade shows no rename/delete icons). Import: `adb push` a valid filter JSON (`FilterParams.toJson()` output, e.g. an exported file) to `/sdcard/Download/`, pick it via the Import button (user performs the picker taps if UI automation is awkward), and also try importing a non-filter file (e.g. a `.jpg` renamed `.json`): expect the "Not a valid filter file" snackbar.
- [ ] **Step 4: Gallery/Settings/Permission:** gallery grid shows the photo, viewer swipes, share sheet opens, delete with confirmation works, empty state text appears when all deleted; settings switches persist across relaunch (grid on startup, flash default, quality, mirror); revoke Camera permission via `adb shell pm revoke com.filtercam android.permission.CAMERA` and relaunch: permission screen shows ("Allow camera"; after two denials "Open settings").
- [ ] **Step 5: Stability:** 10 rapid flips; Home then return; 5 cold starts: no black preview, no crash (`adb logcat -d | findstr FATAL`).
- [ ] **Step 6:** Report each failure with a screenshot reference; fix via the review loop; commit `git add -A && git commit -m "fix: UI polish from device verification"` (+ trailer) if any fixes were made.
