package cz.photoscanar.project

/**
 * A 200-line JSON writer, so that project.json can be produced by plain JVM unit
 * tests (android.util.JSONObject is a stub outside an instrumented test) and
 * without pulling in a serialisation library.
 */
class JsonWriter(private val indentStep: String = "  ") {

    private val sb = StringBuilder()
    private var depth = 0
    private val needsComma = ArrayList<Boolean>()

    private fun indent() {
        sb.append('\n')
        repeat(depth) { sb.append(indentStep) }
    }

    private fun prefix() {
        if (needsComma.isNotEmpty() && needsComma[needsComma.size - 1]) sb.append(',')
        if (needsComma.isNotEmpty()) needsComma[needsComma.size - 1] = true
        if (depth > 0) indent()
    }

    fun beginObject(): JsonWriter { prefix(); sb.append('{'); depth++; needsComma.add(false); return this }
    fun beginObject(key: String): JsonWriter {
        prefix(); sb.append(quote(key)).append(": {"); depth++; needsComma.add(false); return this
    }
    fun endObject(): JsonWriter {
        needsComma.removeAt(needsComma.size - 1); depth--; indent(); sb.append('}'); return this
    }
    fun beginArray(key: String): JsonWriter {
        prefix(); sb.append(quote(key)).append(": ["); depth++; needsComma.add(false); return this
    }
    fun endArray(): JsonWriter {
        needsComma.removeAt(needsComma.size - 1); depth--; indent(); sb.append(']'); return this
    }

    fun value(key: String, v: String?): JsonWriter {
        prefix(); sb.append(quote(key)).append(": ").append(if (v == null) "null" else quote(v)); return this
    }
    fun value(key: String, v: Long?): JsonWriter {
        prefix(); sb.append(quote(key)).append(": ").append(v?.toString() ?: "null"); return this
    }
    fun value(key: String, v: Int?): JsonWriter = value(key, v?.toLong())
    fun value(key: String, v: Boolean): JsonWriter {
        prefix(); sb.append(quote(key)).append(": ").append(v); return this
    }
    fun value(key: String, v: Double?, decimals: Int = 6): JsonWriter {
        prefix(); sb.append(quote(key)).append(": ")
        if (v == null || v.isNaN() || v.isInfinite()) sb.append("null")
        else sb.append(Companion.trimNumber(String.format(java.util.Locale.US, "%.${decimals}f", v)))
        return this
    }
    fun value(key: String, v: Float?, decimals: Int = 6): JsonWriter = value(key, v?.toDouble(), decimals)

    fun stringArray(key: String, values: List<String>): JsonWriter {
        beginArray(key)
        for (v in values) { prefix(); sb.append(quote(v)) }
        endArray()
        return this
    }

    fun floatArray(key: String, values: List<Float>, decimals: Int = 4): JsonWriter {
        beginArray(key)
        for (v in values) {
            prefix()
            sb.append(Companion.trimNumber(String.format(java.util.Locale.US, "%.${decimals}f", v)))
        }
        endArray()
        return this
    }

    fun intArray(key: String, values: List<Int>): JsonWriter {
        beginArray(key)
        for (v in values) { prefix(); sb.append(v) }
        endArray()
        return this
    }

    override fun toString(): String = sb.toString()

    companion object {
        fun quote(s: String): String {
            val out = StringBuilder(s.length + 2)
            out.append('"')
            for (c in s) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            out.append('"')
            return out.toString()
        }

        internal fun trimNumber(s: String): String {
            if (!s.contains('.')) return s
            return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }
    }
}
