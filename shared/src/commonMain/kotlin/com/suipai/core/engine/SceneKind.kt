package com.suipai.core.engine

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("SceneKind", exact = true)
enum class SceneKind {
    DAYLIGHT,
    PORTRAIT,
    INDOOR,
    NIGHT,
    STREET,
    FOOD,
    MIST,
    CYAN,
    DUSK,
    CLEAR,
    SNOW,
    AUTUMN,
    RAIN,
    DAWN,
    MOON,
    NEON,
    WOOD,
    INKGOLD,
    BLOSSOM,
    CELADON,
    BRONZE,
    HAZE,
}

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("Adaptation", exact = true)
data class Adaptation(
    val scene: SceneKind,
    val look: Look,
    val intensity: Float,
    val reason: String,
)
