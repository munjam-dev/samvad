package com.samvad.messenger.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Mentions
import com.samvad.core.chat.Message
import com.samvad.core.chat.PollResult
import com.samvad.core.chat.Polls
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.SamvadTheme
import com.samvad.messenger.ui.theme.motionSpec
import kotlin.math.abs

/** A centred group event line ("Asha added Ravi"). */
@Composable
internal fun SystemLineChip(text: String) {
  val colors = SamvadTheme.colors
  Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
    Text(
      text,
      style = MaterialTheme.typography.labelMedium,
      color = colors.onChip,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .widthIn(max = 320.dp)
        .background(colors.chip, RoundedCornerShape(10.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

/** A stable colour per sender, from the theme, so names are easy to tell apart. */
@Composable
internal fun senderColor(uid: String): Color {
  val colors = SamvadTheme.colors
  val palette = listOf(colors.primary, colors.onTertiaryContainer, colors.onSecondaryContainer, colors.onPrimaryContainer)
  return palette[abs(uid.hashCode()) % palette.size]
}

@Composable
internal fun SenderLabel(name: String, uid: String) {
  Text(
    name,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.SemiBold,
    color = senderColor(uid),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.padding(bottom = 2.dp),
  )
}

/** Message text with "@Name" mentions of group members in bold brand colour. */
@Composable
internal fun mentionText(text: String, names: List<String>): AnnotatedString {
  val colors = SamvadTheme.colors
  val spans = remember(text, names) { Mentions.spans(text, names) }
  if (spans.isEmpty()) return AnnotatedString(text)
  return buildAnnotatedString {
    append(text)
    for (r in spans) addStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
  }
}

/** Members matching "@query", shown above the composer. */
@Composable
internal fun MentionSuggestions(candidates: List<Pair<String, String>>, onPick: (String, String) -> Unit) {
  val colors = SamvadTheme.colors
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp)
      .heightIn(max = 220.dp)
      .background(colors.surface, RoundedCornerShape(16.dp))
      .verticalScroll(rememberScrollState()),
  ) {
    candidates.forEach { (uid, name) ->
      Row(
        Modifier.fillMaxWidth().clickable { onPick(uid, name) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Avatar(ChatList.initials(name), uid, size = 32.dp)
        Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/** A poll with live results; tap an option to vote (again to withdraw, or toggle when multiple). */
@Composable
internal fun PollCard(message: Message, result: PollResult, mine: Boolean, onVote: (String) -> Unit) {
  val poll = message.poll ?: return
  val colors = SamvadTheme.colors
  val fg = colors.onBubble
  val sub = colors.bubbleMeta
  Column(Modifier.widthIn(min = 220.dp, max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(poll.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
    Text(
      stringResource(if (poll.multi) R.string.poll_multi_hint else R.string.poll_single_hint),
      style = MaterialTheme.typography.labelSmall,
      color = sub,
    )
    poll.ids.zip(poll.options).forEach { (id, option) ->
      val count = result.counts[id] ?: 0
      val chosen = id in result.mine
      val share = if (result.voters == 0) 0f else count.toFloat() / result.voters
      val bar by animateFloatAsState(share, motionSpec(Motion.standard()), label = "pollBar")
      Column(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .toggleable(chosen, role = if (poll.multi) Role.Checkbox else Role.RadioButton) { onVote(id) }
          .padding(vertical = 4.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(
            if (chosen) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            tint = if (chosen) colors.brand else sub,
            modifier = Modifier.size(20.dp),
          )
          Text(option, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f))
          Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = sub)
        }
        Box(Modifier.padding(start = 28.dp, top = 4.dp).fillMaxWidth().height(6.dp).background(sub.copy(alpha = 0.2f), CircleShape)) {
          Box(Modifier.fillMaxWidth(bar).fillMaxHeight().background(if (id in result.leading) colors.brand else sub, CircleShape))
        }
      }
    }
    Text(pluralStringResource(R.plurals.poll_votes, result.voters, result.voters), style = MaterialTheme.typography.labelSmall, color = sub)
  }
}

/** Create a poll: question, 2–12 options, single or multiple answers. */
@Composable
internal fun PollComposer(onCreate: (String, List<String>, Boolean) -> Boolean, onClose: () -> Unit) {
  val colors = SamvadTheme.colors
  var question by rememberSaveable { mutableStateOf("") }
  val options = remember { mutableStateListOf("", "") }
  var multi by rememberSaveable { mutableStateOf(false) }
  var invalid by remember { mutableStateOf(false) }
  Column(
    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(stringResource(R.string.poll_create), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    OutlinedTextField(
      value = question,
      onValueChange = { question = it.take(Polls.MAX_QUESTION); invalid = false },
      label = { Text(stringResource(R.string.poll_question)) },
      modifier = Modifier.fillMaxWidth(),
    )
    options.forEachIndexed { i, option ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = option,
          onValueChange = { options[i] = it.take(Polls.MAX_OPTION); invalid = false },
          label = { Text(stringResource(R.string.poll_option, i + 1)) },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        if (options.size > Polls.MIN_OPTIONS) {
          IconButton(onClick = { options.removeAt(i) }) { Icon(Icons.Outlined.Close, stringResource(R.string.poll_remove_option), tint = colors.onSurfaceVariant) }
        }
      }
    }
    if (options.size < Polls.MAX_OPTIONS) {
      TextButton(onClick = { options.add("") }) { Text(stringResource(R.string.poll_add_option)) }
    }
    Row(
      Modifier.fillMaxWidth().toggleable(multi, role = Role.Switch) { multi = it }.padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(stringResource(R.string.poll_allow_multi), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
      Switch(checked = multi, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary))
    }
    if (invalid) Text(stringResource(R.string.poll_invalid), color = colors.error, style = MaterialTheme.typography.bodySmall)
    PrimaryButton(stringResource(R.string.poll_send), onClick = {
      if (onCreate(question, options.toList(), multi)) onClose() else invalid = true
    })
  }
}

/** Replaces the composer after I left or was removed from a group. */
@Composable
internal fun RemovedBar() {
  val colors = SamvadTheme.colors
  Text(
    stringResource(R.string.group_removed),
    style = MaterialTheme.typography.bodyMedium,
    color = colors.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.background)
      .navigationBarsPadding()
      .padding(12.dp)
      .background(colors.surfaceContainer, RoundedCornerShape(18.dp))
      .padding(16.dp),
  )
}
