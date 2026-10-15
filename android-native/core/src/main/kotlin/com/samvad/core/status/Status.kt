package com.samvad.core.status

/** Who may see a status: everyone I chat with, only chosen people, or everyone except chosen people. */
enum class StatusAudience(val wire: String) {
  ALL("all"), ONLY("only"), EXCEPT("except");

  companion object {
    fun parse(value: String?): StatusAudience = entries.firstOrNull { it.wire == value } ?: ALL
  }
}

/** One status post. [expiresAt] is set by the server a moment after posting; until then it is null. */
data class StatusItem(
  val id: String,
  val authorId: String,
  /** "text" or "image". */
  val kind: String,
  val text: String,
  /** Background style for a text status (see [StatusRules.STYLES]). */
  val style: String?,
  val mediaPath: String?,
  val createdAt: Long,
  val expiresAt: Long?,
)

/** Everything one person has posted that is still live, oldest first, with whether I have seen it all. */
data class StatusGroup(val authorId: String, val items: List<StatusItem>, val allSeen: Boolean) {
  val latestAt: Long get() = items.maxOf { it.createdAt }
}

object StatusRules {
  const val MAX_TEXT = 700
  const val LIFETIME_MS = 24L * 60 * 60 * 1000

  /** Names of the text-status backgrounds. The colours live in the app theme. */
  val STYLES = listOf("forest", "sage", "marigold", "monsoon", "night")
  const val DEFAULT_STYLE = "forest"

  fun style(name: String?): String = if (name in STYLES) name!! else DEFAULT_STYLE

  fun validText(text: String): Boolean = text.trim().let { it.isNotEmpty() && it.length <= MAX_TEXT }

  /**
   * A status is live until its expiry. Before the server has stamped an expiry, the author still sees it
   * (createdAt plus the lifetime), so a fresh post never flickers away.
   */
  fun isLive(item: StatusItem, nowMs: Long): Boolean = nowMs < (item.expiresAt ?: (item.createdAt + LIFETIME_MS))

  /**
   * Groups live statuses by author. People with something unseen come first, then the rest by newest post.
   * Within a group the items run oldest to newest, the order they are played in.
   */
  fun feed(items: List<StatusItem>, seen: Set<String>, me: String, nowMs: Long, muted: Set<String> = emptySet()): List<StatusGroup> =
    items
      .filter { it.authorId != me && it.authorId !in muted && isLive(it, nowMs) }
      .groupBy { it.authorId }
      .map { (author, list) ->
        val ordered = list.sortedBy { it.createdAt }
        StatusGroup(author, ordered, allSeen = ordered.all { it.id in seen })
      }
      .sortedWith(compareBy<StatusGroup> { it.allSeen }.thenByDescending { it.latestAt })

  /** My own live statuses, oldest first. */
  fun mine(items: List<StatusItem>, me: String, nowMs: Long): List<StatusItem> =
    items.filter { it.authorId == me && isLive(it, nowMs) }.sortedBy { it.createdAt }

  /** Index of the first item I have not seen, or the first item when I have seen everything. */
  fun startIndex(group: StatusGroup, seen: Set<String>): Int = group.items.indexOfFirst { it.id !in seen }.let { if (it < 0) 0 else it }

  /** The next moment the feed changes by itself (a status expiring), or null. */
  fun nextExpiry(items: List<StatusItem>, nowMs: Long): Long? =
    items.mapNotNull { it.expiresAt ?: (it.createdAt + LIFETIME_MS) }.filter { it > nowMs }.minOrNull()

  /** "Just now", "12 min ago", "3 h ago": how long ago a status was posted. */
  fun ago(createdAt: Long, nowMs: Long): String {
    val minutes = ((nowMs - createdAt) / 60_000L).coerceAtLeast(0)
    return when {
      minutes < 1 -> "Just now"
      minutes < 60 -> "$minutes min ago"
      else -> "${minutes / 60} h ago"
    }
  }
}
