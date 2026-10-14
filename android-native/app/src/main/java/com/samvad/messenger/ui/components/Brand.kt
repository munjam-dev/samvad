package com.samvad.messenger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

/** The pinwheel mark. Decorative by default; pass [describe] when it stands alone. */
@Composable
fun SamvadMark(size: Dp, modifier: Modifier = Modifier, describe: Boolean = false) {
  Image(
    painter = painterResource(R.drawable.samvad_mark),
    contentDescription = if (describe) stringResource(R.string.cd_samvad_mark) else null,
    modifier = modifier.size(size),
  )
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, markSize: Dp = 28.dp, color: Color = SamvadTheme.colors.onSurface) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
    SamvadMark(markSize)
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = color)
  }
}
