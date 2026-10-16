package com.samvad.core.safety

import java.net.IDN
import java.util.Locale

/** How careful the reader should be. Only [WARNING] ever interrupts the reader (a confirmation before a link opens). */
enum class RiskLevel { NONE, CAUTION, WARNING }

/**
 * Why a message or link was flagged. These codes are the only thing the UI ever turns into words: nothing
 * from the message itself is copied into a reason, so a scammer cannot put text of their choosing into the notice.
 */
enum class ScamReason(val level: RiskLevel, val isLink: Boolean) {
  /** The link points at a number address such as 192.168.0.1 instead of a website name. */
  LINK_IP_ADDRESS(RiskLevel.WARNING, true),

  /** https://bank.com@evil.com: what sits before the @ is only a label; the page that opens is evil.com. */
  LINK_USERINFO(RiskLevel.WARNING, true),

  /** The name imitates a well-known brand (digits for letters, brand plus "login" or "kyc", brand.com.evil.com). */
  LINK_LOOKALIKE_HOST(RiskLevel.WARNING, true),

  /** Letters from another alphabet (for example Cyrillic) made to look like Latin letters. */
  LINK_HOMOGRAPH(RiskLevel.WARNING, true),

  /** An internationalised name (xn-- or non-ASCII letters) that is not an obvious lookalike. */
  LINK_PUNYCODE(RiskLevel.CAUTION, true),

  /** A link shortener hides where the link really goes. */
  LINK_SHORTENER(RiskLevel.CAUTION, true),

  /** The link does not use https. */
  LINK_NOT_HTTPS(RiskLevel.CAUTION, true),

  /** A very long name or many nested sub-domains, used to bury the real site name. */
  LINK_LONG_HOST(RiskLevel.CAUTION, true),

  /** The site name ends in a type heavily used for throw-away scam sites (see [ScamShield.RISKY_TLDS]). */
  LINK_RISKY_TLD(RiskLevel.CAUTION, true),

  /** The site name has characters no normal website name has. */
  LINK_MALFORMED_HOST(RiskLevel.CAUTION, true),

  /** Asks the reader to share an OTP, PIN or CVV. */
  TEXT_SHARE_CODE(RiskLevel.CAUTION, false),

  /** "Verify your account", "KYC update", "your account will be blocked". */
  TEXT_KYC_VERIFY(RiskLevel.CAUTION, false),

  /** Says the reader has won a prize or lottery. */
  TEXT_PRIZE(RiskLevel.CAUTION, false),

  /** Asks for gift cards or their codes. */
  TEXT_GIFT_CARD(RiskLevel.CAUTION, false),

  /** "Work from home, earn 5000 per day". */
  TEXT_WORK_FROM_HOME(RiskLevel.CAUTION, false),

  /** Asks for money and calls it urgent or an emergency. */
  TEXT_URGENT_MONEY(RiskLevel.CAUTION, false),

  /** Asks for a processing, registration or claim fee. Only reported together with another text reason. */
  TEXT_FEE_REQUEST(RiskLevel.CAUTION, false),
}

/** A web address found in a message, as written, with its position in the message text. */
data class ExtractedLink(val raw: String, val start: Int, val end: Int)

/**
 * One link and what is wrong with it.
 *
 * [openUrl] is what would be opened (https:// is added when the sender left the scheme off). [host] is the real
 * destination host in ASCII form (xn-- for international names), which is what the reader is shown before opening.
 * Empty when no host could be read.
 */
data class LinkRisk(
  val raw: String,
  val start: Int,
  val end: Int,
  val openUrl: String,
  val host: String,
  val level: RiskLevel,
  val reasons: Set<ScamReason>,
)

/** The result for one message. [reasons] lists link reasons first, then text reasons; [links] holds every link found, flagged or not. */
data class ScamAssessment(val level: RiskLevel, val reasons: List<ScamReason>, val links: List<LinkRisk>) {
  val isClear: Boolean get() = level == RiskLevel.NONE
  val hasLinkReason: Boolean get() = reasons.any { it.isLink }

  companion object {
    val CLEAR = ScamAssessment(RiskLevel.NONE, emptyList(), emptyList())
  }
}

/**
 * On-device link and message risk checks (Phase 9, Scam Shield). Pure Kotlin: the text never leaves the device,
 * is never logged, and nothing here talks to a server.
 *
 * Design rules:
 *  - Conservative. A plain https link to a well-formed domain is [RiskLevel.NONE].
 *  - Text rules need a request or an offer, not just a keyword: "Do not share your OTP" is a bank's own advice and is
 *    not flagged; "share your OTP" is.
 *  - Text on its own is [RiskLevel.CAUTION]. It becomes [RiskLevel.WARNING] only with a second scam sign
 *    (urgency, a fee, another pattern, or a flagged link).
 *  - Reasons are an enum; message text is never echoed.
 *
 * This reduces risk; it does not detect every scam and it does not vouch for any link or sender.
 */
object ScamShield {
  /** Longest text examined. Messages are capped at 4096 characters; this bounds the work on anything longer. */
  const val MAX_SCANNED_CHARS = 8_192

  /** At most this many links per message are analysed. */
  const val MAX_LINKS = 8

  /** Link shorteners. They are common and often harmless, so they only earn a CAUTION. */
  val SHORTENERS: Set<String> = setOf(
    "bit.ly", "bitly.com", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "v.gd", "cutt.ly", "rb.gy",
    "shorturl.at", "shorturl.asia", "t.ly", "tiny.cc", "buff.ly", "rebrand.ly", "bl.ink", "s.id", "tr.ee",
    "clck.ru", "qr.ae", "short.io", "adf.ly", "lnk.bio", "urlzs.com",
  )

  /**
   * Endings that are heavily used for throw-away scam sites, or that read like file names (.zip, .mov). Kept short on
   * purpose: popular TLDs with some abuse (.xyz, .online, .site) are not listed because they would flag honest links.
   */
  val RISKY_TLDS: Set<String> = setOf("tk", "ml", "ga", "cf", "gq", "top", "icu", "cyou", "click", "zip", "mov", "rest")

  // ---- Links ----------------------------------------------------------------------------------

  private val COMMON_TLDS = setOf(
    "com", "in", "net", "org", "co", "io", "app", "dev", "info", "biz", "me", "us", "uk", "ly", "gl", "to", "gov", "edu",
    "xyz", "online", "site", "store", "shop", "live", "link", "page", "club", "vip", "work", "life", "tech", "pro",
  ) + RISKY_TLDS

  private val LINK = Regex(
    // 1) scheme://..., 2) www....  3) bare host/path with a known TLD or an IPv4 address and a path.
    """(?i)(?:\bhttps?://[^\s<>"'`]+)""" +
      """|(?:(?<![\w@/.\-])www\.[^\s<>"'`]+)""" +
      """|(?:(?<![\w@/.:\-])(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}(?::\d{1,5})?/[^\s<>"'`]*)""" +
      """|(?:(?<![\w@/.:\-])\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?/[^\s<>"'`]*)""",
  )

  private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '\'', '"', '*', '_')

  /** The web addresses in [text], in order. Handles https://, http://, www. and a bare "site.com/path". */
  fun extractLinks(text: String): List<ExtractedLink> {
    val src = if (text.length > MAX_SCANNED_CHARS) text.substring(0, MAX_SCANNED_CHARS) else text
    val out = ArrayList<ExtractedLink>()
    for (m in LINK.findAll(src)) {
      var end = m.range.last + 1
      while (end > m.range.first && src[end - 1] in TRAILING) end--
      val raw = src.substring(m.range.first, end)
      // A bare "site.com/path" is only a link when its ending is a real web ending (not "file.txt/x" or "Rs.500/month").
      if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true) && !raw.startsWith("www.", ignoreCase = true)) {
        val authority = raw.substringBefore('/').substringBefore(':')
        val isIp = authority.all { it.isDigit() || it == '.' }
        if (!isIp && authority.substringAfterLast('.').lowercase(Locale.ROOT) !in COMMON_TLDS) continue
      }
      if (raw.length < 4) continue
      // "https://" alone is not a link.
      if (raw.substringAfter("://", raw).isEmpty()) continue
      out += ExtractedLink(raw, m.range.first, m.range.first + raw.length)
      if (out.size >= MAX_LINKS) break
    }
    return out
  }

  /** Checks one link as written. Never throws. */
  fun assessLink(raw: String, start: Int = 0, end: Int = raw.length): LinkRisk {
    val hasScheme = raw.contains("://")
    val scheme = if (hasScheme) raw.substringBefore("://").lowercase(Locale.ROOT) else "https"
    val rest = if (hasScheme) raw.substringAfter("://") else raw
    val openUrl = if (hasScheme) raw else "https://$raw"

    // Browsers treat a backslash like a slash in http(s) links, so "https://bank.com\@evil.com" opens bank.com.
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) authority.substring(0, at) else null
    val hostPort = if (at >= 0) authority.substring(at + 1) else authority

    val reasons = LinkedHashSet<ScamReason>()
    if (userInfo != null) reasons += ScamReason.LINK_USERINFO
    if (scheme == "http") reasons += ScamReason.LINK_NOT_HTTPS

    val rawHost: String
    if (hostPort.startsWith("[")) {
      rawHost = hostPort.substringBefore(']').trimStart('[')
      reasons += ScamReason.LINK_IP_ADDRESS
    } else {
      rawHost = hostPort.substringBefore(':').trimEnd('.').lowercase(Locale.ROOT)
    }

    var host = rawHost
    if (!hostPort.startsWith("[")) {
      if (rawHost.isEmpty() || rawHost.contains('%') || rawHost.any { it.isWhitespace() || it.isISOControl() }) {
        reasons += ScamReason.LINK_MALFORMED_HOST
      } else {
        analyseHost(rawHost, reasons)?.let { host = it }
      }
    }
    return LinkRisk(raw, start, end, openUrl, host, levelOf(reasons), reasons)
  }

  /** Adds the reasons for a DNS host name. Returns the ASCII form of the host, or null when it could not be converted. */
  private fun analyseHost(host: String, reasons: MutableSet<ScamReason>): String? {
    var ascii = host
    var unicode = host
    if (host.any { it.code > 127 }) {
      try {
        ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        unicode = host
      } catch (_: Exception) {
        reasons += ScamReason.LINK_MALFORMED_HOST
        return null
      }
    } else if (host.split('.').any { it.startsWith("xn--") }) {
      unicode = try { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { host }
    }

    if (ascii.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_') }) {
      reasons += ScamReason.LINK_MALFORMED_HOST
      return ascii
    }

    if (isIpHost(ascii)) {
      reasons += ScamReason.LINK_IP_ADDRESS
      return ascii
    }

    val labels = ascii.split('.')
    // Alphabets.
    val unicodeLabels = unicode.split('.')
    if (unicodeLabels.any { l -> l.any { it.code > 127 } } || labels.any { it.startsWith("xn--") }) {
      if (isHomograph(unicodeLabels)) reasons += ScamReason.LINK_HOMOGRAPH else reasons += ScamReason.LINK_PUNYCODE
    }

    if (ascii in SHORTENERS || labels.size >= 2 && labels.takeLast(2).joinToString(".") in SHORTENERS) {
      reasons += ScamReason.LINK_SHORTENER
    }
    if (labels.size > 5 || ascii.length > 60) reasons += ScamReason.LINK_LONG_HOST
    if (labels.size >= 2 && labels.last() in RISKY_TLDS) reasons += ScamReason.LINK_RISKY_TLD
    if (isBrandLookalike(labels)) reasons += ScamReason.LINK_LOOKALIKE_HOST
    return ascii
  }

  private fun levelOf(reasons: Set<ScamReason>): RiskLevel =
    reasons.maxOfOrNull { it.level } ?: RiskLevel.NONE

  /** Dotted decimal, a single decimal number, hex (0x...) or octal parts: every way a browser reads an IPv4 address. */
  private fun isIpHost(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size !in 1..4) return false
    return parts.all { p -> p.isNotEmpty() && (p.all { it.isDigit() } || (p.startsWith("0x") && p.length > 2 && p.drop(2).all { c -> c.isDigit() || c in 'a'..'f' })) }
  }

  /**
   * True when a label mixes Latin with another alphabet, or is wholly Cyrillic or Greek under an ASCII ending
   * (the classic "аpple.com" trick). Indian scripts are not flagged: a Devanagari name is not a Latin lookalike.
   */
  private fun isHomograph(labels: List<String>): Boolean {
    val tldIsAscii = labels.lastOrNull()?.all { it.code < 128 } ?: true
    for (label in labels) {
      val scripts = HashSet<Character.UnicodeScript>()
      label.codePoints().forEach { cp ->
        if (Character.isLetter(cp)) scripts += Character.UnicodeScript.of(cp)
      }
      if (Character.UnicodeScript.LATIN in scripts && scripts.size > 1) return true
      if (tldIsAscii && scripts.isNotEmpty() && scripts.all { it == Character.UnicodeScript.CYRILLIC || it == Character.UnicodeScript.GREEK }) return true
    }
    return false
  }

  // ---- Brand lookalikes --------------------------------------------------------------------------

  /** Well-known names that scam links imitate, with the registrable domains that really belong to them. */
  private val BRANDS: Map<String, Set<String>> = mapOf(
    "google" to setOf("google.com", "google.co.in"),
    "paypal" to setOf("paypal.com"),
    "amazon" to setOf("amazon.com", "amazon.in", "amazon.co.uk", "amazon.de"),
    "facebook" to setOf("facebook.com"),
    "whatsapp" to setOf("whatsapp.com", "whatsapp.net"),
    "instagram" to setOf("instagram.com"),
    "flipkart" to setOf("flipkart.com"),
    "netflix" to setOf("netflix.com"),
    "microsoft" to setOf("microsoft.com"),
    "apple" to setOf("apple.com"),
    "paytm" to setOf("paytm.com"),
    "phonepe" to setOf("phonepe.com"),
    "hdfcbank" to setOf("hdfcbank.com"),
    "hdfc" to setOf("hdfcbank.com", "hdfc.com"),
    "icicibank" to setOf("icicibank.com"),
    "icici" to setOf("icicibank.com", "icicibank.co.in"),
    "sbi" to setOf("sbi.co.in", "onlinesbi.sbi", "sbi.bank.in"),
    "irctc" to setOf("irctc.co.in"),
    "uidai" to setOf("uidai.gov.in"),
    "aadhaar" to setOf("uidai.gov.in"),
    "npci" to setOf("npci.org.in"),
    "samvad" to setOf("samvad-messenger.web.app"),
  )

  /** Brands short enough that a digit swap would match ordinary words by chance are not checked for digit swaps. */
  private const val MIN_LEET_BRAND_LENGTH = 4

  private val SECURITY_WORDS = setOf(
    "login", "signin", "verify", "verification", "kyc", "secure", "security", "support", "update", "account", "bank",
    "banking", "netbanking", "online", "refund", "helpdesk", "care", "customer", "service", "reward", "rewards", "claim",
    "alert", "wallet", "billing", "recovery", "unlock", "confirm", "activate", "offer", "prize",
  )

  private val SECOND_LEVELS = setOf("co", "com", "org", "net", "gov", "ac", "edu", "nic", "sbi", "bank")

  /** The registrable part of a host: last two labels, or last three under co.in, com.au and similar. */
  private fun registrable(labels: List<String>): String {
    val n = labels.size
    if (n >= 3 && labels[n - 2] in SECOND_LEVELS && labels[n - 1].length == 2) return labels.takeLast(3).joinToString(".")
    return labels.takeLast(minOf(2, n)).joinToString(".")
  }

  /** Letters a digit or look-alike pair may stand for. "1" can be i or l, so each reading is tried. */
  private fun leetVariants(token: String): Set<String> {
    var variants = setOf(token)
    fun swap(from: String, vararg to: String) {
      variants = variants.flatMapTo(HashSet()) { v -> if (from in v) to.map { t -> v.replace(from, t) } + v else listOf(v) }
    }
    swap("0", "o"); swap("1", "i", "l"); swap("3", "e"); swap("4", "a"); swap("5", "s"); swap("7", "t")
    swap("rn", "m"); swap("vv", "w")
    return variants
  }

  private fun isBrandLookalike(labels: List<String>): Boolean {
    if (labels.size < 2) return false
    val reg = registrable(labels)
    val official = BRANDS.values.any { reg in it }
    val subLabels = labels.dropLast(reg.count { it == '.' } + 1)
    val regLabel = reg.substringBefore('.')

    // (1) A digit or look-alike pair standing in for a letter: paypa1, g00gle, arnazon.
    for (label in labels) {
      for (token in label.split('-')) {
        if (token.isEmpty()) continue
        val changed = leetVariants(token) - token
        for ((brand, domains) in BRANDS) {
          if (brand.length < MIN_LEET_BRAND_LENGTH) continue
          if (reg in domains) continue
          if (brand in changed) return true
        }
      }
    }
    // (2) The brand plus a security word in one name: sbi-kyc-update.com, login-paypal.net.
    for (label in labels) {
      val tokens = label.split('-')
      if (tokens.size < 2) continue
      for ((brand, domains) in BRANDS) {
        if (reg in domains) continue
        if (brand in tokens && tokens.any { it in SECURITY_WORDS }) return true
      }
    }
    // (3) The brand's own address buried in front of another domain: paypal.com.evil.net.
    for ((brand, domains) in BRANDS) {
      if (reg in domains) continue
      val at = subLabels.indexOf(brand)
      if (at >= 0 && at + 1 < subLabels.size && subLabels[at + 1] in setOf("com", "in", "co", "org", "net")) return true
    }
    // (4) The registrable name is the brand itself under an ending that is not the brand's: paypal.top, sbi.xyz.
    if (!official) {
      val brand = BRANDS.keys.firstOrNull { it == regLabel && it.length >= MIN_LEET_BRAND_LENGTH }
      if (brand != null && labels.last() in RISKY_TLDS) return true
    }
    return false
  }

  // ---- Text ----------------------------------------------------------------------------------------
  //
  // The text is lower-cased, links are blanked out, and it is cut into sentences. Rules look at one sentence, or at two
  // neighbouring sentences ("Urgent! Send 5000 now"). Each rule needs a request or an offer, not just a keyword.

  private fun rx(pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE))

  private const val L = """(?<![a-z0-9])"""
  private const val R = """(?![a-z0-9])"""

  // Codes the reader must never share. A bare "pin" is left out on purpose: "send me the pin" means a map location.
  private const val CODE =
    """(?:[o0]\.?t\.?p|one[\s-]?time[\s-]?(?:password|pin|code)|(?:upi|atm|debit|card|secret|bank|m)[\s-]?pin|pin\s?number|cvv|cvc|passcode|verification\s?code|security\s?code|6[\s-]?digit\s?code)"""
  private const val VERB_EN = """(?:share|send|tell|give|forward|provide|read\s?out|reply\s?with|dictate|let\s?me\s?know|text|whatsapp)"""
  private const val VERB_HI = """(?:bata|batao|bataiye|bataye|batana|bhej|bhejo|bhejiye|bhejna|bhej\s?do|dedo|de\s?do|dijiye|dena|share\s?kar|send\s?kar|forward\s?kar)"""

  // English "share your OTP"; Hindi word order "OTP batao"; "OTP do".
  private val CODE_ASKED = listOf(
    rx("""$L$VERB_EN$R.{0,40}?$L$CODE$R"""),
    rx("""$L$CODE$R.{0,30}?$L$VERB_HI$R"""),
    rx("""$L$CODE\s(?:do|dedo|de\sdo)$R"""),
    rx("""ओटीपी(?:\s+\S+){0,4}?\s+(?:बताओ|बताइए|बताएं|भेजो|भेजिए|भेजें|दो|शेयर)"""),
  )
  private val CODE_TERM = rx("""$L$CODE$R|ओटीपी""")

  // "Do not share", "never", "mat batao", and "I will send you the OTP" are not requests.
  private val NOT_A_REQUEST = rx(
    """(?<![a-z])(?:do\s?not|don'?t|dont|never|not|mat|nahi|nahin|nhi|no\sone|(?:i|we)\s?(?:will|'ll|shall|can|am\sgoing\sto))(?![a-z])|नहीं|मत""",
  )

  private val KYC_TERM = rx("""${L}kyc$R|केवाईसी""")
  private val KYC_TRIGGER = rx(
    """$L(?:updat\w*|expir\w*|pending|verif\w*|suspend\w*|block\w*|deactivat\w*|incomplete|complete\w*|band|karo|kare|karen|karein|karna|karwao|kijiye)$R|अपडेट|वेरीफाई|बंद|पूरा""",
  )
  private val KYC_OTHER = listOf(
    rx("""$L(?:verify|confirm|validate)\s(?:your|ur)\s(?:\w+\s)?(?:account|bank|identity|details|number|aadhaar|pan)$R"""),
    rx("""${L}account\s(?:\w+\s){0,3}(?:blocked|suspended|closed|deactivated|frozen|restricted|locked)$R"""),
    rx("""$L(?:block|band)\s?ho\s?(?:jayega|jaega|jayegi|jaye|jaenge|gaya)$R"""),
  )

  private val WIN = rx(
    """$L(?:you(?:'ve|\shave|\sare|'re)?\s(?:won|been\sselected|the\swinner)|congratulations?[^a-z0-9]+.{0,40}$L(?:won|winner|selected)|lucky\s(?:winner|draw)|aap(?:ne)?\s(?:jeet|jeeta|jite)|lottery\s(?:nikli|lagi|jeet)|lagi\shai\slottery)$R|(?:लॉटरी|इनाम).{0,20}(?:जीत|निकली|लगी)""",
  )
  private val PRIZE_WORD = rx(
    """$L(?:prize|lottery|lucky\sdraw|jackpot|lakh|crore|reward|iphone|gift|voucher|cash|inaam|kbc|inr\s?\d+|rs\s?\d+)$R|₹\s?\d+|लॉटरी|इनाम""",
  )

  private val GIFT_TERM = rx("""$L(?:gift\s?cards?|google\s?play\s?(?:card|code)|itunes\s?(?:card|code)|steam\s?card|amazon\s?(?:pay\s)?(?:voucher|gift))$R""")
  private val GIFT_EVIDENCE = rx(
    """$L(?:scratch|read\sout|(?:send|share)\s(?:me\s|us\s)?(?:the\s)?(?:code|photo|picture|pic|image|screenshot|number)|(?:code|photo|picture|pic|screenshot)\s(?:of|on)\s(?:the\s)?(?:card|back)|bhej\w*|kharid\w*|(?:buy|purchase)\s.{0,40}(?:urgent\w*|immediately|today|now|asap|abhi|turant))$R""",
  )

  private val JOB = rx("""$L(?:work\s?from\s?home|ghar\s?baithe|part[\s-]?time\s(?:job|work)|online\s(?:job|work)|data\s?entry|like\s(?:and|&)\s(?:share|subscribe)|youtube\slike)$R""")
  private val EARN = rx("""$L(?:earn\w*|kama\w*|income|salary|paise|paisa|inr\s?\d+|rs\s?\d+)$R|₹\s?\d+""")
  private val PER_DAY = rx("""$L(?:per\sday|a\sday|/\s?day|daily|every\sday|roz\w*|rozana|per\shour|an\shour|weekly)$R""")
  private val EARN_PER_DAY = listOf(
    rx("""${L}earn\w*\s(?:up\sto\s)?(?:₹|rs\s?|inr\s?)?\d[\d,]*\s?(?:/\s?|per\s|a\s)?(?:day|daily|hour)$R"""),
    rx("""$L(?:daily|rozana|roz)\s(?:₹|rs\s?)?\d[\d,]*\s(?:kamao|kamayein|kama\w*)$R"""),
    rx("""${L}ghar\s?baithe\s(?:kamao|kamaye|kamayein|paise)"""),
  )

  private val STRONG_URGENCY = rx("""$L(?:urgent(?:ly)?|emergency|asap|immediately|turant|fatafat)$R""")
  private val MONEY_ASK = rx(
    """$L(?:send|transfer|pay|lend|give)\s(?:me\s|us\s)?(?:some\s|the\s)?(?:money|cash|amount|paise|rs|rupees|₹|inr|\d)""" +
      """|$L(?:need|require|want)\s(?:some\s)?(?:money|cash|₹\s?\d|rs\s?\d|\d+\s?(?:rs|rupees|inr))""" +
      """|$L(?:paise|paisa|rupay[ae]|money|cash)\s(?:\w+\s){0,2}(?:bhej\w*|transfer|send|dedo|chahiye|chahie|chaiye|udhaar|udhar)$R""",
  )

  private val URGENCY = rx(
    """$L(?:urgent(?:ly)?|immediately|asap|right\s?now|at\s?once|last\s?chance|final\s(?:notice|warning|reminder)|act\s?now|hurry|limited\s?time|today\s?only|expires?\s(?:today|soon|tonight)|within\s\d+\s?(?:hours?|hrs?|minutes?|mins?|days?)|abhi|turant|jaldi|fatafat|aaj\s?hi|aaj\s?raat|warna)$R""",
  )

  private val FEE_REQUEST = rx(
    """$L(?:(?:processing|registration|delivery|clearance|claim|handling|tax|gst|customs|activation|joining|security)\s?(?:fees?|charges?|amount|deposit)|pay\s(?:a\s)?(?:small\s)?(?:fee|deposit)|deposit\s(?:₹|rs\s?|inr\s?)?\d|fees?\s(?:jama|bharo|dena|bhej\w*))""",
  )

  private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")
  private val RS_DOT = Regex("(?<![a-z])rs\\.\\s?")
  private val SPACES = Regex("\\s+")
  private val SENTENCE_END = Regex("[.!?\\n।]+")

  /** Lower-cases, drops invisible characters and blanks out links (their words are judged by the link checks, not the text rules). */
  private fun normalise(text: String, links: List<ExtractedLink>): String {
    val sb = StringBuilder(text)
    for (l in links) for (i in l.start until minOf(l.end, sb.length)) sb.setCharAt(i, ' ')
    return sb.toString()
      .let { ZERO_WIDTH.replace(it, "") }
      .lowercase(Locale.ROOT)
      .replace('’', '\'')
      .replace('‘', '\'')
      .let { RS_DOT.replace(it, "rs ") }
      .let { SPACES.replace(it, " ") }
  }

  /** The sentences, followed by each pair of neighbouring sentences. */
  private fun windows(t: String): List<String> {
    val s = t.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
    return s + s.zipWithNext { a, b -> "$a $b" }
  }

  private fun textReasons(t: String): List<ScamReason> {
    val sentences = t.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
    val windows = windows(t)
    val primary = ArrayList<ScamReason>(4)

    if (sentences.any { s -> CODE_TERM.containsMatchIn(s) && CODE_ASKED.any { it.containsMatchIn(s) } && !NOT_A_REQUEST.containsMatchIn(s) }) {
      primary += ScamReason.TEXT_SHARE_CODE
    }
    if (windows.any { w ->
        (KYC_TERM.containsMatchIn(w) && KYC_TRIGGER.containsMatchIn(w)) || KYC_OTHER.any { it.containsMatchIn(w) }
      }
    ) {
      primary += ScamReason.TEXT_KYC_VERIFY
    }
    if (windows.any { w -> WIN.containsMatchIn(w) && PRIZE_WORD.containsMatchIn(w) }) primary += ScamReason.TEXT_PRIZE
    if (windows.any { w -> GIFT_TERM.containsMatchIn(w) && GIFT_EVIDENCE.containsMatchIn(w) }) primary += ScamReason.TEXT_GIFT_CARD
    if (windows.any { w -> (JOB.containsMatchIn(w) && EARN.containsMatchIn(w) && PER_DAY.containsMatchIn(w)) || EARN_PER_DAY.any { it.containsMatchIn(w) } }) {
      primary += ScamReason.TEXT_WORK_FROM_HOME
    }
    if (windows.any { w -> STRONG_URGENCY.containsMatchIn(w) && MONEY_ASK.containsMatchIn(w) }) primary += ScamReason.TEXT_URGENT_MONEY
    if (primary.isEmpty()) return emptyList()
    if (FEE_REQUEST.containsMatchIn(t)) primary += ScamReason.TEXT_FEE_REQUEST
    return primary
  }

  /**
   * One sign is a CAUTION. WARNING needs a second sign: another pattern, a fee, urgency wording (not for the
   * urgent-money pattern, which is urgent by definition), or a link that is itself flagged.
   */
  private fun textLevel(t: String, reasons: List<ScamReason>, hasRiskyLink: Boolean): RiskLevel {
    if (reasons.isEmpty()) return RiskLevel.NONE
    val primaries = reasons.filter { it != ScamReason.TEXT_FEE_REQUEST }
    val others = primaries.filter { it != ScamReason.TEXT_URGENT_MONEY }
    val warn = primaries.size >= 2 ||
      (others.isNotEmpty() && (URGENCY.containsMatchIn(t) || ScamReason.TEXT_FEE_REQUEST in reasons)) ||
      hasRiskyLink
    return if (warn) RiskLevel.WARNING else RiskLevel.CAUTION
  }

  // ---- Whole message ---------------------------------------------------------------------------------

  /** Checks a message. Safe to call on every message; blank text and text with no signs return a clear result. */
  fun assess(text: String): ScamAssessment {
    if (text.isBlank()) return ScamAssessment.CLEAR
    val extracted = extractLinks(text)
    val linkRisks = extracted.map { assessLink(it.raw, it.start, it.end) }
    val riskyLink = linkRisks.any { it.level != RiskLevel.NONE }

    val t = normalise(text.take(MAX_SCANNED_CHARS), extracted)
    val textReasons = textReasons(t)
    val textLevel = textLevel(t, textReasons, riskyLink)

    // Scam wording makes a merely cautionable link a warning: that is when the tap should stop and ask.
    val links = if (textReasons.isNotEmpty()) {
      linkRisks.map { if (it.level == RiskLevel.CAUTION) it.copy(level = RiskLevel.WARNING) else it }
    } else {
      linkRisks
    }

    val linkReasons = links.flatMap { it.reasons }.distinct()
      .sortedWith(compareByDescending<ScamReason> { it.level }.thenBy { it.ordinal })
    val level = maxOf(textLevel, links.maxOfOrNull { it.level } ?: RiskLevel.NONE)
    if (level == RiskLevel.NONE) return ScamAssessment(RiskLevel.NONE, emptyList(), links)
    return ScamAssessment(level, linkReasons + textReasons, links)
  }
}
