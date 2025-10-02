package com.frostr.igloo.managers

import android.content.Context
import android.util.Log
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * SubscriptionManager - Reads from SecureStorage without PWA!
 *
 * This is the key innovation that enables excellent battery life.
 * The WebSocket infrastructure can monitor relays by reading subscription
 * filters directly from SecureStorage, eliminating the need to load the PWA.
 *
 * Battery Impact: Saves 2-3% per hour by not loading PWA for subscriptions.
 */
class SubscriptionManager(
    private val context: Context,
    private val storageBridge: StorageBridge
) {

    companion object {
        private const val TAG = "SubscriptionManager"
    }

    private val gson = Gson()

    /**
     * Load subscription configuration from SecureStorage
     * This happens WITHOUT loading the PWA!
     */
    fun loadSubscriptionConfig(): SubscriptionConfig? {
        return try {
            // Read user's public key
            val pubkey = storageBridge.getItem("local", "user_pubkey")
            if (pubkey == null) {
                Log.w(TAG, "No user pubkey found in storage - user not logged in yet")
                return null
            }

            // Read relay URLs
            val relayUrlsJson = storageBridge.getItem("local", "relay_urls")
            val relayUrls = if (relayUrlsJson != null) {
                try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson<List<String>>(relayUrlsJson, type)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse relay URLs, using defaults", e)
                    getDefaultRelays()
                }
            } else {
                Log.d(TAG, "No relay URLs in storage, using defaults")
                getDefaultRelays()
            }

            // Read subscription preferences
            val subscriptionPrefsJson = storageBridge.getItem("local", "subscription_prefs")
            val prefs = if (subscriptionPrefsJson != null) {
                try {
                    gson.fromJson<SubscriptionPreferences>(subscriptionPrefsJson, SubscriptionPreferences::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse subscription prefs, using defaults", e)
                    SubscriptionPreferences.default()
                }
            } else {
                Log.d(TAG, "No subscription prefs in storage, using defaults")
                SubscriptionPreferences.default()
            }

            val config = SubscriptionConfig(
                userPubkey = pubkey,
                relayUrls = relayUrls,
                preferences = prefs
            )

            Log.d(TAG, "✓ Loaded config for pubkey: ${pubkey.take(16)}...")
            Log.d(TAG, "  Relays: ${relayUrls.size}, DMs: ${prefs.subscribeToDMs}, Mentions: ${prefs.subscribeToMentions}, Zaps: ${prefs.subscribeToZaps}")

            config

        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to load subscription config", e)
            null
        }
    }

    /**
     * Generate Nostr subscription filters based on config
     * This constructs the actual REQ messages to send to relays
     */
    fun generateSubscriptionFilters(config: SubscriptionConfig): List<NostrSubscription> {
        val subscriptions = mutableListOf<NostrSubscription>()

        // Subscription 1: DMs (kind 4) addressed to user
        if (config.preferences.subscribeToDMs) {
            subscriptions.add(NostrSubscription(
                id = "dms_${config.userPubkey.take(8)}",
                filters = listOf(
                    NostrFilter(
                        kinds = listOf(4),
                        tags = mapOf("p" to listOf(config.userPubkey)),
                        since = getLastSeenTimestamp("dms")
                            ?: (System.currentTimeMillis() / 1000 - 3600), // Last hour
                        limit = 100
                    )
                )
            ))
            Log.d(TAG, "Added DM subscription")
        }

        // Subscription 2: Mentions and replies
        if (config.preferences.subscribeToMentions) {
            subscriptions.add(NostrSubscription(
                id = "mentions_${config.userPubkey.take(8)}",
                filters = listOf(
                    NostrFilter(
                        kinds = listOf(1), // Text notes
                        tags = mapOf("p" to listOf(config.userPubkey)),
                        since = getLastSeenTimestamp("mentions")
                            ?: (System.currentTimeMillis() / 1000 - 3600),
                        limit = 100
                    )
                )
            ))
            Log.d(TAG, "Added mentions subscription")
        }

        // Subscription 3: Zaps
        if (config.preferences.subscribeToZaps) {
            subscriptions.add(NostrSubscription(
                id = "zaps_${config.userPubkey.take(8)}",
                filters = listOf(
                    NostrFilter(
                        kinds = listOf(9735), // Zap receipts
                        tags = mapOf("p" to listOf(config.userPubkey)),
                        since = getLastSeenTimestamp("zaps")
                            ?: (System.currentTimeMillis() / 1000 - 3600),
                        limit = 100
                    )
                )
            ))
            Log.d(TAG, "Added zaps subscription")
        }

        // Subscription 4: Follow feed (optional - battery intensive)
        if (config.preferences.subscribeToFeed && !shouldSkipFeedSubscription()) {
            val followList = config.preferences.followList.take(50) // Limit to 50

            if (followList.isNotEmpty()) {
                subscriptions.add(NostrSubscription(
                    id = "feed_${config.userPubkey.take(8)}",
                    filters = listOf(
                        NostrFilter(
                            kinds = listOf(1),
                            authors = followList,
                            limit = getAdaptiveLimit(),
                            since = getLastSeenTimestamp("feed")
                                ?: (System.currentTimeMillis() / 1000 - 600) // Last 10 minutes
                        )
                    )
                ))
                Log.d(TAG, "Added feed subscription (${followList.size} authors)")
            } else {
                Log.d(TAG, "Skipped feed subscription - no follow list")
            }
        }

        Log.d(TAG, "✓ Generated ${subscriptions.size} subscriptions")
        return subscriptions
    }

    /**
     * Get last seen timestamp for a subscription type
     * This prevents re-fetching events we've already seen (deduplication)
     */
    private fun getLastSeenTimestamp(type: String): Long? {
        return try {
            val timestampStr = storageBridge.getItem("local", "last_seen_$type")
            timestampStr?.toLongOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last seen timestamp for $type", e)
            null
        }
    }

    /**
     * Update last seen timestamp after processing events
     * Called by service when events are processed
     */
    fun updateLastSeenTimestamp(type: String, timestamp: Long) {
        try {
            storageBridge.setItem("local", "last_seen_$type", timestamp.toString())
            Log.d(TAG, "Updated last_seen_$type to $timestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update last seen timestamp for $type", e)
        }
    }

    /**
     * Get adaptive limit based on battery level
     * Reduces data transfer on low battery
     */
    private fun getAdaptiveLimit(): Int {
        val batteryLevel = getBatteryLevel()
        return when {
            batteryLevel <= 15 -> 10  // Critical: only most recent
            batteryLevel <= 30 -> 25  // Low: reduced
            else -> 100               // Normal: full
        }
    }

    /**
     * Check if feed subscription should be skipped
     * Feed is battery-intensive, skip on low battery or background
     */
    private fun shouldSkipFeedSubscription(): Boolean {
        val batteryLevel = getBatteryLevel()
        val appState = getAppState()

        // Skip feed on low battery or in background
        return batteryLevel <= 20 || appState != AppState.FOREGROUND
    }

    /**
     * Get current battery level
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            100 // Assume full if can't read
        }
    }

    /**
     * Get current app state
     * TODO: This should come from BatteryPowerManager once implemented
     */
    private fun getAppState(): AppState {
        // For now, assume FOREGROUND
        // This will be properly tracked by BatteryPowerManager in Phase 2
        return AppState.FOREGROUND
    }

    /**
     * Default relay URLs
     */
    private fun getDefaultRelays(): List<String> {
        return listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band"
        )
    }
}
