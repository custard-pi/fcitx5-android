/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.clipboard.ClipboardSuggestion
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSuggestionExtractor
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardSuggestionExtractorTest {

    @Test
    fun `extracts urls emails and phones adjacent to Chinese text in appearance order`() {
        val text = "请打开https://example.com/path?a=1查看，联系我test@example.com谢谢，手机号13800138000"
        assertEquals(
            listOf(
                ClipboardSuggestion(ClipboardSuggestionKind.Link, "https://example.com/path?a=1"),
                ClipboardSuggestion(ClipboardSuggestionKind.Email, "test@example.com"),
                ClipboardSuggestion(ClipboardSuggestionKind.Phone, "13800138000")
            ),
            ClipboardSuggestionExtractor.extract(text)
        )
    }

    @Test
    fun `does not misclassify Chinese lists filenames or package names as links`() {
        val samples = listOf(
            "1.首先打开设置",
            "文件MainActivity.kt已修改，请查看README.md",
            "包名 org.fcitx.fcitx5.android 版本 v1.2.rc",
            "验证码123456，5分钟内有效",
            "订单号138001380001234"
        )
        for (sample in samples) {
            assertEquals("Unexpected match for: $sample", emptyList<ClipboardSuggestion>(), ClipboardSuggestionExtractor.extract(sample))
        }
    }

    @Test
    fun `extracts bare domains and balanced parentheses in urls`() {
        val text = "官网 www.fcitx-im.org 或 github.com/fcitx5-android/fcitx5-android (https://en.wikipedia.org/wiki/Kotlin_(programming_language))."
        assertEquals(
            listOf(
                ClipboardSuggestion(ClipboardSuggestionKind.Link, "www.fcitx-im.org"),
                ClipboardSuggestion(ClipboardSuggestionKind.Link, "github.com/fcitx5-android/fcitx5-android"),
                ClipboardSuggestion(ClipboardSuggestionKind.Link, "https://en.wikipedia.org/wiki/Kotlin_(programming_language)")
            ),
            ClipboardSuggestionExtractor.extract(text)
        )
    }

    @Test
    fun `extracts Chinese mobile landline 400 and international phones`() {
        val text = "手机+86 138-0013-8000，座机010-12345678，客服400-123-4567，海外+1 (415) 555-0199"
        assertEquals(
            listOf(
                ClipboardSuggestion(ClipboardSuggestionKind.Phone, "+86 138-0013-8000"),
                ClipboardSuggestion(ClipboardSuggestionKind.Phone, "010-12345678"),
                ClipboardSuggestion(ClipboardSuggestionKind.Phone, "400-123-4567"),
                ClipboardSuggestion(ClipboardSuggestionKind.Phone, "+1 (415) 555-0199")
            ),
            ClipboardSuggestionExtractor.extract(text)
        )
    }

    @Test
    fun `preserves appearance order across link phone email and limits to 8`() {
        val text = buildString {
            append("a@example.com 13800138001 https://one.example.com ")
            append("b@example.com 13800138002 https://two.example.com ")
            append("c@example.com 13800138003 https://three.example.com d@example.com")
        }
        assertEquals(
            listOf(
                ClipboardSuggestion(ClipboardSuggestionKind.Email, "a@example.com"),
                ClipboardSuggestion(ClipboardSuggestionKind.Phone, "13800138001"),
                ClipboardSuggestion(ClipboardSuggestionKind.Link, "https://one.example.com"),
                ClipboardSuggestion(ClipboardSuggestionKind.Email, "b@example.com"),
                ClipboardSuggestion(ClipboardSuggestionKind.Phone, "13800138002"),
                ClipboardSuggestion(ClipboardSuggestionKind.Link, "https://two.example.com"),
                ClipboardSuggestion(ClipboardSuggestionKind.Email, "c@example.com"),
                ClipboardSuggestion(ClipboardSuggestionKind.Phone, "13800138003")
            ),
            ClipboardSuggestionExtractor.extract(text)
        )
    }

    @Test
    fun `skips exact original item and prevents inner phone leak on duplicated urls`() {
        assertEquals(
            emptyList<ClipboardSuggestion>(),
            ClipboardSuggestionExtractor.extract("  https://example.com/13800138000  ")
        )
        assertEquals(
            listOf(ClipboardSuggestion(ClipboardSuggestionKind.Link, "https://example.com/13800138000")),
            ClipboardSuggestionExtractor.extract("链接1 https://example.com/13800138000 链接2 https://example.com/13800138000")
        )
    }

    @Test
    fun `does not match email domain as bare url`() {
        val text = "Contact user.name@sub.example.com for help."
        assertEquals(
            listOf(ClipboardSuggestion(ClipboardSuggestionKind.Email, "user.name@sub.example.com")),
            ClipboardSuggestionExtractor.extract(text)
        )
    }
}
