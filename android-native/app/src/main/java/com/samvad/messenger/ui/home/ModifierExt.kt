package com.samvad.messenger.ui.home

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp

internal fun Modifier.offsetX(x: Dp): Modifier = this.offset(x = x)
internal fun Modifier.alphaMark(a: Float): Modifier = this.alpha(a)
