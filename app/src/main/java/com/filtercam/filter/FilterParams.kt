package com.filtercam.filter

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ParamKey(val label: String, val range: ClosedFloatingPointRange<Float>) {
    EXPOSURE("Exposure", -1.5f..1.5f),
    TEMPERATURE("Temperature", -1f..1f),
    TINT("Tint (+magenta)", -1f..1f),
    CONTRAST("Contrast", 0.5f..1.6f),
    BLACK_LIFT("Black lift", 0f..0.2f),
    SHADOW_R("Shadow R", 0f..1f), SHADOW_G("Shadow G", 0f..1f), SHADOW_B("Shadow B", 0f..1f),
    SHADOW_AMOUNT("Shadow tint", 0f..1f),
    HIGHLIGHT_R("Highlight R", 0f..1f), HIGHLIGHT_G("Highlight G", 0f..1f), HIGHLIGHT_B("Highlight B", 0f..1f),
    HIGHLIGHT_AMOUNT("Highlight tint", 0f..1f),
    SATURATION("Saturation", 0f..1.6f),
    GREEN_DEEPEN("Green deepen", 0f..1f),
    CYAN_SHIFT("Blue to cyan", 0f..1f),
    SHARPNESS("Sharpness", 0f..1f),
    CLARITY("Clarity", 0f..1f),
    ROLLOFF("Highlight roll-off", 0f..1f),
    SKIN_PROTECT("Skin protect", 0f..1f),
    HALATION("Halation", 0f..1f),
    HALATION_R("Glow R", 0f..1f), HALATION_G("Glow G", 0f..1f), HALATION_B("Glow B", 0f..1f),
    HALATION_THRESHOLD("Glow threshold", 0f..1f),
    GRAIN("Grain", 0f..1f),
    VIGNETTE("Vignette", 0f..1f),
}

@Serializable
data class FilterParams(
    val name: String,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val contrast: Float = 1f,
    val blackLift: Float = 0f,
    val shadowColor: List<Float> = listOf(0f, 0f, 0f),
    val shadowAmount: Float = 0f,
    val highlightColor: List<Float> = listOf(0f, 0f, 0f),
    val highlightAmount: Float = 0f,
    val saturation: Float = 1f,
    val greenDeepen: Float = 0f,
    val cyanShift: Float = 0f,
    val sharpness: Float = 0f,
    val clarity: Float = 0f,
    val highlightRolloff: Float = 0f,
    val skinProtect: Float = 0f,
    val halation: Float = 0f,
    val halationColor: List<Float> = listOf(1f, 1f, 1f),
    val halationThreshold: Float = 0.65f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
) {
    fun get(key: ParamKey): Float = when (key) {
        ParamKey.EXPOSURE -> exposure
        ParamKey.TEMPERATURE -> temperature
        ParamKey.TINT -> tint
        ParamKey.CONTRAST -> contrast
        ParamKey.BLACK_LIFT -> blackLift
        ParamKey.SHADOW_R -> shadowColor[0]
        ParamKey.SHADOW_G -> shadowColor[1]
        ParamKey.SHADOW_B -> shadowColor[2]
        ParamKey.SHADOW_AMOUNT -> shadowAmount
        ParamKey.HIGHLIGHT_R -> highlightColor[0]
        ParamKey.HIGHLIGHT_G -> highlightColor[1]
        ParamKey.HIGHLIGHT_B -> highlightColor[2]
        ParamKey.HIGHLIGHT_AMOUNT -> highlightAmount
        ParamKey.SATURATION -> saturation
        ParamKey.GREEN_DEEPEN -> greenDeepen
        ParamKey.CYAN_SHIFT -> cyanShift
        ParamKey.SHARPNESS -> sharpness
        ParamKey.CLARITY -> clarity
        ParamKey.ROLLOFF -> highlightRolloff
        ParamKey.SKIN_PROTECT -> skinProtect
        ParamKey.HALATION -> halation
        ParamKey.HALATION_R -> halationColor[0]
        ParamKey.HALATION_G -> halationColor[1]
        ParamKey.HALATION_B -> halationColor[2]
        ParamKey.HALATION_THRESHOLD -> halationThreshold
        ParamKey.GRAIN -> grain
        ParamKey.VIGNETTE -> vignette
    }

    fun with(key: ParamKey, v: Float): FilterParams = when (key) {
        ParamKey.EXPOSURE -> copy(exposure = v)
        ParamKey.TEMPERATURE -> copy(temperature = v)
        ParamKey.TINT -> copy(tint = v)
        ParamKey.CONTRAST -> copy(contrast = v)
        ParamKey.BLACK_LIFT -> copy(blackLift = v)
        ParamKey.SHADOW_R -> copy(shadowColor = shadowColor.replaced(0, v))
        ParamKey.SHADOW_G -> copy(shadowColor = shadowColor.replaced(1, v))
        ParamKey.SHADOW_B -> copy(shadowColor = shadowColor.replaced(2, v))
        ParamKey.SHADOW_AMOUNT -> copy(shadowAmount = v)
        ParamKey.HIGHLIGHT_R -> copy(highlightColor = highlightColor.replaced(0, v))
        ParamKey.HIGHLIGHT_G -> copy(highlightColor = highlightColor.replaced(1, v))
        ParamKey.HIGHLIGHT_B -> copy(highlightColor = highlightColor.replaced(2, v))
        ParamKey.HIGHLIGHT_AMOUNT -> copy(highlightAmount = v)
        ParamKey.SATURATION -> copy(saturation = v)
        ParamKey.GREEN_DEEPEN -> copy(greenDeepen = v)
        ParamKey.CYAN_SHIFT -> copy(cyanShift = v)
        ParamKey.SHARPNESS -> copy(sharpness = v)
        ParamKey.CLARITY -> copy(clarity = v)
        ParamKey.ROLLOFF -> copy(highlightRolloff = v)
        ParamKey.SKIN_PROTECT -> copy(skinProtect = v)
        ParamKey.HALATION -> copy(halation = v)
        ParamKey.HALATION_R -> copy(halationColor = halationColor.replaced(0, v))
        ParamKey.HALATION_G -> copy(halationColor = halationColor.replaced(1, v))
        ParamKey.HALATION_B -> copy(halationColor = halationColor.replaced(2, v))
        ParamKey.HALATION_THRESHOLD -> copy(halationThreshold = v)
        ParamKey.GRAIN -> copy(grain = v)
        ParamKey.VIGNETTE -> copy(vignette = v)
    }

    fun clamped(): FilterParams = ParamKey.values().fold(this) { p, k ->
        p.with(k, p.get(k).coerceIn(k.range))
    }

    /** True when every setting is neutral, so the filter would not change the picture. */
    fun isIdentity(): Boolean = copy(name = "") == FilterParams(name = "")

    fun toJson(): String = prettyJson.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

        fun parse(s: String): Result<FilterParams> = runCatching {
            val p = try {
                json.decodeFromString<FilterParams>(s)
            } catch (e: IllegalArgumentException) { // includes SerializationException
                throw IllegalArgumentException("Not a valid filter file")
            }
            require(p.name.isNotBlank()) { "The filter has no name" }
            require(p.shadowColor.size == 3 && p.highlightColor.size == 3 && p.halationColor.size == 3) {
                "shadowColor, highlightColor and halationColor must each have exactly 3 numbers"
            }
            p.clamped()
        }

        fun fromJson(s: String): FilterParams = parse(s).getOrThrow()
    }
}

private fun List<Float>.replaced(i: Int, v: Float): List<Float> = toMutableList().also { it[i] = v }
