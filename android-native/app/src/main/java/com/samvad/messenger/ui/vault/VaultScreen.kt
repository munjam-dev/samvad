package com.samvad.messenger.ui.vault

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.vault.VaultItem
import com.samvad.core.vault.VaultItemType
import com.samvad.core.vault.VaultTypes
import com.samvad.core.vault.VaultViewKind
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.vault.VaultKeyProtection
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * Personal Vault home. Page pattern: List / Index (search, type filter, rows with a per-item menu, one add action).
 * Viewing an image is a Detail page and writing a note is a Form page; see VaultItemScreens.kt.
 */
@Composable
internal fun VaultScreen(
  container: AppContainer,
  onBack: () -> Unit,
  onViewImage: (String) -> Unit,
  onEditNote: (String?) -> Unit,
  onOpenBackup: () -> Unit,
) {
  VaultGate(container, onBack) {
    VaultList(container, onBack, onViewImage, onEditNote, onOpenBackup)
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VaultList(
  container: AppContainer,
  onBack: () -> Unit,
  onViewImage: (String) -> Unit,
  onEditNote: (String?) -> Unit,
  onOpenBackup: () -> Unit,
) {
  val colors = SamvadTheme.colors
  val vm: VaultViewModel = viewModel(factory = VaultViewModel.factory(container))
  val state = vm.state.collectAsStateWithLifecycle().value
  val busy = vm.busy.collectAsStateWithLifecycle(false).value
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  val fallbackTitle = stringResource(R.string.vault_default_title)

  var adding by remember { mutableStateOf(false) }
  var about by remember { mutableStateOf(false) }
  var menuOpen by remember { mutableStateOf(false) }
  var action by remember { mutableStateOf<VaultAction?>(null) }

  val messageTexts = VaultMessage.entries.associateWith { stringResource(it.text) }
  LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(messageTexts.getValue(it)) } }

  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) vm.import(uri, asIdCopy = false, fallbackTitle = fallbackTitle)
  }
  val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) vm.import(uri, asIdCopy = false, fallbackTitle = fallbackTitle)
  }
  val pickId = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) vm.import(uri, asIdCopy = true, fallbackTitle = fallbackTitle)
  }

  val open: (VaultItem) -> Unit = { item ->
    when (VaultTypes.viewKind(item.type, item.mime)) {
      VaultViewKind.TEXT -> onEditNote(item.id)
      VaultViewKind.IMAGE -> onViewImage(item.id)
      VaultViewKind.EXTERNAL ->
        if (VaultTypes.canOpenInAnotherApp(item.mime)) action = VaultAction.OpenExternally(item) else vm.report(VaultMessage.OPEN_UNSUPPORTED)
    }
  }

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      VaultTopBar(
        title = stringResource(R.string.vault_title),
        onBack = onBack,
        subtitle = state.all?.let { pluralStringResource(R.plurals.vault_item_count, it.size, it.size) },
      ) {
        IconButton(onClick = { vm.lockNow() }) {
          Icon(Icons.Outlined.Lock, stringResource(R.string.vault_lock_now), tint = colors.onSurface)
        }
        Box {
          IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = colors.onSurface)
          }
          DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            if (com.samvad.messenger.BuildConfig.VAULT_BACKUP_ENABLED) DropdownMenuItem(text = { Text(stringResource(R.string.vbk_menu)) }, onClick = { menuOpen = false; onOpenBackup() })
            DropdownMenuItem(text = { Text(stringResource(R.string.vault_about)) }, onClick = { menuOpen = false; about = true })
          }
        }
      }
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.surfaceContainer)

      val all = state.all
      if (all == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(stringResource(R.string.vault_opening), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
      } else {
        LazyColumn(
          Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item(key = "search") {
            SearchField(state.filter.query, vm::setQuery)
          }
          item(key = "filters") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              VaultItemType.entries.forEach { type ->
                val count = state.counts[type] ?: 0
                FilterChip(
                  selected = type in state.filter.types,
                  onClick = { vm.toggleType(type) },
                  label = {
                    val label = stringResource(type.pluralLabel())
                    Text(if (count > 0) "$label · $count" else label)
                  },
                  colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.primaryContainer,
                    selectedLabelColor = colors.onPrimaryContainer,
                    labelColor = colors.onSurface,
                  ),
                )
              }
            }
          }
          when {
            all.isEmpty() -> item(key = "empty") {
              EmptyState(
                Icons.Outlined.Lock,
                stringResource(R.string.vault_empty_title),
                stringResource(R.string.vault_empty_body),
                action = stringResource(R.string.vault_add),
                onAction = { adding = true },
              )
            }
            state.shown.isEmpty() -> item(key = "none") {
              EmptyState(
                Icons.Outlined.Search,
                stringResource(R.string.vault_no_results_title),
                stringResource(R.string.vault_no_results_body),
                action = stringResource(R.string.vault_clear_filters),
                onAction = vm::clearFilters,
              )
            }
            else -> items(state.shown, key = { it.id }) { item ->
              VaultRow(container, item, onOpen = { open(item) }, onAction = { action = it })
            }
          }
        }
      }
    }
    ExtendedFloatingActionButton(
      onClick = { adding = true },
      icon = { Icon(Icons.Outlined.Add, null) },
      text = { Text(stringResource(R.string.vault_add)) },
      containerColor = colors.primary,
      contentColor = colors.onPrimary,
      modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
    )
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 88.dp))
  }

  if (adding) {
    ModalBottomSheet(onDismissRequest = { adding = false }) {
      Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
        AddRow(Icons.Outlined.Image, R.string.vault_add_photo, R.string.vault_add_photo_body) {
          adding = false
          container.appLock.expectExternal()
          pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        AddRow(Icons.Outlined.Description, R.string.vault_add_document, R.string.vault_add_document_body) {
          adding = false
          container.appLock.expectExternal()
          pickDocument.launch(arrayOf("*/*"))
        }
        AddRow(Icons.Outlined.Badge, R.string.vault_add_id, R.string.vault_add_id_body) {
          adding = false
          container.appLock.expectExternal()
          pickId.launch(arrayOf("image/*", "application/pdf"))
        }
        AddRow(Icons.Outlined.EditNote, R.string.vault_add_note, R.string.vault_add_note_body) {
          adding = false
          onEditNote(null)
        }
      }
    }
  }

  if (about) {
    val protection = remember(about) { container.vault.protection() }
    AlertDialog(
      onDismissRequest = { about = false },
      title = { Text(stringResource(R.string.vault_about)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.vault_about_body))
          Text(
            stringResource(
              when (protection) {
                VaultKeyProtection.STRONGBOX -> R.string.vault_protection_strongbox
                VaultKeyProtection.HARDWARE -> R.string.vault_protection_hardware
                VaultKeyProtection.SOFTWARE -> R.string.vault_protection_software
                VaultKeyProtection.UNKNOWN -> R.string.vault_protection_unknown
              },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
          )
        }
      },
      confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.vault_close)) } },
    )
  }

  VaultActionHost(
    container = container,
    action = action,
    onDone = { action = null },
    onMessage = vm::report,
  )
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
  val colors = SamvadTheme.colors
  OutlinedTextField(
    value = query,
    onValueChange = onChange,
    placeholder = { Text(stringResource(R.string.vault_search_hint)) },
    leadingIcon = { Icon(Icons.Outlined.Search, null) },
    trailingIcon = {
      if (query.isNotEmpty()) {
        IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.vault_search_clear)) }
      }
    },
    singleLine = true,
    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    shape = RoundedCornerShape(com.samvad.core.design.Radii.FIELD.dp),
    textStyle = MaterialTheme.typography.bodyLarge,
    colors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = colors.primary,
      unfocusedBorderColor = colors.outline,
      cursorColor = colors.primary,
      focusedContainerColor = colors.surface,
      unfocusedContainerColor = colors.surface,
      focusedLeadingIconColor = colors.onSurfaceVariant,
      unfocusedLeadingIconColor = colors.onSurfaceVariant,
      focusedTrailingIconColor = colors.onSurfaceVariant,
      unfocusedTrailingIconColor = colors.onSurfaceVariant,
    ),
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun AddRow(icon: ImageVector, title: Int, body: Int, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 24.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(icon, null, tint = colors.onPrimaryContainer)
    Column(Modifier.weight(1f)) {
      Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
      Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
  }
}

@Composable
private fun VaultRow(container: AppContainer, item: VaultItem, onOpen: () -> Unit, onAction: (VaultAction) -> Unit) {
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  val shape = MaterialTheme.shapes.large
  var menu by remember { mutableStateOf(false) }
  val typeLabel = stringResource(item.type.label())
  val meta = stringResource(R.string.vault_item_meta, typeLabel, Formatter.formatShortFileSize(context, item.sizeBytes))
  Row(
    Modifier
      .fillMaxWidth()
      .background(colors.surface, shape)
      .border(1.dp, colors.outlineVariant, shape)
      .clip(shape),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      Modifier
        .weight(1f)
        .clickable(role = Role.Button, onClick = onOpen)
        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
        .semantics(mergeDescendants = true) { },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Thumbnail(container, item)
      Column(Modifier.weight(1f)) {
        Text(item.title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
    Box {
      IconButton(onClick = { menu = true }) {
        Icon(Icons.Outlined.MoreVert, stringResource(R.string.vault_item_options, item.title), tint = colors.onSurfaceVariant)
      }
      DropdownMenu(menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.vault_rename)) }, onClick = { menu = false; onAction(VaultAction.Rename(item)) })
        DropdownMenuItem(text = { Text(stringResource(R.string.vault_export)) }, onClick = { menu = false; onAction(VaultAction.Export(item)) })
        DropdownMenuItem(
          text = { Text(stringResource(R.string.vault_delete), color = colors.error) },
          onClick = { menu = false; onAction(VaultAction.Delete(item)) },
        )
      }
    }
  }
}

/** A small decrypted preview for pictures, or the icon for the item's type. Decorative: the row text says what it is. */
@Composable
private fun Thumbnail(container: AppContainer, item: VaultItem) {
  val colors = SamvadTheme.colors
  val preview by produceState<ImageBitmap?>(null, item.id, item.updatedAt) {
    value = if (item.hasThumbnail) container.vault.loadThumbnail(item.id)?.asImageBitmap() else null
  }
  val shape = RoundedCornerShape(12.dp)
  Box(
    Modifier.size(48.dp).clip(shape).background(colors.primaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    val bitmap = preview
    if (bitmap != null) {
      Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
      Icon(item.type.icon(), null, tint = colors.onPrimaryContainer)
    }
  }
}

private fun VaultItemType.icon(): ImageVector = when (this) {
  VaultItemType.PHOTO -> Icons.Outlined.Image
  VaultItemType.DOCUMENT -> Icons.Outlined.Description
  VaultItemType.NOTE -> Icons.Outlined.EditNote
  VaultItemType.ID_COPY -> Icons.Outlined.Badge
}

private fun VaultItemType.label(): Int = when (this) {
  VaultItemType.PHOTO -> R.string.vault_type_photo
  VaultItemType.DOCUMENT -> R.string.vault_type_document
  VaultItemType.NOTE -> R.string.vault_type_note
  VaultItemType.ID_COPY -> R.string.vault_type_id_copy
}

private fun VaultItemType.pluralLabel(): Int = when (this) {
  VaultItemType.PHOTO -> R.string.vault_type_photos
  VaultItemType.DOCUMENT -> R.string.vault_type_documents
  VaultItemType.NOTE -> R.string.vault_type_notes
  VaultItemType.ID_COPY -> R.string.vault_type_id_copies
}
