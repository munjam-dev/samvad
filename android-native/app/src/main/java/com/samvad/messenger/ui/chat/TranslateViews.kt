package com.samvad.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.samvad.core.translate.TranslateFailure
import com.samvad.core.translate.TranslateLanguage
import com.samvad.core.translate.TranslateLanguages
import com.samvad.core.translate.TranslateState
import com.samvad.core.translate.TranslationEntry
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

/** Body of the bottom sheet that asks which language to translate into. */
@Composable
fun TranslateSheetContent(onPick: (TranslateLanguage) -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    Text(
      stringResource(R.string.translate_sheet_title),
      style = MaterialTheme.typography.titleMedium,
      color = colors.onSurface,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
    )
    TranslateLanguages.all.forEach { lang ->
      Text(
        lang.nativeName,
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurface,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .clickable { onPick(lang) }
          .padding(horizontal = 24.dp, vertical = 14.dp),
      )
    }
    Text(
      stringResource(R.string.translate_sheet_note),
      style = MaterialTheme.typography.bodySmall,
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
  }
}

/** The translation under a message bubble: progress, the translated text, or an honest error. */
@Composable
fun TranslationCard(
  entry: TranslationEntry,
  onToggleOriginal: () -> Unit,
  onRetry: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SamvadTheme.colors
  val langName = entry.target.nativeName
  Column(
    modifier
      .widthIn(max = 300.dp)
      .background(colors.surfaceContainer, RoundedCornerShape(14.dp))
      .padding(horizontal = 12.dp, vertical = 8.dp)
      .semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    when (val s = entry.state) {
      TranslateState.Downloading, TranslateState.Working -> Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = colors.brand, strokeWidth = 2.dp)
        Text(
          if (s == TranslateState.Downloading) stringResource(R.string.translate_downloading, langName) else stringResource(R.string.translate_working),
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
      }
      is TranslateState.Done -> {
        Text(stringResource(R.string.translate_header, langName), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        if (!entry.showOriginal) {
          Text(s.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.padding(top = 2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(onClick = onToggleOriginal) {
            Text(stringResource(if (entry.showOriginal) R.string.translate_show_translation else R.string.translate_show_original))
          }
          TextButton(onClick = onRemove) { Text(stringResource(R.string.translate_remove)) }
        }
      }
      is TranslateState.Failed -> {
        Text(
          when (s.reason) {
            TranslateFailure.OFFLINE -> stringResource(R.string.translate_error_offline)
            TranslateFailure.UNKNOWN_LANGUAGE -> stringResource(R.string.translate_error_unknown)
            TranslateFailure.SAME_LANGUAGE -> stringResource(R.string.translate_error_same, langName)
            TranslateFailure.FAILED -> stringResource(R.string.translate_error_failed)
          },
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          if (s.reason == TranslateFailure.OFFLINE || s.reason == TranslateFailure.FAILED) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.translate_retry)) }
          }
          TextButton(onClick = onRemove) { Text(stringResource(R.string.translate_remove)) }
        }
      }
    }
  }
}
