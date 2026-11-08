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

        val parts = normalized.map { label ->
            if (YEAR.matches(label)) "" to label
            else SUFFIX_YEAR.matchEntire(label)?.let {
                it.groupValues[1].trimEnd() to it.groupValues[2]
            }
        }
        if (parts.any { it == null }) return FALLBACK
        val found = parts.filterNotNull()
        val prefix = found.first().first
        if (!found.all { it.first.equals(prefix, ignoreCase = true) })
            return FALLBACK
        val years = found.map { it.second.toInt() }
        if (years.distinct().size < 2) return FALLBACK
        val range = "${years.min()}–${years.max()}"
        return safe(if (prefix.isEmpty()) "$range Combined" else "$prefix $range")
    }
}
