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
    fun validComponent(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
}
