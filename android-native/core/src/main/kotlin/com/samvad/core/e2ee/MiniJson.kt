package com.samvad.core.e2ee

/**
 * A tiny JSON reader and writer for the encrypted message payload. It exists so that the payload format does not
 * depend on org.json (Android only) and can be tested on the JVM. Numbers are read as Long when they have no
 * fraction or exponent, otherwise Double. Nesting is limited, so a hostile payload cannot overflow the stack.
 */
internal object MiniJson {
  private const val MAX_DEPTH = 12

  fun write(value: Any?): String = StringBuilder().also { append(it, value, 0) }.toString()

  private fun append(out: StringBuilder, value: Any?, depth: Int) {
    require(depth <= MAX_DEPTH) { "too deep" }
    when (value) {
      null -> out.append("null")
      is String -> appendString(out, value)
      is Boolean -> out.append(if (value) "true" else "false")
      is Int, is Long -> out.append(value.toString())
      is Double -> {
        require(value.isFinite()) { "not finite" }
        out.append(value.toString())
      }
      is Map<*, *> -> {
        out.append('{')
        var first = true
        for ((k, v) in value) {
          if (!first) out.append(',')
          first = false
          appendString(out, k as? String ?: throw IllegalArgumentException("key must be a string"))
          out.append(':')
          append(out, v, depth + 1)
        }
        out.append('}')
      }
      is List<*> -> {
        out.append('[')
        value.forEachIndexed { i, v ->
          if (i > 0) out.append(',')
          append(out, v, depth + 1)
        }
        out.append(']')
      }
      else -> throw IllegalArgumentException("unsupported type")
    }
  }

  private fun appendString(out: StringBuilder, s: String) {
    out.append('"')
    for (c in s) {
      when {
        c == '"' -> out.append("\\\"")
        c == '\\' -> out.append("\\\\")
        c == '\n' -> out.append("\\n")
        c == '\r' -> out.append("\\r")
        c == '\t' -> out.append("\\t")
        c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> out.append(c)
      }
    }
    out.append('"')
  }

  /** Parses [text]; throws [IllegalArgumentException] on anything that is not valid JSON. */
  fun parse(text: String): Any? {
    val p = Parser(text)
    val v = p.value(0)
    p.skipWs()
    require(p.pos == text.length) { "trailing data" }
    return v
  }

  private class Parser(val s: String) {
    var pos = 0

    fun skipWs() {
      while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
    }

    fun value(depth: Int): Any? {
      require(depth <= MAX_DEPTH) { "too deep" }
      skipWs()
      require(pos < s.length) { "unexpected end" }
      return when (val c = s[pos]) {
        '{' -> obj(depth)
        '[' -> arr(depth)
        '"' -> str()
        't' -> lit("true", true)
        'f' -> lit("false", false)
        'n' -> lit("null", null)
        else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("unexpected character")
      }
    }

    private fun lit(word: String, v: Any?): Any? {
      require(s.startsWith(word, pos)) { "bad literal" }
      pos += word.length
      return v
    }

    private fun num(): Any {
      val start = pos
      if (s[pos] == '-') pos++
      while (pos < s.length && s[pos] in '0'..'9') pos++
      var isDouble = false
      if (pos < s.length && s[pos] == '.') {
        isDouble = true
        pos++
        while (pos < s.length && s[pos] in '0'..'9') pos++
      }
      if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
        isDouble = true
        pos++
        if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
        while (pos < s.length && s[pos] in '0'..'9') pos++
      }
      val t = s.substring(start, pos)
      return if (isDouble) t.toDouble() else t.toLong()
    }

    private fun str(): String {
      pos++ // opening quote
      val sb = StringBuilder()
      while (true) {
        require(pos < s.length) { "unterminated string" }
        val c = s[pos++]
        when {
          c == '"' -> return sb.toString()
          c == '\\' -> {
            require(pos < s.length) { "bad escape" }
            when (val e = s[pos++]) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'u' -> {
                require(pos + 4 <= s.length) { "bad unicode escape" }
                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                pos += 4
              }
              else -> throw IllegalArgumentException("bad escape $e")
            }
          }
          else -> sb.append(c)
        }
      }
    }

    private fun arr(depth: Int): List<Any?> {
      pos++
      val out = ArrayList<Any?>()
      skipWs()
      if (pos < s.length && s[pos] == ']') { pos++; return out }
      while (true) {
        out.add(value(depth + 1))
        skipWs()
        require(pos < s.length) { "unterminated array" }
        when (s[pos++]) {
          ',' -> continue
          ']' -> return out
          else -> throw IllegalArgumentException("bad array")
        }
      }
    }

    private fun obj(depth: Int): Map<String, Any?> {
      pos++
      val out = LinkedHashMap<String, Any?>()
      skipWs()
      if (pos < s.length && s[pos] == '}') { pos++; return out }
      while (true) {
        skipWs()
        require(pos < s.length && s[pos] == '"') { "bad key" }
        val k = str()
        skipWs()
        require(pos < s.length && s[pos++] == ':') { "missing colon" }
        out[k] = value(depth + 1)
        skipWs()
        require(pos < s.length) { "unterminated object" }
        when (s[pos++]) {
          ',' -> continue
          '}' -> return out
          else -> throw IllegalArgumentException("bad object")
        }
      }
    }
  }
}
