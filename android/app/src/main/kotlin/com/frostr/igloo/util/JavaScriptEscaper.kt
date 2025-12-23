package com.frostr.igloo.util

/**
 * Centralized JavaScript string escaping utility.
 *
 * Prevents XSS/injection when embedding data in JavaScript strings.
 * All bridges MUST use this instead of inline escaping.
 *
 * Security considerations:
 * - Backslash MUST be escaped first to avoid double-escaping
 * - Null bytes are removed (can cause issues in JS strings)
 * - Unicode is preserved (no escaping needed for valid UTF-8)
 */
object JavaScriptEscaper {

    /**
     * Escape a string for safe embedding in JavaScript single-quoted strings.
     *
     * Example: escape("hello'world") -> "hello\\'world"
     *
     * @param str The string to escape
     * @return The escaped string (without surrounding quotes)
     */
    fun escape(str: String): String = str
        .replace("\\", "\\\\")    // Backslash first! (before other escapes add backslashes)
        .replace("'", "\\'")      // Single quote
        .replace("\"", "\\\"")    // Double quote
        .replace("\n", "\\n")     // Newline
        .replace("\r", "\\r")     // Carriage return
        .replace("\t", "\\t")     // Tab
        .replace("\b", "\\b")     // Backspace
        .replace("\u000c", "\\f") // Form feed
        .replace("\u0000", "")    // Null byte (remove - can cause issues)

    /**
     * Escape and wrap in single quotes for direct embedding.
     *
     * Example: quote("hello") -> "'hello'"
     *
     * @param str The string to escape and quote
     * @return The escaped string wrapped in single quotes
     */
    fun quote(str: String): String = "'${escape(str)}'"

    /**
     * Escape and wrap in double quotes for direct embedding.
     *
     * Example: doubleQuote("hello") -> "\"hello\""
     *
     * @param str The string to escape and quote
     * @return The escaped string wrapped in double quotes
     */
    fun doubleQuote(str: String): String = "\"${escape(str)}\""
}
