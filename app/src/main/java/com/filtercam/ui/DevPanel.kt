package com.filtercam.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filtercam.filter.FilterParams
import com.filtercam.filter.ParamKey

@Composable
fun DevPanel(params: FilterParams, onChange: (ParamKey, Float) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        ParamKey.values().forEach { k ->
            Row {
                Text("${k.label}  %.2f".format(params.get(k)), color = Color.White)
            }
            Slider(value = params.get(k), onValueChange = { onChange(k, it) }, valueRange = k.range)
        }
    }
}
