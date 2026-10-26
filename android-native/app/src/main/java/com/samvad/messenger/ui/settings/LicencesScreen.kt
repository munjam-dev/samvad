package com.samvad.messenger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.SamvadTheme

/** Where the published source lives (also named in README.md and NOTICE). */
const val SOURCE_REPO_URL = "https://github.com/munjam-dev/samvad"

/**
 * Settings → Source code and licences. Pattern: Detail (vectorlab-ux-skills:page-patterns): one read-only
 * document with a single action, opening the repository.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val uri = LocalUriHandler.current
  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.licences_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    }
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
      Text(stringResource(R.string.licences_intro), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
      Text(
        stringResource(R.string.licences_server),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.licences_libs_title),
        style = MaterialTheme.typography.titleSmall,
        color = colors.onSurface,
        modifier = Modifier.padding(top = 24.dp),
      )
      Text(
        stringResource(R.string.licences_libs_body),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
      Text(
        stringResource(R.string.licences_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
      )
    }
    PrimaryButton(
      stringResource(R.string.licences_open_repo),
      { uri.openUri(SOURCE_REPO_URL) },
      Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
    )
  }
}
