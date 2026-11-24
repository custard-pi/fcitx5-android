/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

enum class ClipboardSuggestionKind { Link, Phone, Email }

data class ClipboardSuggestion(val kind: ClipboardSuggestionKind, val text: String)

/** Temporary paste targets extracted from clipboard text, without changing clipboard history. */
object ClipboardSuggestionExtractor {
    private const val MAX_SCANNABLE_CHARS = 16_384
    private const val DEFAULT_LIMIT = 8

    // Common TLDs for scheme-less URLs to avoid matching filenames/package names (e.g. MainActivity.kt, org.fcitx.android).
    private const val COMMON_TLDS =
        "com|org|net|edu|gov|io|co|me|app|dev|ai|cn|tw|hk|jp|kr|uk|de|fr|ru|ca|au|us|eu|" +
            "tv|xyz|info|biz|top|vip|pro|site|online|tech|cloud|store|shop|wiki|gg|im|to|ly|" +
            "be|ch|nl|se|no|fi|pl|es|it|br|in|sg|my|id|vn|th|ph|nz|za|mx|ar|cl|pe|moe|ac|int|mil"

    private const val URL_PATH = """(?:[/?#][A-Za-z0-9\-._~:/?#\[\]@!$&()*+,;=%]*)?"""
    private const val HOST_LABEL = """[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"""

    // Use ASCII-only boundaries so CJK text adjacent to URLs/emails/phones (without spaces) does not break matching.
    private val schemeUrlPattern = Regex(
        """(?i)(?<![A-Za-z0-9_])https?://(?:(?:$HOST_LABEL\.)+[A-Za-z]{2,63}|localhost|(?:\d{1,3}\.){3}\d{1,3})(?::\d{1,5})?$URL_PATH"""
    )
    private val wwwUrlPattern = Regex(
        """(?i)(?<![A-Za-z0-9_@./-])www\.(?:$HOST_LABEL\.)+[A-Za-z]{2,24}(?::\d{1,5})?$URL_PATH(?![A-Za-z0-9_@-])(?!\.[A-Za-z0-9])"""
    )
    private val bareUrlPattern = Regex(
        """(?i)(?<![A-Za-z0-9_@./-])(?:$HOST_LABEL\.)+(?:$COMMON_TLDS)(?::\d{1,5})?$URL_PATH(?![A-Za-z0-9_@-])(?!\.[A-Za-z0-9])"""
    )
    private val emailPattern = Regex(
        """(?i)(?<![A-Za-z0-9_+.-])[A-Za-z0-9][A-Za-z0-9_%+.-]*@(?:$HOST_LABEL\.)+[A-Za-z]{2,63}(?![A-Za-z0-9_-])(?!\.[A-Za-z0-9])"""
    )

    // Chinese mobile, Chinese landline/400/800 hotlines, formatted North American, and explicit E.164 international numbers.
    private val phonePatterns = listOf(
        Regex("""(?<![A-Za-z0-9+])(?:\+?86[\s-]?)?1[3-9]\d(?:[\s-]?\d{4}){2}(?![A-Za-z0-9])"""),
        Regex("""(?<![A-Za-z0-9+])(?:\(0\d{2,3}\)\s*|0\d{2,3}-)\d{7,8}(?![A-Za-z0-9])"""),
        Regex("""(?<![A-Za-z0-9+])[48]00(?:-\d{3}-\d{4}|-\d{4}-\d{3}|\s\d{3}\s\d{4})(?![A-Za-z0-9])"""),
        Regex("""(?<![A-Za-z0-9+])(?:\+1[\s.-]?)?(?:\([2-9]\d{2}\)[\s.-]?|[2-9]\d{2}[\s.-])[2-9]\d{2}[\s.-]\d{4}(?![A-Za-z0-9])"""),
        Regex("""(?<![A-Za-z0-9+])\+[1-9]\d{0,2}(?:[\s().-]*\d){7,14}(?![A-Za-z0-9])""")
    )

    private val patterns: List<Pair<ClipboardSuggestionKind, Regex>> = buildList {
        add(ClipboardSuggestionKind.Link to schemeUrlPattern)
        add(ClipboardSuggestionKind.Link to wwwUrlPattern)
        add(ClipboardSuggestionKind.Link to bareUrlPattern)
        add(ClipboardSuggestionKind.Email to emailPattern)
        phonePatterns.forEach { add(ClipboardSuggestionKind.Phone to it) }
    }

    private const val TRAILING_PUNCTUATION = ".,;:!?)]}"

    private data class Found(
        val start: Int,
        val endExclusive: Int,
        val suggestion: ClipboardSuggestion
    )

    fun extract(text: String, limit: Int = DEFAULT_LIMIT): List<ClipboardSuggestion> {
        if (limit <= 0 || text.length > MAX_SCANNABLE_CHARS) return emptyList()
        val matches = mutableListOf<Found>()
        for ((kind, pattern) in patterns) {
            for (match in pattern.findAll(text)) {
                val value = if (kind == ClipboardSuggestionKind.Link) {
                    match.value.stripTrailingPunctuation()
                } else match.value
                if (value.isEmpty()) continue
                if (kind == ClipboardSuggestionKind.Phone && value.count(Char::isDigit) !in 8..15) continue
                if (kind == ClipboardSuggestionKind.Email && !isValidEmailLocalPart(value)) continue
                matches.add(Found(match.range.first, match.range.first + value.length, ClipboardSuggestion(kind, value)))
            }
        }
        if (matches.isEmpty()) return emptyList()

        val original = text.trim()
        val results = mutableListOf<ClipboardSuggestion>()
        val seen = HashSet<String>()
        var maxClaimedEnd = -1
        for (match in matches.sortedWith(
            compareBy<Found> { it.start }
                .thenByDescending { it.endExclusive }
                .thenBy { it.suggestion.kind.ordinal }
        )) {
            if (match.start < maxClaimedEnd) continue
            // Always claim the span before dedup/original checks so inner submatches
            // (e.g. a phone number inside a copied or duplicated URL) never leak out.
            maxClaimedEnd = match.endExclusive
            if (match.suggestion.text == original) continue
            if (!seen.add(match.suggestion.text)) continue
            results.add(match.suggestion)
            if (results.size >= limit) break
        }
        return results
    }

    private fun isValidEmailLocalPart(email: String): Boolean {
        val local = email.substringBefore('@')
        return !local.endsWith('.') && !local.contains("..")
    }

    private fun String.stripTrailingPunctuation(): String {
        var end = length
        while (end > 0 && this[end - 1] in TRAILING_PUNCTUATION) {
            val last = this[end - 1]
            if (last == ')' && countChar(')', end) <= countChar('(', end)) break
            if (last == ']' && countChar(']', end) <= countChar('[', end)) break
            end--
        }
        return substring(0, end)
    }

    private fun String.countChar(target: Char, endExclusive: Int): Int {
        var count = 0
        for (i in 0 until endExclusive) {
            if (this[i] == target) count++
        }
        return count
    }
}
