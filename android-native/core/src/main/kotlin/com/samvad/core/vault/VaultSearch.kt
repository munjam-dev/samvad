package com.samvad.core.vault

import java.text.Normalizer
import java.util.Locale

/** What the list shows. An empty [types] set means every type. */
data class VaultFilter(val query: String = "", val types: Set<VaultItemType> = emptySet()) {
  val isActive: Boolean get() = query.isNotBlank() || types.isNotEmpty()

  /** Adds [type] to the chips, or removes it when it is already chosen. */
  fun toggle(type: VaultItemType): VaultFilter =
    copy(types = if (type in types) types - type else types + type)
}

object VaultSearch {
  /** Items matching the filter, newest change first. Every word of the query must appear in the title. */
  fun apply(items: List<VaultItem>, filter: VaultFilter): List<VaultItem> {
    val words = fold(filter.query).split(' ').filter { it.isNotEmpty() }
    return items
      .asSequence()
      .filter { filter.types.isEmpty() || it.type in filter.types }
      .filter { item ->
        if (words.isEmpty()) true else {
          val title = fold(item.title)
          words.all { title.contains(it) }
        }
      }
      .sortedWith(compareByDescending<VaultItem> { it.updatedAt }.thenBy { it.id })
      .toList()
  }

  /** How many items of each type exist, for the chip labels. */
  fun counts(items: List<VaultItem>): Map<VaultItemType, Int> =
    VaultItemType.entries.associateWith { type -> items.count { it.type == type } }

  /** Case, accents and compatibility forms (full-width letters, ligatures) do not matter when searching. */
  internal fun fold(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
    val sb = StringBuilder(decomposed.length)
    for (ch in decomposed) {
      // Latin accents are dropped; Indic vowel signs and viramas are not combining accents here, so they stay.
      if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt() && ch.code in 0x0300..0x036F) continue
      sb.append(ch)
    }
    return sb.toString().lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
  }
}
