package com.frostr.igloo.managers

import android.content.Context
import android.util.Log
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.models.EventPriority
import com.frostr.igloo.models.NostrEvent
import com.frostr.igloo.models.QueuedNostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NostrEventHandler - Processes batched events and decides on PWA wake-up
 *
 * This is the decision-making component that determines whether to:
 * 1. Wake up the PWA to process events
 * 2. Queue events for later processing
 * 3. Handle events directly in native code (notifications, etc.)
 *
 * Key Responsibilities:
 * - Analyze event batches and determine action
 * - Generate notifications for high-priority events
 * - Store events in SecureStorage for PWA access when it wakes
 * - Coordinate PWA wake-up through service callback
 * - Track processing statistics
 *
 * Battery Impact: Minimizes PWA wake-ups through intelligent decision logic
 */
class NostrEventHandler(
    private val context: Context,
    private val storageBridge: StorageBridge,
    private val batteryPowerManager: BatteryPowerManager,
    private val onShouldWakePWA: (reason: String, eventCount: Int) -> Unit
) {

    companion object {
        private const val TAG = "NostrEventHandler"

        // Storage keys
        private const val STORAGE_PENDING_EVENTS_KEY = "pending_nostr_events"
        private const val STORAGE_EVENT_STATS_KEY = "event_processing_stats"

        // Wake-up thresholds
        private const val WAKE_THRESHOLD_HIGH_PRIORITY = 1 // Wake immediately for any HIGH priority
        private const val WAKE_THRESHOLD_NORMAL_BATCH = 10 // Wake for 10+ NORMAL priority events
        private const val WAKE_THRESHOLD_LOW_BATCH = 50 // Wake for 50+ LOW priority events
    }

    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Statistics
    private var eventsProcessed = 0
    private var batchesProcessed = 0
    private var pwaWakeUps = 0

    /**
     * Process a batch of events
     * This is called by NostrEventQueue when a batch is ready
     */
    fun processBatch(events: List<QueuedNostrEvent>) {
        if (events.isEmpty()) {
            Log.d(TAG, "Empty batch, skipping")
            return
        }

        Log.d(TAG, "Processing batch of ${events.size} events...")

        handlerScope.launch {
            try {
                // Store events in SecureStorage for PWA access
                storeEventsForPWA(events)

                // Update statistics
                eventsProcessed += events.size
                batchesProcessed++

                // Analyze batch and decide on action
                val decision = analyzeAndDecide(events)

                Log.d(TAG, "Batch decision: ${decision.action} (reason: ${decision.reason})")

                // Execute decision
                when (decision.action) {
                    BatchAction.WAKE_PWA -> {
                        pwaWakeUps++
                        onShouldWakePWA(decision.reason, events.size)
                    }
                    BatchAction.GENERATE_NOTIFICATIONS -> {
                        generateNotifications(events)
                    }
                    BatchAction.QUEUE_FOR_LATER -> {
                        Log.d(TAG, "Events queued for later processing (${events.size} events)")
                    }
                }

                // Save statistics
                saveStatistics()

            } catch (e: Exception) {
                Log.e(TAG, "Error processing batch", e)
            }
        }
    }

    /**
     * Get processing statistics
     */
    fun getStatistics(): ProcessingStatistics {
        return ProcessingStatistics(
            eventsProcessed = eventsProcessed,
            batchesProcessed = batchesProcessed,
            pwaWakeUps = pwaWakeUps,
            averageBatchSize = if (batchesProcessed > 0) eventsProcessed.toFloat() / batchesProcessed else 0f,
            wakeUpRate = if (batchesProcessed > 0) pwaWakeUps.toFloat() / batchesProcessed else 0f
        )
    }

    /**
     * Clear all pending events from storage
     */
    fun clearPendingEvents() {
        try {
            storageBridge.setItem("local", STORAGE_PENDING_EVENTS_KEY, "[]")
            Log.d(TAG, "Cleared pending events")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear pending events", e)
        }
    }

    // ============================================================================
    // Private Methods
    // ============================================================================

    /**
     * Store events in SecureStorage for PWA to access
     */
    private fun storeEventsForPWA(events: List<QueuedNostrEvent>) {
        try {
            // Read existing events
            val existingJson = storageBridge.getItem("local", STORAGE_PENDING_EVENTS_KEY) ?: "[]"
            val existingEvents = parseEventsList(existingJson)

            // Add new events
            val allEvents = existingEvents + events

            // Keep only most recent 200 events to prevent storage bloat
            val eventsToStore = allEvents.takeLast(200)

            // Serialize and store
            val json = serializeEventsList(eventsToStore)
            storageBridge.setItem("local", STORAGE_PENDING_EVENTS_KEY, json)

            Log.d(TAG, "✓ Stored ${events.size} events in SecureStorage (total: ${eventsToStore.size})")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to store events in SecureStorage", e)
        }
    }

    /**
     * Analyze batch and decide on action
     */
    private fun analyzeAndDecide(events: List<QueuedNostrEvent>): BatchDecision {
        val highPriorityCount = events.count { it.priority == EventPriority.HIGH }
        val normalPriorityCount = events.count { it.priority == EventPriority.NORMAL }
        val lowPriorityCount = events.count { it.priority == EventPriority.LOW }

        val currentAppState = batteryPowerManager.getCurrentAppState()
        val batteryLevel = batteryPowerManager.getCurrentBatteryLevel()
        val isCharging = batteryPowerManager.isCharging()

        // Decision logic

        // 1. HIGH priority events: Always wake PWA (DMs, zaps, mentions)
        if (highPriorityCount >= WAKE_THRESHOLD_HIGH_PRIORITY) {
            return BatchDecision(
                action = BatchAction.WAKE_PWA,
                reason = "High priority events: $highPriorityCount (DMs/zaps/mentions)"
            )
        }

        // 2. Large NORMAL priority batch: Wake PWA if conditions are good
        if (normalPriorityCount >= WAKE_THRESHOLD_NORMAL_BATCH) {
            // Check if we should wake based on battery/state
            val shouldWake = when {
                isCharging -> true // Always wake when charging
                batteryLevel <= 15 -> false // Don't wake on critical battery
                batteryLevel <= 30 -> normalPriorityCount >= 20 // Higher threshold on low battery
                else -> true
            }

            if (shouldWake) {
                return BatchDecision(
                    action = BatchAction.WAKE_PWA,
                    reason = "Normal priority batch: $normalPriorityCount events"
                )
            }
        }

        // 3. Large LOW priority batch: Only wake if battery is good
        if (lowPriorityCount >= WAKE_THRESHOLD_LOW_BATCH) {
            val shouldWake = when {
                isCharging && batteryLevel > 50 -> true
                !isCharging && batteryLevel > 80 -> true
                else -> false
            }

            if (shouldWake) {
                return BatchDecision(
                    action = BatchAction.WAKE_PWA,
                    reason = "Low priority batch: $lowPriorityCount events (good battery)"
                )
            }
        }

        // 4. Mixed batch with some HIGH priority: Generate notifications instead of wake
        if (highPriorityCount > 0 && batteryLevel <= 20) {
            return BatchDecision(
                action = BatchAction.GENERATE_NOTIFICATIONS,
                reason = "High priority events but low battery: generating notifications only"
            )
        }

        // 5. Default: Queue for later (PWA will process when user opens app)
        return BatchDecision(
            action = BatchAction.QUEUE_FOR_LATER,
            reason = "Small batch or poor battery conditions: queued for later"
        )
    }

    /**
     * Generate notifications for high-priority events
     */
    private fun generateNotifications(events: List<QueuedNostrEvent>) {
        val highPriorityEvents = events.filter { it.priority == EventPriority.HIGH }

        highPriorityEvents.forEach { queuedEvent ->
            val event = queuedEvent.event

            when (event.kind) {
                4 -> {
                    // DM notification
                    Log.d(TAG, "TODO: Generate DM notification for event ${event.id.take(8)}...")
                    // TODO: Use NotificationManager to show DM notification
                }
                9735 -> {
                    // Zap notification
                    Log.d(TAG, "TODO: Generate Zap notification for event ${event.id.take(8)}...")
                    // TODO: Use NotificationManager to show Zap notification
                }
                1 -> {
                    // Mention notification
                    Log.d(TAG, "TODO: Generate mention notification for event ${event.id.take(8)}...")
                    // TODO: Use NotificationManager to show mention notification
                }
            }
        }

        Log.d(TAG, "Generated ${highPriorityEvents.size} notifications")
    }

    /**
     * Save processing statistics to storage
     */
    private fun saveStatistics() {
        try {
            val stats = getStatistics()
            val json = serializeStatistics(stats)
            storageBridge.setItem("local", STORAGE_EVENT_STATS_KEY, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save statistics", e)
        }
    }

    /**
     * Parse events list from JSON
     */
    private fun parseEventsList(json: String): List<QueuedNostrEvent> {
        return try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<QueuedNostrEvent>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse events list", e)
            emptyList()
        }
    }

    /**
     * Serialize events list to JSON
     */
    private fun serializeEventsList(events: List<QueuedNostrEvent>): String {
        return try {
            val gson = com.google.gson.Gson()
            gson.toJson(events)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize events list", e)
            "[]"
        }
    }

    /**
     * Serialize statistics to JSON
     */
    private fun serializeStatistics(stats: ProcessingStatistics): String {
        return """
        {
            "eventsProcessed": ${stats.eventsProcessed},
            "batchesProcessed": ${stats.batchesProcessed},
            "pwaWakeUps": ${stats.pwaWakeUps},
            "averageBatchSize": ${stats.averageBatchSize},
            "wakeUpRate": ${stats.wakeUpRate}
        }
        """.trimIndent()
    }
}

/**
 * Batch decision result
 */
private data class BatchDecision(
    val action: BatchAction,
    val reason: String
)

/**
 * Possible batch actions
 */
private enum class BatchAction {
    WAKE_PWA,               // Wake up PWA to process events
    GENERATE_NOTIFICATIONS, // Generate native notifications only
    QUEUE_FOR_LATER        // Keep events queued, process when user opens app
}

/**
 * Processing statistics
 */
data class ProcessingStatistics(
    val eventsProcessed: Int,
    val batchesProcessed: Int,
    val pwaWakeUps: Int,
    val averageBatchSize: Float,
    val wakeUpRate: Float // PWA wake-ups per batch
)
