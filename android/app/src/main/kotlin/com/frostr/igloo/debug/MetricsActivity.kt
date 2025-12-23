package com.frostr.igloo.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.frostr.igloo.R

/**
 * Full-screen Activity for displaying NIP-55 pipeline metrics.
 * Only accessible in debug builds via the floating debug button.
 */
class MetricsActivity : AppCompatActivity() {

    private lateinit var metricsTextView: TextView
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshInterval = 1000L // 1 second auto-refresh

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            refreshHandler.postDelayed(this, refreshInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_metrics)

        // Setup toolbar with back button
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "NIP-55 Metrics"
        }

        // Find views
        metricsTextView = findViewById(R.id.metrics_content)

        findViewById<Button>(R.id.reset_button).setOnClickListener {
            NIP55Metrics.reset()
            updateDisplay()
            Toast.makeText(this, "Metrics reset", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.copy_button).setOnClickListener {
            copyToClipboard()
        }

        updateDisplay()
    }

    override fun onResume() {
        super.onResume()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        stopAutoRefresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startAutoRefresh() {
        refreshHandler.post(refreshRunnable)
    }

    private fun stopAutoRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun updateDisplay() {
        val snapshot = NIP55Metrics.getSnapshot()
        metricsTextView.text = formatMetrics(snapshot)
    }

    private fun formatMetrics(s: MetricsSnapshot): String {
        val sb = StringBuilder()

        // Header
        sb.appendLine("$DOUBLE_LINE")
        sb.appendLine("         NIP-55 PIPELINE METRICS")
        sb.appendLine("$DOUBLE_LINE")
        sb.appendLine("Session Duration: ${s.formatSessionDuration()}")
        sb.appendLine()

        // Request Metrics
        sb.appendLine("$SINGLE_LINE REQUEST METRICS $SINGLE_LINE")
        sb.appendLine("Total Requests: ${s.totalRequests}")

        if (s.requestsByType.isNotEmpty()) {
            sb.appendLine()
            s.requestsByType.entries
                .sortedByDescending { it.value }
                .forEach { (type, count) ->
                    val percent = if (s.totalRequests > 0) count * 100 / s.totalRequests else 0
                    sb.appendLine("  ${type.padEnd(20)} ${count.toString().padStart(4)} ($percent%)")
                }
        }

        if (s.requestsByEntryPoint.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Entry Point:")
            s.requestsByEntryPoint.entries
                .sortedByDescending { it.value }
                .forEach { (entry, count) ->
                    val percent = if (s.totalRequests > 0) count * 100 / s.totalRequests else 0
                    sb.appendLine("  ${entry.padEnd(20)} ${count.toString().padStart(4)} ($percent%)")
                }
        }

        if (s.requestsByCaller.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Top Callers:")
            s.requestsByCaller.entries
                .sortedByDescending { it.value }
                .take(5)
                .forEach { (caller, count) ->
                    val shortCaller = caller.takeLast(30)
                    sb.appendLine("  ${shortCaller.padEnd(30)} $count")
                }
        }
        sb.appendLine()

        // Success/Failure
        sb.appendLine("$SINGLE_LINE SUCCESS/FAILURE $SINGLE_LINE")
        val successRateStr = String.format("%.1f%%", s.successRate)
        sb.appendLine("Success Rate: $successRateStr (${s.successCount}/${s.successCount + s.failureCount})")

        if (s.errorsByType.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Errors by Type:")
            s.errorsByType.entries
                .sortedByDescending { it.value }
                .forEach { (type, count) ->
                    sb.appendLine("  ${type.padEnd(20)} $count")
                }
        }
        sb.appendLine()

        // Timing
        sb.appendLine("$SINGLE_LINE TIMING $SINGLE_LINE")
        sb.appendLine("Avg Duration: ${s.avgDurationMs}ms")
        sb.appendLine("P95 Duration: ${s.p95DurationMs}ms")
        sb.appendLine("Slow (>1s):   ${s.slowRequestCount}")
        sb.appendLine()

        // Deduplication
        sb.appendLine("$SINGLE_LINE DEDUPLICATION $SINGLE_LINE")
        sb.appendLine("Cache Hits:        ${s.cacheHits}")
        sb.appendLine("Duplicates Blocked: ${s.duplicatesBlocked}")
        sb.appendLine()

        // Permissions
        sb.appendLine("$SINGLE_LINE PERMISSIONS $SINGLE_LINE")
        sb.appendLine("Prompts Shown:   ${s.permissionPromptsShown}")
        val approvalRateStr = String.format("%.1f%%", s.permissionApprovalRate)
        sb.appendLine("Approval Rate:   $approvalRateStr")
        sb.appendLine()

        // Resources
        sb.appendLine("$SINGLE_LINE RESOURCES $SINGLE_LINE")
        sb.appendLine("Wake Lock Acquisitions: ${s.wakeLockAcquisitions}")
        sb.appendLine("WebView Unavailable:    ${s.webViewUnavailableCount}")
        sb.appendLine("$DOUBLE_LINE")

        return sb.toString()
    }

    private fun copyToClipboard() {
        val snapshot = NIP55Metrics.getSnapshot()
        val text = formatMetrics(snapshot)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("NIP-55 Metrics", text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "Metrics copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DOUBLE_LINE = "======================================="
        private const val SINGLE_LINE = "---"
    }
}
