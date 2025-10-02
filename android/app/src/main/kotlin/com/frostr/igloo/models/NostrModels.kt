package com.frostr.igloo.models

import com.google.gson.Gson

/**
 * Nostr data models for subscription management
 */

/**
 * Subscription configuration loaded from SecureStorage
 */
data class SubscriptionConfig(
    val userPubkey: String,
    val relayUrls: List<String>,
    val preferences: SubscriptionPreferences
)

/**
 * User's subscription preferences (stored in SecureStorage)
 */
data class SubscriptionPreferences(
    val subscribeToDMs: Boolean = true,
    val subscribeToMentions: Boolean = true,
    val subscribeToZaps: Boolean = true,
    val subscribeToFeed: Boolean = false, // Off by default - battery intensive
    val feedOnlyInForeground: Boolean = true,
    val followList: List<String> = emptyList()
) {
    companion object {
        fun default() = SubscriptionPreferences()
    }
}

/**
 * Nostr subscription with filters
 */
data class NostrSubscription(
    val id: String,
    val filters: List<NostrFilter>
) {
    fun toREQMessage(): String {
        val gson = Gson()
        val filtersJson = filters.joinToString(",") { it.toJson() }
        return """["REQ","$id",$filtersJson]"""
    }
}

/**
 * Nostr filter for subscription
 */
data class NostrFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val tags: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null
) {
    fun toJson(): String {
        val gson = Gson()
        val json = mutableMapOf<String, Any>()

        kinds?.let { json["kinds"] = it }
        authors?.let { json["authors"] = it }
        tags?.forEach { (tag, values) -> json["#$tag"] = values }
        since?.let { json["since"] = it }
        until?.let { json["until"] = it }
        limit?.let { json["limit"] = it }

        return gson.toJson(json)
    }
}

/**
 * Nostr event (simplified for relay communication)
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
)

/**
 * Event priority for wake logic
 */
enum class EventPriority {
    HIGH,      // DMs, zaps, mentions - wake PWA immediately
    NORMAL,    // Regular posts, reactions - batch process
    LOW        // Background updates - process when convenient
}

/**
 * Queued event with metadata
 */
data class QueuedNostrEvent(
    val event: NostrEvent,
    val relayUrl: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val priority: EventPriority = EventPriority.NORMAL
)

/**
 * App state for battery management
 */
enum class AppState {
    FOREGROUND,    // App actively used
    BACKGROUND,    // App in background but not in doze
    DOZE,          // Device in doze mode
    RARE,          // App in rare standby bucket
    RESTRICTED     // App in restricted standby bucket
}

/**
 * Connection state for relay management
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/**
 * Connection health info
 */
data class ConnectionHealth(
    val state: ConnectionState,
    val subscriptionConfirmed: Boolean,
    val lastMessageAge: Long,
    val reconnectAttempts: Int
)
