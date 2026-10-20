package com.samvad.core.chat

import com.samvad.core.media.Media

/** One photo or video in the shared-media grid. */
data class SharedMediaItem(val messageId: String, val senderId: String, val createdAt: Long, val media: Media, val video: Boolean)

/** One document in the list. */
data class SharedDocument(val messageId: String, val senderId: String, val createdAt: Long, val media: Media)

/** One link found in a message's text. [url] always has a scheme; [host] is shown as the title. */
data class SharedLink(val messageId: String, val senderId: String, val createdAt: Long, val url: String, val host: String)

data class SharedMedia(
  val photos: List<SharedMediaItem>,
  val documents: List<SharedDocument>,
  val links: List<SharedLink>,
) {
  val isEmpty: Boolean get() = photos.isEmpty() && documents.isEmpty() && links.isEmpty()
}

/**
 * "Media, links and documents": built only from messages that are already loaded on this device (the app does not
 * download a whole chat to build it), so the screen says so. Pure logic; newest first. Deleted and expired messages
 * and system lines are left out.
 */
object SharedMediaBuilder {
  private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
  private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

  fun links(text: String): List<String> =
    URL.findAll(text).mapNotNull { m ->
      var raw = m.value.trimEnd(*TRAILING)
      if (raw.count { it == '(' } > raw.count { it == ')' } && m.value.endsWith(")")) raw += ")"
      val withScheme = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
      withScheme.takeIf { host(it).isNotEmpty() && host(it).contains('.') }
    }.distinct().toList()

  /** The host part ("example.com") without scheme, credentials, port and a leading "www.". */
  fun host(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val noUser = authority.substringAfterLast('@')
    val noPort = noUser.substringBefore(':')
    return noPort.removePrefix("www.").lowercase()
  }

  fun build(messages: List<Message>, nowMs: Long): SharedMedia {
    val photos = ArrayList<SharedMediaItem>()
    val documents = ArrayList<SharedDocument>()
    val links = ArrayList<SharedLink>()
    for (m in messages.sortedByDescending { it.createdAt }) {
      if (m.deleted || m.pending || m.kind == "system" || Disappearing.isExpired(m.expiresAt, nowMs)) continue
      val media = m.media
      when {
        media != null && (m.kind == "image" || m.kind == "video" || media.mime.startsWith("video/")) ->
          photos += SharedMediaItem(m.id, m.senderId, m.createdAt, media, video = m.kind == "video" || media.mime.startsWith("video/"))
        media != null && m.kind == "file" -> documents += SharedDocument(m.id, m.senderId, m.createdAt, media)
      }
      if (m.kind == "text" || m.kind == "image" || m.kind == "file" || m.kind == "video") {
        for (url in links(m.text)) links += SharedLink(m.id, m.senderId, m.createdAt, url, host(url))
      }
    }
    return SharedMedia(photos, documents, links)
  }
}
