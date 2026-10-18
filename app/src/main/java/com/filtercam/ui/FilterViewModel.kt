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
