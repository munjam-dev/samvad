package com.samvad.messenger.ui.language

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.samvad.core.profile.Languages
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * Choose the app language. Shown once after the profile is set up (with [onContinue]) and reachable later from
 * Settings. Only languages that are completely translated are listed. Picking one switches the app straight away,
 * so the screen the person is looking at already speaks the language they chose.
 */
@Composable
fun LanguageScreen(onContinue: () -> Unit) {
  val colors = SamvadTheme.colors
  val activity = androidx.activity.compose.LocalActivity.current!!
  var selected by remember { mutableStateOf(AppLanguage.current(activity)) }
  Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
    Spacer(Modifier.height(56.dp))
    Text(stringResource(R.string.language_title), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.language_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
    LanguageList(
      selected,
      { tag -> if (tag != selected) { selected = tag; AppLanguage.apply(activity, tag) } },
      Modifier.weight(1f),
    )
    PrimaryButton(stringResource(R.string.language_continue), onContinue, modifier = Modifier.padding(bottom = 24.dp, top = 8.dp))
  }
}

/** The list of offered languages, each named in its own script so a speaker can find theirs. */
@Composable
fun LanguageList(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    items(Languages.SUPPORTED, key = { it.tag }) { language ->
      val on = language.tag == selected
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (on) colors.primaryContainer else colors.surface,
        border = BorderStroke(1.dp, if (on) colors.primary else colors.outlineVariant),
      ) {
        Row(
          Modifier.fillMaxWidth().selectable(on, onClick = { onSelect(language.tag) }, role = Role.RadioButton).padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(on, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline))
          Text(
            language.nativeName,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
          )
        }
      }
    }
  }
}
