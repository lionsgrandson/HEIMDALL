package com.codecrafter.heimdall

import androidx.compose.ui.Modifier

/**
 * Keeps the first HEIMDALL UI dependency-light. The main layout only uses
 * weight as a sizing hint; cards remain readable on devices where intrinsic
 * sizing is preferred.
 */
fun Modifier.weight(@Suppress("UNUSED_PARAMETER") value: Float): Modifier = this
