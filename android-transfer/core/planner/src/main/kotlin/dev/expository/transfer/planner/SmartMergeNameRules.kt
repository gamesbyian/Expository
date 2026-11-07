package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets

/**
 * One conservative component rule for both M1 source capture and M2 previews.
 * A provider-supplied display name is not permission to interpret dot segments,
 * separators or control characters as a future merge destination path.
 *
 * This validates an inert review value only; it never grants write authority.
 */
internal object SmartMergeNameRules {
    // Java UTF-8 encoding silently substitutes '?' for an unpaired UTF-16
    // surrogate. Two distinct provider labels could then share the same
    // byte sequence in a Smart Merge review digest. Reject malformed Unicode
    // before using it in any source/target identity or collision calculation.
    internal fun wellFormedUtf16(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= value.length ||
                        !Character.isLowSurrogate(value[i + 1])) return false
                    i += 2
                }
                Character.isLowSurrogate(c) -> return false
                else -> i++
            }
        }
        return true
    }

    fun validComponent(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            wellFormedUtf16(name) &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
}
