package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic, inert folder-name suggestion for a future Smart Merge.
 * Only source display names are read, never actual file bytes, credentials,
 * paths or provider metadata. No suggestion constitutes write permission,
 * a deduplication claim, or completed conflict review.
 */
object SmartMergeNameSuggestions {
    private const val FALLBACK = "Combined"
    private const val MAX_SUGGESTION_BYTES = 120
    private const val MAX_SOURCES = 64
    private val YEAR = Regex("(?:19|20)[0-9]{2}")
    private val SUFFIX_YEAR = Regex("^(.+?)[ ._-]+((?:19|20)[0-9]{2})$")
    // Disc 01 / Disc 02, Season_1 / Season_2, etc. Require a common
    // descriptive stem and CONTIGUOUS ordinal values to avoid implying that
    // missing volumes or folders have actually been selected.
    private val SUFFIX_ORDINAL = Regex("^(.+?)[ ._-]+([0-9]{1,3})$")
    private val PAREN_ORDINAL = Regex("^(.+?)[ ._-]+\\\\(([0-9]{1,3})\\\\)$")

    fun suggestFromSourceNames(names: List<String>): String {
        if (names.size !in 2..MAX_SOURCES ||
            names.any { !SmartMergeNameRules.validComponent(it) })
            return FALLBACK

        // Normalize canonically equivalent Unicode; sorting guarantees that
        // reordering panes or basket items does not rename the proposal.
        val normalized = names.map {
            Normalizer.normalize(it.trim(), Normalizer.Form.NFC)
        }.sortedWith(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })

        fun safe(value: String): String = value.takeIf {
            it.toByteArray(StandardCharsets.UTF_8).size <= MAX_SUGGESTION_BYTES &&
                SmartMergeNameRules.validComponent(it)
        } ?: FALLBACK

        val first = normalized.first()
        if (normalized.all { it.equals(first, ignoreCase = true) })
            return safe("$first Combined")

        val years = normalized.map { label ->
            if (YEAR.matches(label)) "" to label
            else SUFFIX_YEAR.matchEntire(label)?.let {
                it.groupValues[1].trimEnd() to it.groupValues[2]
            }
        }
        if (years.all { it != null }) {
            val found = years.filterNotNull()
            val prefix = found.first().first
            if (found.all { it.first.equals(prefix, ignoreCase = true) }) {
                val values = found.map { it.second.toInt() }
                if (values.distinct().size >= 2) {
                    val span = "${values.min()}–${values.max()}"
                    return safe(if (prefix.isEmpty()) "$span Combined"
                        else "$prefix $span")
                }
            }
        }

        // A short numeric suffix often identifies a set of volumes, discs
        // or consecutively numbered folders. Suggest a readable span only
        // when every selected label has the SAME stem, numeric width and a
        // complete contiguous range. A missing or repeated ordinal must not
        // be disguised by a misleading range suggestion.
        // Parenthesized disc numbers are common in exported photo/music
        // folders. Do not mix "Disc 1" and "Disc (2)": the formatting
        // mismatch could indicate two unrelated naming conventions.
        data class Ordinal(val stem: String, val digits: String,
            val parenthesized: Boolean)
        val numbered = normalized.map { label ->
            val plain = SUFFIX_ORDINAL.matchEntire(label)
            val paren = if (plain == null) PAREN_ORDINAL.matchEntire(label) else null
            when {
                plain != null -> Ordinal(plain.groupValues[1].trimEnd(),
                    plain.groupValues[2], false)
                paren != null -> Ordinal(paren.groupValues[1].trimEnd(),
                    paren.groupValues[2], true)
                else -> null
            }
        }
        if (numbered.any { it == null }) return FALLBACK
        val found = numbered.filterNotNull()
        val prefix = found.first().stem
        val width = found.first().digits.length
        val parenthesized = found.first().parenthesized
        if (prefix.isBlank() ||
            !found.all { it.stem.equals(prefix, ignoreCase = true) &&
                it.digits.length == width &&
                it.parenthesized == parenthesized }) return FALLBACK
        val sequence = found.map { it.digits.toInt() }
        if (sequence.distinct().size != sequence.size ||
            sequence.max() - sequence.min() + 1 != sequence.size)
            return FALLBACK
        val left = sequence.min().toString().padStart(width, '0')
        val right = sequence.max().toString().padStart(width, '0')
        return safe(if (parenthesized) "$prefix ($left–$right)"
            else "$prefix $left–$right")
    }
}
