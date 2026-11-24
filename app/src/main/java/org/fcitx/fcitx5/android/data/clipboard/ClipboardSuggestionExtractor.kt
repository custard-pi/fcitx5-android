/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import timber.log.Timber

enum class ClipboardSuggestionKind { Link, Phone, Email, Address, Otp }

enum class ClipboardExtractMode(override val stringRes: Int) : ManagedPreferenceEnum {
    TextClassifierWithFallback(R.string.clipboard_extract_mode_auto),
    TextClassifierOnly(R.string.clipboard_extract_mode_text_classifier),
    RegexOnly(R.string.clipboard_extract_mode_regex),
    Disabled(R.string.disabled);
}

data class ClipboardExtractConfig(
    val linkMode: ClipboardExtractMode = ClipboardExtractMode.TextClassifierWithFallback,
    val phoneMode: ClipboardExtractMode = ClipboardExtractMode.TextClassifierWithFallback,
    val emailMode: ClipboardExtractMode = ClipboardExtractMode.TextClassifierWithFallback,
    val extractAddress: Boolean = true,
    val extractOtp: Boolean = true
) {
    fun modeFor(kind: ClipboardSuggestionKind): ClipboardExtractMode = when (kind) {
        ClipboardSuggestionKind.Link -> linkMode
        ClipboardSuggestionKind.Phone -> phoneMode
        ClipboardSuggestionKind.Email -> emailMode
        ClipboardSuggestionKind.Address -> if (extractAddress) ClipboardExtractMode.TextClassifierOnly else ClipboardExtractMode.Disabled
        ClipboardSuggestionKind.Otp -> if (extractOtp) ClipboardExtractMode.TextClassifierOnly else ClipboardExtractMode.Disabled
    }

    fun textClassifierKinds(): Set<ClipboardSuggestionKind> = buildSet {
        for (kind in ClipboardSuggestionKind.entries) {
            val mode = modeFor(kind)
            if (mode == ClipboardExtractMode.TextClassifierWithFallback ||
                mode == ClipboardExtractMode.TextClassifierOnly
            ) {
                add(kind)
            }
        }
    }

    fun hasAnyEnabled(): Boolean = ClipboardSuggestionKind.entries.any {
        modeFor(it) != ClipboardExtractMode.Disabled
    }
}

data class ClipboardSuggestion(val kind: ClipboardSuggestionKind, val text: String)

data class ExtractedSpan(
    val start: Int,
    val endExclusive: Int,
    val kind: ClipboardSuggestionKind,
    val text: String
)

fun interface TextClassifierProvider {
    fun classify(text: String, kinds: Set<ClipboardSuggestionKind>): List<ExtractedSpan>
}

/** Temporary paste targets extracted from clipboard text, without changing clipboard history. */
object ClipboardSuggestionExtractor {
    private const val MAX_SCANNABLE_CHARS = 16_384
    private const val DEFAULT_LIMIT = 8
    private const val ENTITY_TYPE_OTP = "otp"

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

    private val regexPatternsByKind: Map<ClipboardSuggestionKind, List<Regex>> = mapOf(
        ClipboardSuggestionKind.Link to listOf(schemeUrlPattern, wwwUrlPattern, bareUrlPattern),
        ClipboardSuggestionKind.Phone to phonePatterns,
        ClipboardSuggestionKind.Email to listOf(emailPattern)
    )

    private const val TRAILING_PUNCTUATION = ".,;:!?)]}"

    fun extract(
        context: Context,
        text: String,
        config: ClipboardExtractConfig = ClipboardExtractConfig(),
        limit: Int = DEFAULT_LIMIT
    ): List<ClipboardSuggestion> {
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            TextClassifierProvider { input, kinds -> classifyWithAndroid(context, input, kinds) }
        } else {
            null
        }
        return extract(text, config, provider, limit)
    }

    fun extract(
        text: String,
        config: ClipboardExtractConfig = ClipboardExtractConfig(),
        textClassifierProvider: TextClassifierProvider? = null,
        limit: Int = DEFAULT_LIMIT
    ): List<ClipboardSuggestion> {
        if (limit <= 0 || text.length > MAX_SCANNABLE_CHARS || !config.hasAnyEnabled()) {
            return emptyList()
        }

        val tcKinds = config.textClassifierKinds()
        val tcByKind = if (tcKinds.isNotEmpty() && textClassifierProvider != null) {
            textClassifierProvider.classify(text, tcKinds)
                .filter { it.kind in tcKinds && it.text.isNotEmpty() }
                .groupBy { it.kind }
        } else {
            emptyMap()
        }

        val matches = mutableListOf<ExtractedSpan>()
        for (kind in ClipboardSuggestionKind.entries) {
            when (config.modeFor(kind)) {
                ClipboardExtractMode.Disabled -> Unit
                ClipboardExtractMode.TextClassifierOnly -> {
                    tcByKind[kind]?.let { matches.addAll(it) }
                }
                ClipboardExtractMode.RegexOnly -> {
                    matches.addAll(extractWithRegex(text, kind))
                }
                ClipboardExtractMode.TextClassifierWithFallback -> {
                    val tcMatches = tcByKind[kind]
                    if (!tcMatches.isNullOrEmpty()) {
                        matches.addAll(tcMatches)
                    } else {
                        matches.addAll(extractWithRegex(text, kind))
                    }
                }
            }
        }
        if (matches.isEmpty()) return emptyList()

        val original = text.trim()
        val results = mutableListOf<ClipboardSuggestion>()
        val seen = HashSet<String>()
        var maxClaimedEnd = -1
        for (match in matches.sortedWith(
            compareBy<ExtractedSpan> { it.start }
                .thenByDescending { it.endExclusive }
                .thenBy { it.kind.ordinal }
        )) {
            if (match.start < maxClaimedEnd) continue
            // Always claim the span before dedup/original checks so inner submatches
            // (e.g. a phone number inside a copied or duplicated URL) never leak out.
            maxClaimedEnd = match.endExclusive
            if (match.text == original) continue
            if (!seen.add(match.text)) continue
            results.add(ClipboardSuggestion(match.kind, match.text))
            if (results.size >= limit) break
        }
        return results
    }

    private fun extractWithRegex(text: String, kind: ClipboardSuggestionKind): List<ExtractedSpan> {
        val patterns = regexPatternsByKind[kind] ?: return emptyList()
        val found = mutableListOf<ExtractedSpan>()
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val value = if (kind == ClipboardSuggestionKind.Link) {
                    match.value.stripTrailingPunctuation()
                } else match.value
                if (value.isEmpty()) continue
                if (kind == ClipboardSuggestionKind.Phone && value.count(Char::isDigit) !in 8..15) continue
                if (kind == ClipboardSuggestionKind.Email && !isValidEmailLocalPart(value)) continue
                found.add(ExtractedSpan(match.range.first, match.range.first + value.length, kind, value))
            }
        }
        return found
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun classifyWithAndroid(
        context: Context,
        text: String,
        kinds: Set<ClipboardSuggestionKind>
    ): List<ExtractedSpan> {
        val manager = context.getSystemService(TextClassificationManager::class.java) ?: return emptyList()
        val classifier = manager.textClassifier
        if (text.length > classifier.maxGenerateLinksTextLength) return emptyList()

        val entityToKind = buildMap {
            if (ClipboardSuggestionKind.Link in kinds) put(TextClassifier.TYPE_URL, ClipboardSuggestionKind.Link)
            if (ClipboardSuggestionKind.Phone in kinds) put(TextClassifier.TYPE_PHONE, ClipboardSuggestionKind.Phone)
            if (ClipboardSuggestionKind.Email in kinds) put(TextClassifier.TYPE_EMAIL, ClipboardSuggestionKind.Email)
            if (ClipboardSuggestionKind.Address in kinds) put(TextClassifier.TYPE_ADDRESS, ClipboardSuggestionKind.Address)
            if (ClipboardSuggestionKind.Otp in kinds) put(ENTITY_TYPE_OTP, ClipboardSuggestionKind.Otp)
        }
        if (entityToKind.isEmpty()) return emptyList()

        val entityConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TextClassifier.EntityConfig.Builder()
                .setIncludedTypes(entityToKind.keys)
                .includeTypesFromTextClassifier(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            TextClassifier.EntityConfig.createWithExplicitEntityList(entityToKind.keys)
        }

        val textLinks = try {
            classifier.generateLinks(
                TextLinks.Request.Builder(text)
                    .setEntityConfig(entityConfig)
                    .build()
            )
        } catch (t: Throwable) {
            Timber.w(t, "TextClassifier.generateLinks failed")
            return emptyList()
        }

        val spans = mutableListOf<ExtractedSpan>()
        for (link in textLinks.links) {
            if (link.entityCount <= 0) continue
            val topEntity = link.getEntity(0)
            val kind = entityToKind[topEntity] ?: continue
            val rawStart = link.start
            val rawEnd = link.end
            if (rawStart < 0 || rawEnd > text.length || rawStart >= rawEnd) continue
            val rawSlice = text.substring(rawStart, rawEnd)
            val leadingWs = rawSlice.indexOfFirst { !it.isWhitespace() }
            if (leadingWs < 0) continue
            val trimmed = rawSlice.trim()
            val value = if (kind == ClipboardSuggestionKind.Link) {
                trimmed.stripTrailingPunctuation()
            } else {
                trimmed
            }
            if (value.isEmpty()) continue
            val start = rawStart + leadingWs
            spans.add(ExtractedSpan(start, start + value.length, kind, value))
        }
        Timber.d("TextClassifier extracted %d spans for %s", spans.size, kinds)
        return spans
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
