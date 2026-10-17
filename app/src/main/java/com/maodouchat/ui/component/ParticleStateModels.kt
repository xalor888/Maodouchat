package com.maodouchat.ui.component

import androidx.compose.ui.geometry.Offset

internal data class Particle(
    val origin: Offset,
    val velocity: Offset,
    val size: Float,
    val delay: Float,
    val spin: Float
)
