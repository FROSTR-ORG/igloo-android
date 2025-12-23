package com.frostr.igloo.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for JavaScriptEscaper utility.
 *
 * These tests verify that all special characters are properly escaped
 * for safe embedding in JavaScript strings.
 */
class JavaScriptEscaperTest {

    @Test
    fun `escapes backslash`() {
        val input = "path\\to\\file"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("path\\\\to\\\\file")
    }

    @Test
    fun `escapes single quote`() {
        val input = "it's a test"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("it\\'s a test")
    }

    @Test
    fun `escapes double quote`() {
        val input = "say \"hello\""
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("say \\\"hello\\\"")
    }

    @Test
    fun `escapes newline`() {
        val input = "line1\nline2"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("line1\\nline2")
    }

    @Test
    fun `escapes carriage return`() {
        val input = "line1\rline2"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("line1\\rline2")
    }

    @Test
    fun `escapes tab`() {
        val input = "col1\tcol2"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("col1\\tcol2")
    }

    @Test
    fun `escapes backspace`() {
        val input = "back\bspace"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("back\\bspace")
    }

    @Test
    fun `escapes form feed`() {
        val input = "form\u000cfeed"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("form\\ffeed")
    }

    @Test
    fun `removes null bytes`() {
        val input = "null\u0000byte"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("nullbyte")
    }

    @Test
    fun `handles empty string`() {
        val input = ""
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("")
    }

    @Test
    fun `handles unicode`() {
        val input = "emoji: \uD83D\uDE00 and japanese: \u3042"
        val result = JavaScriptEscaper.escape(input)
        // Unicode should be preserved, not escaped
        assertThat(result).isEqualTo("emoji: \uD83D\uDE00 and japanese: \u3042")
    }

    @Test
    fun `escapes XSS attempt - single quote injection`() {
        // Attempt to break out of single-quoted string
        val input = "'; alert('xss'); '"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("\\'; alert(\\'xss\\'); \\'")
    }

    @Test
    fun `escapes XSS attempt - script tag`() {
        val input = "<script>alert('xss')</script>"
        val result = JavaScriptEscaper.escape(input)
        // Script tags themselves are not a JS string escape issue,
        // but quotes inside should be escaped
        assertThat(result).isEqualTo("<script>alert(\\'xss\\')</script>")
    }

    @Test
    fun `quote wraps in single quotes`() {
        val input = "hello"
        val result = JavaScriptEscaper.quote(input)
        assertThat(result).isEqualTo("'hello'")
    }

    @Test
    fun `quote escapes content before wrapping`() {
        val input = "it's \"quoted\""
        val result = JavaScriptEscaper.quote(input)
        assertThat(result).isEqualTo("'it\\'s \\\"quoted\\\"'")
    }

    @Test
    fun `doubleQuote wraps in double quotes`() {
        val input = "hello"
        val result = JavaScriptEscaper.doubleQuote(input)
        assertThat(result).isEqualTo("\"hello\"")
    }

    @Test
    fun `escapes backslash before other characters`() {
        // Critical: backslash must be escaped FIRST
        // Otherwise "'" becomes "\'" which then becomes "\\'" (wrong!)
        val input = "\\'"
        val result = JavaScriptEscaper.escape(input)
        // Should be: \\ (escaped backslash) + \' (escaped quote)
        assertThat(result).isEqualTo("\\\\\\'")
    }

    @Test
    fun `handles complex mixed content`() {
        val input = "Event: {\"id\": \"abc\", \"content\": \"Hello\\nWorld\"}"
        val result = JavaScriptEscaper.escape(input)
        assertThat(result).isEqualTo("Event: {\\\"id\\\": \\\"abc\\\", \\\"content\\\": \\\"Hello\\\\nWorld\\\"}")
    }
}
