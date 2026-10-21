package com.samvad.messenger.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.privacy.PrivacySettings
import com.samvad.core.privacy.Visibility
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/**
 * Settings → Privacy. Pattern: Settings / Configuration (vectorlab-ux-skills:page-patterns #5):
 * every row here configures the account, nothing here is a single record.
 */
@Composable
fun PrivacySettingsScreen(container: AppContainer, me: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  val privacy = container.privacy
  val settings = privacy?.let { remember(me) { it.settings(me) } }?.collectAsStateWithLifecycle(PrivacySettings())?.value ?: PrivacySettings()

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    }
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(stringResource(R.string.privacy_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

      VisibilityGroup(
        title = stringResource(R.string.privacy_last_seen),
        value = settings.lastSeen,
        onChange = { v -> scope.launch { privacy?.setLastSeenVisibility(me, v) } },
      )
      VisibilityGroup(
        title = stringResource(R.string.privacy_about_label),
        value = settings.aboutVisibility,
        onChange = { v -> scope.launch { privacy?.setAboutVisibility(me, v) } },
      )
      VisibilityGroup(
        title = stringResource(R.string.privacy_photo_label),
        value = settings.photoVisibility,
        onChange = { v -> scope.launch { privacy?.setPhotoVisibility(me, v) } },
      )
      VisibilityGroup(
        title = stringResource(R.string.privacy_group_adds_label),
        value = settings.groupAdds,
        onChange = { v -> scope.launch { privacy?.setGroupAdds(me, v) } },
        noOptionBody = stringResource(R.string.privacy_group_adds_nobody_body),
      )

      PrivacyGroup(stringResource(R.string.privacy_discovery_title)) {
        SwitchRow(
          title = stringResource(R.string.privacy_discovery_switch),
          body = stringResource(R.string.privacy_discovery_body),
          checked = settings.phoneDiscovery,
          onChange = { v -> scope.launch { privacy?.setPhoneDiscovery(me, v) } },
        )
      }

      Text(
        stringResource(R.string.privacy_footer),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
      )
    }
  }
}

@Composable
private fun VisibilityGroup(title: String, value: Visibility, onChange: (Visibility) -> Unit, noOptionBody: String? = null) {
  PrivacyGroup(title) {
    val colors = SamvadTheme.colors
    VisibilityRow(stringResource(R.string.privacy_everyone), value == Visibility.EVERYONE) { onChange(Visibility.EVERYONE) }
    VisibilityRow(stringResource(R.string.privacy_people_i_chat_with), value == Visibility.PARTNERS) { onChange(Visibility.PARTNERS) }
    VisibilityRow(stringResource(R.string.privacy_nobody), value == Visibility.NOBODY) { onChange(Visibility.NOBODY) }
    if (noOptionBody != null && value == Visibility.NOBODY) {
      Text(noOptionBody, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    }
  }
}

@Composable
private fun PrivacyGroup(title: String, content: @Composable () -> Unit) {
  val colors = SamvadTheme.colors
  Column {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Column(
      Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(20.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp)).padding(vertical = 4.dp),
    ) { content() }
  }
}

@Composable
private fun VisibilityRow(label: String, selected: Boolean, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline), modifier = Modifier.size(48.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
  }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .fillMaxWidth()
      .toggleable(checked, role = Role.Switch, onValueChange = onChange)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
      Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    Switch(
      checked = checked,
      onCheckedChange = null,
      colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
    )
  }
}
