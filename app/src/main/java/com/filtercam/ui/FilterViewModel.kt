package com.filtercam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.filtercam.filter.FilterParams
import com.filtercam.filter.FilterRepository
import com.filtercam.filter.ParamKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FilterViewModel(app: Application) : AndroidViewModel(app) {
    val presets: List<FilterParams> = FilterRepository(app).loadAll()
    private val _params = MutableStateFlow(presets.first())
    private val _strength = MutableStateFlow(1f)
    val params: StateFlow<FilterParams> = _params
    val strength: StateFlow<Float> = _strength

    fun select(i: Int) { _params.value = presets[i] }
    fun setStrength(v: Float) { _strength.value = v.coerceIn(0f, 1f) }
    fun setParam(k: ParamKey, v: Float) { _params.value = _params.value.with(k, v.coerceIn(k.range)).clamped() }
}
