package com.frostr.igloo.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit logger for NIP-55 signing requests.
 *
 * Logs sign_event requests to a JSON Lines file for auditing purposes.
 * Each line is a JSON object containing the event and metadata.
 *
 * Log file location: /storage/emulated/0/Android/data/{packageName}/files/audit/signing_audit.jsonl
 *
 * Usage:
 *   AuditLogger.logSignEvent(context, callingApp, eventJson)
 *
 * Retrieve logs via:
 *   adb pull /storage/emulated/0/Android/data/com.frostr.igloo.debug/files/audit/
 */
object AuditLogger {

    private const val TAG = "AuditLogger"
    private const val AUDIT_DIR = "audit"
    private const val AUDIT_FILE = "signing_audit.jsonl"
    private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024 // 10 MB

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    /**
     * Log a sign_event request to the audit file.
     *
     * @param context Application context
     * @param callingApp Package name of the app requesting the signature
     * @param eventJson The Nostr event JSON being signed
     * @param requestId Optional request ID for correlation
     */
    fun logSignEvent(
        context: Context,
        callingApp: String,
        eventJson: String,
        requestId: String? = null
    ) {
        try {
            val auditDir = File(context.getExternalFilesDir(null), AUDIT_DIR)
            if (!auditDir.exists()) {
                auditDir.mkdirs()
            }

            val auditFile = File(auditDir, AUDIT_FILE)

            // Rotate file if too large
            if (auditFile.exists() && auditFile.length() > MAX_FILE_SIZE_BYTES) {
                rotateLogFile(auditFile)
            }

            // Build audit entry
            val auditEntry = JSONObject().apply {
                put("timestamp", dateFormat.format(Date()))
                put("unix_ts", System.currentTimeMillis())
                put("calling_app", callingApp)
                requestId?.let { put("request_id", it) }

                // Parse and include the event
                try {
                    val event = JSONObject(eventJson)
                    put("event", event)
                } catch (e: Exception) {
                    // If parsing fails, include as raw string
                    put("event_raw", eventJson)
                    put("parse_error", e.message)
                }
            }

            // Append to file (one JSON object per line)
            FileWriter(auditFile, true).use { writer ->
                writer.append(auditEntry.toString())
                writer.append("\n")
            }

            Log.d(TAG, "Logged sign_event from $callingApp")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audit log", e)
        }
    }

    /**
     * Log an encrypt/decrypt request to the audit file.
     *
     * @param context Application context
     * @param callingApp Package name of the requesting app
     * @param operationType The operation type (nip04_encrypt, nip44_decrypt, etc.)
     * @param pubkey The target pubkey
     * @param requestId Optional request ID for correlation
     */
    fun logCryptoOperation(
        context: Context,
        callingApp: String,
        operationType: String,
        pubkey: String,
        requestId: String? = null
    ) {
        try {
            val auditDir = File(context.getExternalFilesDir(null), AUDIT_DIR)
            if (!auditDir.exists()) {
                auditDir.mkdirs()
            }

            val auditFile = File(auditDir, AUDIT_FILE)

            // Rotate file if too large
            if (auditFile.exists() && auditFile.length() > MAX_FILE_SIZE_BYTES) {
                rotateLogFile(auditFile)
            }

            // Build audit entry
            val auditEntry = JSONObject().apply {
                put("timestamp", dateFormat.format(Date()))
                put("unix_ts", System.currentTimeMillis())
                put("calling_app", callingApp)
                put("operation", operationType)
                put("pubkey", pubkey)
                requestId?.let { put("request_id", it) }
            }

            // Append to file
            FileWriter(auditFile, true).use { writer ->
                writer.append(auditEntry.toString())
                writer.append("\n")
            }

            Log.d(TAG, "Logged $operationType from $callingApp")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audit log", e)
        }
    }

    /**
     * Get the path to the audit log file.
     */
    fun getAuditLogPath(context: Context): String {
        val auditDir = File(context.getExternalFilesDir(null), AUDIT_DIR)
        return File(auditDir, AUDIT_FILE).absolutePath
    }

    /**
     * Get the current audit log file size in bytes.
     */
    fun getAuditLogSize(context: Context): Long {
        val auditFile = File(context.getExternalFilesDir(null), "$AUDIT_DIR/$AUDIT_FILE")
        return if (auditFile.exists()) auditFile.length() else 0
    }

    /**
     * Clear the audit log.
     */
    fun clearAuditLog(context: Context): Boolean {
        return try {
            val auditFile = File(context.getExternalFilesDir(null), "$AUDIT_DIR/$AUDIT_FILE")
            if (auditFile.exists()) {
                auditFile.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear audit log", e)
            false
        }
    }

    /**
     * Rotate the log file when it exceeds max size.
     */
    private fun rotateLogFile(currentFile: File) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val rotatedFile = File(currentFile.parent, "signing_audit_$timestamp.jsonl")
            currentFile.renameTo(rotatedFile)
            Log.i(TAG, "Rotated audit log to ${rotatedFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate audit log", e)
        }
    }
}
