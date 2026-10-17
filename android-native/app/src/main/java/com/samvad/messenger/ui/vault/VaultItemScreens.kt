package com.samvad.messenger.ui.vault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.vault.VaultItem
import com.samvad.core.vault.VaultLimits
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.vault.VaultException
import com.samvad.messenger.data.vault.VaultState
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

// ---- Picture viewer. Page pattern: Detail / Record (one item, its actions in the top bar). ----

private sealed interface ImageLoad {
  data object Loading : ImageLoad
  data object Failed : ImageLoad
  class Ready(val bitmap: ImageBitmap) : ImageLoad
}

@Composable
internal fun VaultImageScreen(container: AppContainer, id: String, onBack: () -> Unit) {
  VaultGate(container, onBack) { ImageContent(container, id, onBack) }
}

@Composable
private fun ImageContent(container: AppContainer, id: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val repo = container.vault
  val snackbar = remember { SnackbarHostState() }
  val item by produceState<VaultItem?>(null, id) { value = repo.item(id) }
  val image by produceState<ImageLoad>(ImageLoad.Loading, id) {
    value = try {
      repo.loadImage(id, MAX_VIEW_EDGE_PX)?.let { ImageLoad.Ready(it.asImageBitmap()) } ?: ImageLoad.Failed
    } catch (_: VaultException) {
      ImageLoad.Failed
    }
  }
  var action by remember { mutableStateOf<VaultAction?>(null) }
  var menu by remember { mutableStateOf(false) }
  val messageTexts = VaultMessage.entries.associateWith { stringResource(it.text) }
  val scope = rememberCoroutineScope()
  val report: (VaultMessage) -> Unit = { m -> scope.launch { snackbar.showSnackbar(messageTexts.getValue(m)) } }

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      VaultTopBar(item?.title.orEmpty(), onBack) {
        val current = item
        if (current != null) {
          Box {
            IconButton(onClick = { menu = true }) {
              Icon(Icons.Outlined.MoreVert, stringResource(R.string.vault_item_options, current.title), tint = colors.onSurface)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
              DropdownMenuItem(text = { Text(stringResource(R.string.vault_rename)) }, onClick = { menu = false; action = VaultAction.Rename(current) })
              DropdownMenuItem(text = { Text(stringResource(R.string.vault_export)) }, onClick = { menu = false; action = VaultAction.Export(current) })
              DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_delete), color = colors.error) },
                onClick = { menu = false; action = VaultAction.Delete(current) },
              )
            }
          }
        }
      }
      Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        when (val state = image) {
          ImageLoad.Loading -> CircularProgressIndicator(color = colors.brand)
          ImageLoad.Failed -> EmptyState(
            Icons.Outlined.Image,
            stringResource(R.string.vault_title),
            stringResource(R.string.vault_image_failed),
            action = item?.let { stringResource(R.string.vault_export) },
            onAction = { item?.let { action = VaultAction.Export(it) } },
          )
          is ImageLoad.Ready -> ZoomableImage(state.bitmap, stringResource(R.string.vault_image_cd, item?.title.orEmpty()))
        }
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
  }
  VaultActionHost(container, action, onDone = { action = null }, onMessage = report, onDeleted = onBack)
}

private const val MAX_VIEW_EDGE_PX = 2048

/** Pinch to zoom and drag to look around; back to normal size by pinching out. */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, description: String) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  Image(
    bitmap,
    contentDescription = description,
    contentScale = ContentScale.Fit,
    modifier = Modifier
      .fillMaxSize()
      .pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
          scale = (scale * zoom).coerceIn(1f, 6f)
          offset = if (scale <= 1f) Offset.Zero else offset + pan
        }
      }
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offset.x
        translationY = offset.y
      },
  )
}

// ---- Note editor. Page pattern: Form (title and text, one primary action: save). ----

@Stable
internal class NoteViewModel(private val container: AppContainer, id: String?) : ViewModel() {
  private val repo = container.vault

  /** Null until the first save of a new note. */
  var noteId: String? = id
    private set
  var title by mutableStateOf("")
    private set
  var body by mutableStateOf("")
    private set
  var loaded by mutableStateOf(id == null)
    private set
  var missing by mutableStateOf(false)
    private set
  var dirty by mutableStateOf(false)
    private set
  var saving by mutableStateOf(false)
    private set

  fun editTitle(value: String) {
    title = value.take(VaultLimits.MAX_TITLE_CHARS)
    dirty = true
  }

  fun editBody(value: String) {
    body = value.take(VaultLimits.MAX_NOTE_CHARS)
    dirty = true
  }

  suspend fun load() {
    val id = noteId
    if (loaded || id == null) return
    try {
      val item = repo.item(id)
      if (item == null) {
        missing = true
        return
      }
      body = repo.readNote(id)
      title = item.title
      dirty = false
      loaded = true
    } catch (_: VaultException) {
      missing = true
    }
  }

  /** Forget the text held in memory. Called when the vault locks, so a locked vault leaves no readable note behind. */
  fun wipe() {
    title = ""
    body = ""
    dirty = false
    loaded = noteId == null
  }

  /** Returns a message to show, or null when saved without trouble. */
  fun save(fallbackTitle: String, onSaved: (VaultMessage) -> Unit) {
    if (saving) return
    saving = true
    viewModelScope.launch {
      try {
        val item = repo.saveNote(noteId, title, body, fallbackTitle)
        noteId = item.id
        title = item.title
        dirty = false
        onSaved(VaultMessage.NOTE_SAVED)
      } catch (e: VaultException) {
        onSaved(e.toMessage())
      } finally {
        saving = false
      }
    }
  }

  companion object {
    fun factory(container: AppContainer, id: String?) = viewModelFactory { initializer { NoteViewModel(container, id) } }
  }
}

@Composable
internal fun VaultNoteScreen(container: AppContainer, id: String?, onBack: () -> Unit) {
  VaultGate(container, onBack) { NoteContent(container, id, onBack) }
}

@Composable
private fun NoteContent(container: AppContainer, id: String?, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val repo = container.vault
  val vm: NoteViewModel = viewModel(key = "note-${id ?: "new"}", factory = NoteViewModel.factory(container, id))
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val messageTexts = VaultMessage.entries.associateWith { stringResource(it.text) }
  val report: (VaultMessage) -> Unit = { m -> scope.launch { snackbar.showSnackbar(messageTexts.getValue(m)) } }
  val untitled = stringResource(R.string.vault_note_untitled)
  var confirmLeave by remember { mutableStateOf(false) }
  var menu by remember { mutableStateOf(false) }
  var action by remember { mutableStateOf<VaultAction?>(null) }

  LaunchedEffect(vm) { vm.load() }
  // When the vault locks this screen leaves composition; the text must not stay behind in memory.
  DisposableEffect(vm) {
    onDispose { if (repo.state.value != VaultState.UNLOCKED) vm.wipe() }
  }
  val leave: () -> Unit = { if (vm.dirty) confirmLeave = true else onBack() }
  BackHandler(enabled = vm.dirty) { confirmLeave = true }

  Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
    VaultTopBar(
      title = stringResource(if (vm.noteId == null) R.string.vault_note_new else R.string.vault_type_note),
      onBack = leave,
    ) {
      TextButton(onClick = { vm.save(untitled, report) }, enabled = vm.dirty && !vm.saving && vm.loaded) {
        Text(stringResource(R.string.save), color = if (vm.dirty) colors.primary else colors.onSurfaceVariant)
      }
      if (vm.noteId != null) {
        Box {
          IconButton(onClick = { menu = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = colors.onSurface)
          }
          DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.vault_export)) }, onClick = {
              menu = false
              scope.launch { vm.noteId?.let { repo.item(it) }?.let { action = VaultAction.Export(it) } }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.vault_delete), color = colors.error) }, onClick = {
              menu = false
              scope.launch { vm.noteId?.let { repo.item(it) }?.let { action = VaultAction.Delete(it) } }
            })
          }
        }
      }
    }
    when {
      vm.missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(Icons.Outlined.Search, stringResource(R.string.vault_title), stringResource(R.string.vault_missing))
      }
      !vm.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.brand) }
      else -> Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SamvadTextField(vm.title, vm::editTitle, stringResource(R.string.vault_note_title_hint), maxLength = VaultLimits.MAX_TITLE_CHARS)
        SamvadTextField(
          vm.body,
          vm::editBody,
          stringResource(R.string.vault_note_body_hint),
          singleLine = false,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
  Box(Modifier.fillMaxSize()) {
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
  }

  if (confirmLeave) {
    AlertDialog(
      onDismissRequest = { confirmLeave = false },
      title = { Text(stringResource(R.string.vault_note_discard_title)) },
      text = { Text(stringResource(R.string.vault_note_discard_body)) },
      confirmButton = {
        TextButton(onClick = { confirmLeave = false; vm.wipe(); onBack() }) {
          Text(stringResource(R.string.vault_note_discard_confirm), color = colors.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.vault_note_keep_editing)) } },
    )
  }
  VaultActionHost(container, action, onDone = { action = null }, onMessage = report, onDeleted = onBack)
}
