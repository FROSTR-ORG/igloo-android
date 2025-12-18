package com.frostr.igloo

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.frostr.igloo.bridges.StorageBridge
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Welcome dialog shown on first app launch
 *
 * Displays helpful information about Igloo and a link to the project website.
 * Content is loaded from assets/welcome.json for easy customization.
 * Includes a "Don't show this again" checkbox to suppress future displays.
 */
class WelcomeDialog : DialogFragment() {

    companion object {
        private const val TAG = "WelcomeDialog"
        const val STORAGE_KEY = "igloo_welcome_dismissed"
        private const val WELCOME_FILE = "welcome.json"

        fun newInstance(): WelcomeDialog {
            return WelcomeDialog()
        }

        /**
         * Check if the welcome dialog should be shown
         * Returns true if the user hasn't dismissed it permanently
         */
        fun shouldShow(storageBridge: StorageBridge): Boolean {
            val dismissed = storageBridge.getItem("local", STORAGE_KEY)
            return dismissed != "true"
        }
    }

    private val gson = Gson()
    private var dontShowAgain = false
    private var welcomeContent: WelcomeContent? = null

    /**
     * Data class for welcome.json content
     * All fields are required - no defaults. The welcome.json file must exist.
     */
    data class WelcomeContent(
        val title: String,
        val description: String,
        val warning: String,
        val features: List<String>,
        val gettingStarted: String,
        val websiteUrl: String,
        val websiteButtonText: String,
        val dismissButtonText: String,
        val dontShowAgainText: String
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Load content from assets
        welcomeContent = loadWelcomeContent(requireContext())

        val builder = AlertDialog.Builder(requireContext())
        val dialogView = createDialogView()

        builder.setView(dialogView)
            .setTitle(welcomeContent!!.title)
            .setPositiveButton(welcomeContent!!.dismissButtonText) { _, _ ->
                handleDismiss()
            }

        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)

        return dialog
    }

    /**
     * Load welcome content from assets/welcome.json
     * Throws exception if file doesn't exist or can't be parsed
     */
    private fun loadWelcomeContent(context: Context): WelcomeContent {
        val jsonString = context.assets.open(WELCOME_FILE)
            .bufferedReader()
            .use { it.readText() }

        return gson.fromJson(jsonString, WelcomeContent::class.java)
            ?: throw IllegalStateException("Failed to parse $WELCOME_FILE")
    }

    private fun createDialogView(): View {
        val context = requireContext()
        val content = welcomeContent
            ?: throw IllegalStateException("Welcome content not loaded")

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // App description
        if (content.description.isNotEmpty()) {
            layout.addView(TextView(context).apply {
                text = content.description
                textSize = 16f
                setPadding(0, 0, 0, 24)
            })
        }

        // Features list
        if (content.features.isNotEmpty()) {
            layout.addView(TextView(context).apply {
                text = buildString {
                    append("Features:\n")
                    content.features.forEach { feature ->
                        append("• $feature\n")
                    }
                }.trimEnd()
                textSize = 14f
                setPadding(0, 0, 0, 24)
            })
        }

        // Getting started info
        if (content.gettingStarted.isNotEmpty()) {
            layout.addView(TextView(context).apply {
                text = content.gettingStarted
                textSize = 14f
                setPadding(0, 0, 0, 24)
            })
        }

        // Website link button
        if (content.websiteUrl.isNotEmpty()) {
            layout.addView(Button(context).apply {
                text = content.websiteButtonText
                setOnClickListener {
                    openProjectWebsite(content.websiteUrl)
                }
            })
        }

        // Warning section (displayed with emphasis)
        if (content.warning.isNotEmpty()) {
            layout.addView(TextView(context).apply {
                text = "\u26A0\uFE0F ${content.warning}"  // Warning emoji prefix
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#D84315"))  // Deep orange
                setBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))  // Light orange background
                setPadding(24, 16, 24, 16)
            })
        }

        // Don't show again checkbox
        layout.addView(CheckBox(context).apply {
            text = content.dontShowAgainText
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                dontShowAgain = isChecked
            }
            setPadding(0, 24, 0, 0)
        })

        return layout
    }

    private fun openProjectWebsite(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open website: $url", e)
        }
    }

    private fun handleDismiss() {
        if (dontShowAgain) {
            // Save preference to not show again
            try {
                val storageBridge = StorageBridge(requireContext())
                storageBridge.setItem("local", STORAGE_KEY, "true")
                Log.d(TAG, "Welcome dialog dismissed permanently")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save welcome dialog preference", e)
            }
        } else {
            Log.d(TAG, "Welcome dialog dismissed (will show again)")
        }
    }
}
