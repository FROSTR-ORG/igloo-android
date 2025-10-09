package com.frostr.igloo

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Singleton bridge for communicating NIP-55 requests between InvisibleNIP55Handler and MainActivity
 * This solves the cross-task communication issue by queuing requests until MainActivity is ready
 */
object NIP55RequestBridge {
    private const val TAG = "NIP55RequestBridge"
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface RequestListener {
        fun onNIP55Request(request: NIP55Request)
    }

    private var listener: RequestListener? = null
    private val pendingRequests = ConcurrentLinkedQueue<Pair<NIP55Request, (NIP55Result) -> Unit>>()

    fun registerListener(listener: RequestListener) {
        Log.d(TAG, "Listener registered: ${listener.javaClass.simpleName}")
        this.listener = listener

        // Process any queued requests
        processPendingRequests()
    }

    fun unregisterListener() {
        Log.d(TAG, "Listener unregistered (${pendingRequests.size} requests still pending)")
        this.listener = null
    }

    /**
     * Send a NIP-55 request to MainActivity
     * If MainActivity is not ready, queue the request until it becomes available
     */
    fun sendRequest(request: NIP55Request, callback: (NIP55Result) -> Unit) {
        mainHandler.post {
            val currentListener = listener
            if (currentListener == null) {
                Log.d(TAG, "MainActivity not ready - queuing request ${request.id}")
                pendingRequests.add(Pair(request, callback))
                Log.d(TAG, "Bridge queue size: ${pendingRequests.size}")
                return@post
            }

            Log.d(TAG, "Forwarding request ${request.id} to MainActivity immediately")

            // Register callback for this request
            PendingNIP55ResultRegistry.registerCallback(request.id, object : PendingNIP55ResultRegistry.ResultCallback {
                override fun onResult(result: NIP55Result) {
                    Log.d(TAG, "Received result for request ${request.id}")
                    callback(result)
                }
            })

            // Forward request to listener
            currentListener.onNIP55Request(request)
        }
    }

    /**
     * Process all pending requests (called when MainActivity becomes active)
     */
    private fun processPendingRequests() {
        if (pendingRequests.isEmpty()) {
            Log.d(TAG, "No pending requests to process")
            return
        }

        Log.d(TAG, "Processing ${pendingRequests.size} pending requests")

        while (pendingRequests.isNotEmpty()) {
            val (request, callback) = pendingRequests.poll() ?: break
            val currentListener = listener

            if (currentListener == null) {
                Log.e(TAG, "Listener became null while processing queue - re-queueing")
                pendingRequests.add(Pair(request, callback))
                break
            }

            Log.d(TAG, "Forwarding queued request ${request.id} to MainActivity")

            // Register callback for this request
            PendingNIP55ResultRegistry.registerCallback(request.id, object : PendingNIP55ResultRegistry.ResultCallback {
                override fun onResult(result: NIP55Result) {
                    Log.d(TAG, "Received result for queued request ${request.id}")
                    callback(result)
                }
            })

            // Forward request to listener
            currentListener.onNIP55Request(request)
        }
    }

    fun hasActiveListener(): Boolean = listener != null

    fun getPendingRequestCount(): Int = pendingRequests.size
}
