package com.suipai.core.engine

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("Look", exact = true)
data class Look(
    val id: String,
    val name: String,
    val summary: String,
    val monochrome: Boolean = false,
    val lockDaylightWb: Boolean = false,
    val contrast: Float = 0.12f,
    val saturation: Float = 0.04f,
    val temperature: Float = 0f,
    val highlightRoll: Float = 0.18f,
    val shadowLift: Float = 0.06f,
    val splitShadowBlue: Float = 0f,
    val splitHighlightWarm: Float = 0f,
    val grain: Float = 0.08f,
    val vignette: Float = 0.08f,
    val softness: Float = 0.04f,
    val fade: Float = 0f,
    val skinProtect: Float = 0f,
    val defaultIntensity: Float = 0.55f,
)
