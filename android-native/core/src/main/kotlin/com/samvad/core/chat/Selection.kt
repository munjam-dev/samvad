package com.samvad.core.chat

/** What starring does to the current selection. */
enum class StarAction { NONE, STAR, UNSTAR }

/** Which actions the selection bar offers for the messages that are selected. */
data class SelectionActions(
  val copy: Boolean,
  val forward: Boolean,
  val star: StarAction,
  val deleteForEveryone: Boolean,
  /** The full single-message menu (reactions, reply, edit) is offered when exactly one is selected. */
  val moreOptions: Boolean,
)

/** Rules for selecting several messages at once. Pure logic; the screen only draws the result. */
object Selection {
  /** Forwarding sends every selected message to every chosen chat, so the batch stays small. */
  const val MAX_FORWARD_MESSAGES = 20

  /** Whether a message can be part of a selection (same bar as the long-press menu). */
  fun canSelect(message: Message): Boolean = !message.deleted && !message.pending && message.kind != "system"

  /** Adds [id] when it is not selected, removes it when it is. */
  fun toggle(selected: Set<String>, id: String): Set<String> = if (id in selected) selected - id else selected + id

  /** The selected messages, oldest first (ties broken by id so the order is stable). */
  fun ordered(messages: Collection<Message>, selected: Set<String>): List<Message> =
    messages.filter { it.id in selected && canSelect(it) }.sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id })

  private fun copyable(message: Message): Boolean = message.kind == "text" && message.text.isNotBlank()

  /**
   * Which actions are allowed for [selection] (already ordered or not). Delete for everyone needs
   * every message to be mine and delivered; starring needs every message to be starrable.
   */
  fun actions(selection: List<Message>, me: String, starredIds: Set<String>): SelectionActions {
    if (selection.isEmpty()) return SelectionActions(false, false, StarAction.NONE, false, false)
    val starrable = selection.all { !it.deleted && it.kind != "system" && !it.pending && !it.failed }
    return SelectionActions(
      copy = selection.any(::copyable),
      forward = selection.size <= MAX_FORWARD_MESSAGES && selection.all(MessageRules::canForward),
      star = when {
        !starrable -> StarAction.NONE
        selection.all { it.id in starredIds } -> StarAction.UNSTAR
        else -> StarAction.STAR
      },
      deleteForEveryone = selection.all { it.senderId == me && !it.deleted && !it.pending && !it.failed },
      moreOptions = selection.size == 1,
    )
  }

  /** The text to copy: every copyable message's text, oldest first, one per line. */
  fun copyText(selection: List<Message>): String =
    selection.sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id }).filter(::copyable).joinToString("\n") { it.text }

  /** The messages starring should change: only those not yet starred when starring, all when unstarring. */
  fun starTargets(selection: List<Message>, action: StarAction, starredIds: Set<String>): List<Message> = when (action) {
    StarAction.NONE -> emptyList()
    StarAction.STAR -> selection.filter { it.id !in starredIds }
    StarAction.UNSTAR -> selection.filter { it.id in starredIds }
  }
}
