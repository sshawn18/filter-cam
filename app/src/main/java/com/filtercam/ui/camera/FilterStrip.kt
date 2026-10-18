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
