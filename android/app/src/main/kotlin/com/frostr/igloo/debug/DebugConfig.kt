package com.frostr.igloo.debug

/**
 * Debug Configuration
 *
 * Centralized configuration for debug features including NIP-55 pipeline logging.
 * Debug features are automatically disabled in release builds via ProGuard rules.
 *
 * Features:
 * - NIP-55 pipeline debug logging
 * - Intent flow tracing
 * - IPC communication monitoring
 * - Timing and performance metrics
 * - Permission system debugging
 * - PWA bridge interaction logging
 */
object DebugConfig {

    /**
     * Master debug flag - controls all debug features
     * Set to true for debug builds, false for release builds (ProGuard will optimize out)
     */
    const val DEBUG_ENABLED = true

    /**
     * NIP-55 specific debug logging
     * Traces the complete NIP-55 request/response pipeline
     */
    const val NIP55_LOGGING = true

    /**
     * Intent system debugging
     * Logs intent creation, forwarding, and result handling
     */
    const val INTENT_LOGGING = true

    /**
     * IPC communication debugging
     * Monitors HTTP IPC between processes and PWA bridge calls
     */
    const val IPC_LOGGING = true

    /**
     * Performance timing logging
     * Measures request processing times and identifies bottlenecks
     */
    const val TIMING_LOGGING = true

    /**
     * Permission system debugging
     * Tracks permission checks, auto-approval rules, and user decisions
     */
    const val PERMISSION_LOGGING = true

    /**
     * PWA bridge interaction logging
     * Monitors JavaScript interface calls and WebView communication
     */
    const val PWA_BRIDGE_LOGGING = true

    /**
     * Signing operation debugging
     * Traces cryptographic operations and result handling
     */
    const val SIGNING_LOGGING = true

    /**
     * Error and exception debugging
     * Enhanced error logging with stack traces and context
     */
    const val ERROR_LOGGING = true

    /**
     * Flow state debugging
     * Tracks request lifecycle from start to completion
     */
    const val FLOW_LOGGING = true

    /**
     * Verbose mode - includes detailed parameter logging
     * WARNING: May log sensitive data in debug builds
     */
    const val VERBOSE_LOGGING = false
}