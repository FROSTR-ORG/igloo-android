package com.frostr.igloo.constants

/**
 * Constants for Nostr event kinds.
 *
 * See: https://github.com/nostr-protocol/nips for NIP definitions.
 */
object NostrEventKinds {

    // Core event kinds (NIP-01)
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val RECOMMEND_RELAY = 2
    const val CONTACTS = 3
    const val ENCRYPTED_DM = 4  // NIP-04 DM
    const val EVENT_DELETION = 5
    const val REPOST = 6
    const val REACTION = 7
    const val BADGE_AWARD = 8

    // Relay authentication (NIP-42)
    const val RELAY_AUTH = 22242

    // Wallet (NIP-47 NWC)
    const val WALLET_INFO = 23194
    const val WALLET_REQUEST = 23195

    // Nostr Connect (NIP-46)
    const val NOSTR_CONNECT = 24133

    // NWC Request
    const val NWC_REQUEST = 27235

    // Long-form content (NIP-23)
    const val LONG_FORM_CONTENT = 30023

    // Application-specific data (NIP-78)
    const val APP_DATA = 30078

    /**
     * Get a human-readable name for an event kind.
     *
     * @param kind The event kind number
     * @return A human-readable description
     */
    fun getDisplayName(kind: Int): String {
        return when (kind) {
            METADATA -> "Metadata"
            TEXT_NOTE -> "Text Note"
            RECOMMEND_RELAY -> "Relay Recommendation"
            CONTACTS -> "Contacts"
            ENCRYPTED_DM -> "Direct Message"
            EVENT_DELETION -> "Event Deletion"
            REPOST -> "Repost"
            REACTION -> "Reaction"
            BADGE_AWARD -> "Badge Award"
            RELAY_AUTH -> "Relay Auth"
            WALLET_INFO -> "Wallet Info"
            WALLET_REQUEST -> "Wallet Request"
            NOSTR_CONNECT -> "Nostr Connect"
            NWC_REQUEST -> "NWC Request"
            LONG_FORM_CONTENT -> "Long-form Content"
            APP_DATA -> "App Data"
            else -> "Kind $kind"
        }
    }
}
