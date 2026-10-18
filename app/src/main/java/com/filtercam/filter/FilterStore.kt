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
        val target = File(dir, "$name.json")
        write(target, saved)
        if (existing != null && !Files.isSameFile(existing.toPath(), target.toPath())) existing.delete()
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
        val target = File(dir, "$name.json")
        write(target, p)
        if (!Files.isSameFile(oldFile.toPath(), target.toPath())) oldFile.delete()
        p
    }

    fun delete(name: String): Result<Unit> = runCatching {
        val f = userFile(name) ?: throw IllegalArgumentException("Built-in filters can't be deleted")
        if (!f.delete()) throw IOException("Could not delete the filter")
    }

    fun writeExport(params: FilterParams, outDir: File): File {
        outDir.mkdirs()
        val f = File(outDir, params.name.replace(Regex("""[^\p{L}\p{N} _-]"""), "_") + ".json")
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
        val NAME_RE = Regex("""^[\p{L}\p{N} _-]+$""")
    }
}

fun pickSelection(entries: List<FilterStore.Entry>, current: String?): FilterParams =
    entries.firstOrNull { it.params.name == current }?.params ?: entries.first().params
