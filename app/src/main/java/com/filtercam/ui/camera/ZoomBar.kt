package com.filtercam.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filtercam.camera.ZoomInfo
import com.filtercam.ui.theme.Palette
import kotlin.math.abs

private val STOPS = listOf(0.6f, 1f, 2f, 3f, 5f)

/** Which physical lens a Galaxy S24 Ultra uses at a given zoom ratio (the phone switches lenses automatically). */
fun lensLabel(ratio: Float, front: Boolean): String = when {
    front -> "Front camera"
    ratio < 1f -> "Ultra-wide"
    ratio < 3f -> "Main"
    ratio < 5f -> "Telephoto 3×"
    else -> "Telephoto 5×"
}

private fun fmt(r: Float) = if (abs(r - Math.round(r)) < 0.05f) "${Math.round(r)}" else "%.1f".format(r)

@Composable
fun ZoomBar(zoom: ZoomInfo, front: Boolean, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    val stops = STOPS.filter { it >= zoom.min - 0.01f && it <= zoom.max + 0.01f }
    val active = stops.firstOrNull { abs(it - zoom.ratio) < 0.08f }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${lensLabel(zoom.ratio, front)}  •  ${fmt(zoom.ratio)}×",
            color = Color.White, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        if (stops.size > 1) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0x99000000)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                stops.forEach { s ->
                    val on = s == active
                    Box(
                        Modifier.size(if (on) 40.dp else 34.dp).clip(CircleShape)
                            .background(if (on) Color(0xFF2A2A2A) else Color.Transparent)
                            .clickable { onZoom(s) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            fmt(s) + if (on) "×" else "",
                            color = if (on) Palette.Accent else Color.White, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
