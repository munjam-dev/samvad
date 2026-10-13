package com.samvad.messenger.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.samvad.messenger.R
import com.samvad.messenger.data.GroupException
import com.samvad.messenger.data.GroupRepository
import com.samvad.messenger.data.InvitePreview
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/** Enter an invite code, see which group it is (name and size only), then join. */
@Composable
fun JoinGroupDialog(groups: GroupRepository, onJoined: (String) -> Unit, onDismiss: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  var code by rememberSaveable { mutableStateOf("") }
  var preview by remember { mutableStateOf<InvitePreview?>(null) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val invalid = stringResource(R.string.group_join_invalid)
  val limit = stringResource(R.string.group_error_limit)
  val full = stringResource(R.string.group_error_full)
  val failed = stringResource(R.string.group_error_failed)
  val clean = code.filter { it.isLetterOrDigit() }.uppercase()
  fun run(work: suspend () -> Unit) {
    busy = true
    error = null
    scope.launch {
      error = try {
        work()
        null
      } catch (e: GroupException.InvalidCode) {
        invalid
      } catch (e: GroupException.Full) {
        full
      } catch (e: GroupException.LimitReached) {
        limit
      } catch (e: Exception) {
        failed
      }
      busy = false
    }
  }
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(stringResource(R.string.group_join)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = code,
          onValueChange = { code = it.take(16); preview = null; error = null },
          label = { Text(stringResource(R.string.group_join_hint)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        preview?.let {
          Text(stringResource(R.string.group_join_preview, it.name, it.memberCount), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        }
        error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        if (busy) CircularProgressIndicator(Modifier, color = colors.brand)
      }
    },
    confirmButton = {
      val p = preview
      if (p == null) {
        TextButton(enabled = !busy && clean.length == 12, onClick = { run { preview = groups.previewInvite(clean) } }) {
          Text(stringResource(R.string.group_join_check))
        }
      } else {
        TextButton(enabled = !busy, onClick = { run { onJoined(groups.join(clean)) } }) { Text(stringResource(R.string.group_join_confirm)) }
      }
    },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
