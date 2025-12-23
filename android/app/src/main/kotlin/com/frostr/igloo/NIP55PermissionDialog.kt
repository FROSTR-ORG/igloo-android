package com.frostr.igloo

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.frostr.igloo.bridges.StorageBridge
import com.frostr.igloo.constants.NostrEventKinds
import com.frostr.igloo.debug.NIP55Metrics
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Native Android permission dialog for NIP-55 requests
 *
 * Handles both single permission requests (prompt_required) and bulk permission
 * requests (get_public_key with permissions array).
 *
 * Features:
 * - Material Design UI
 * - Event kind filtering for sign_event permissions
 * - Direct storage access via StorageBridge
 * - Remember choice checkbox
 * - Works independently of PWA
 */
class NIP55PermissionDialog : DialogFragment() {

    companion object {
        private const val TAG = "NIP55PermissionDialog"
        private const val ARG_APP_ID = "app_id"
        private const val ARG_REQUEST_TYPE = "request_type"
        private const val ARG_EVENT_KIND = "event_kind"
        private const val ARG_PERMISSIONS_JSON = "permissions_json"
        private const val ARG_IS_BULK = "is_bulk"

        /**
         * Create dialog for single permission request
         */
        fun newInstance(
            appId: String,
            requestType: String,
            eventKind: Int? = null
        ): NIP55PermissionDialog {
            return NIP55PermissionDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_APP_ID, appId)
                    putString(ARG_REQUEST_TYPE, requestType)
                    eventKind?.let { putInt(ARG_EVENT_KIND, it) }
                    putBoolean(ARG_IS_BULK, false)
                }
            }
        }

        /**
         * Create dialog for bulk permission request (get_public_key with permissions)
         */
        fun newInstanceBulk(
            appId: String,
            permissionsJson: String
        ): NIP55PermissionDialog {
            return NIP55PermissionDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_APP_ID, appId)
                    putString(ARG_PERMISSIONS_JSON, permissionsJson)
                    putBoolean(ARG_IS_BULK, true)
                }
            }
        }
    }

    private val gson = Gson()
    private var callback: PermissionCallback? = null
    private var rememberChoice = false  // Set based on dialog type in createDialogView
    private val selectedPermissions = mutableMapOf<String, Boolean>() // key: "type:kind", value: selected
    private var mergedPermissionsList: List<Map<String, Any>> = emptyList() // Store merged permissions for saving

    interface PermissionCallback {
        fun onApproved()
        fun onDenied()
        fun onCancelled()
    }

    fun setCallback(callback: PermissionCallback) {
        this.callback = callback
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appId = arguments?.getString(ARG_APP_ID) ?: "unknown"
        val isBulk = arguments?.getBoolean(ARG_IS_BULK) ?: false

        // Record metrics for permission prompt
        NIP55Metrics.recordPermissionPrompt()

        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(android.R.layout.select_dialog_item, null)

        // Create custom view programmatically (no XML layout needed)
        val dialogView = createDialogView(appId, isBulk)

        builder.setView(dialogView)
            .setTitle(if (isBulk) getString(R.string.permission_dialog_title_bulk) else getString(R.string.permission_dialog_title))
            .setPositiveButton(getString(R.string.permission_button_allow)) { _, _ ->
                handleApprove(appId, isBulk)
            }
            .setNegativeButton(getString(R.string.permission_button_deny)) { _, _ ->
                handleDeny(appId, isBulk)
            }

        val dialog = builder.create()

        // Prevent dialog from being cancelled by touching outside
        // This allows users to dismiss the keyboard without dismissing the dialog
        dialog.setCanceledOnTouchOutside(false)

        return dialog
    }

    /**
     * Called when the dialog is cancelled (back button, touch outside, etc.)
     * This ensures we return an immediate response instead of waiting for timeout
     */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        Log.d(TAG, "Permission dialog cancelled by user")
        callback?.onCancelled()
    }

    private fun createDialogView(appId: String, isBulk: Boolean): View {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // App name
        layout.addView(TextView(context).apply {
            text = getString(R.string.permission_app_label, appId)
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })

        // Permission list
        if (isBulk) {
            addBulkPermissionsList(layout, context)
        } else {
            addSinglePermissionInfo(layout, context)
        }

        // Divider line to separate permissions from the remember option
        layout.addView(android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1  // 1px height for divider
            ).apply {
                topMargin = 32
                bottomMargin = 16
            }
            setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))  // Light gray divider
        })

        // Remember choice section with distinct styling
        layout.addView(TextView(context).apply {
            text = getString(R.string.permission_header_settings)
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#757575"))  // Gray label
            setPadding(0, 0, 0, 8)
        })

        // Remember choice checkbox (checked by default for bulk dialogs only)
        rememberChoice = isBulk  // Initialize based on dialog type
        layout.addView(CheckBox(context).apply {
            text = getString(R.string.permission_remember_choice)
            isChecked = isBulk  // Checked for bulk dialogs, unchecked for single permission dialogs
            setOnCheckedChangeListener { _, isChecked ->
                rememberChoice = isChecked
            }
            setPadding(0, 0, 0, 0)
        })

        return layout
    }

    private fun addSinglePermissionInfo(layout: LinearLayout, context: Context) {
        val requestType = arguments?.getString(ARG_REQUEST_TYPE) ?: "unknown"
        val eventKind = if (arguments?.containsKey(ARG_EVENT_KIND) == true) {
            arguments?.getInt(ARG_EVENT_KIND)
        } else null

        layout.addView(TextView(context).apply {
            text = buildString {
                append("Permission: ")
                append(getHumanReadableName(requestType))
                if (eventKind != null) {
                    append("\nEvent Kind: ")
                    append(getEventKindLabel(eventKind))
                }
            }
            textSize = 16f
            setPadding(0, 8, 0, 8)
        })
    }

    private fun addBulkPermissionsList(layout: LinearLayout, context: Context) {
        val appId = arguments?.getString(ARG_APP_ID) ?: "unknown"
        val permissionsJson = arguments?.getString(ARG_PERMISSIONS_JSON) ?: "[]"

        try {
            val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
            val requestedPermissions: List<Map<String, Any>> = gson.fromJson(permissionsJson, listType)

            // Load default permissions from config and merge with requested
            val defaultPermissions = loadDefaultPermissions(context)
            val mergedPermissions = mergePermissions(requestedPermissions, defaultPermissions)
            Log.d(TAG, "Merged permissions: ${requestedPermissions.size} requested + ${defaultPermissions.size} defaults = ${mergedPermissions.size} total")

            // Load existing permissions for this specific app
            val existingPermissions = loadExistingPermissionsForApp(appId)
            Log.d(TAG, "Found ${existingPermissions.size} existing permissions for app: $appId")

            // Filter out permissions already granted for this app
            val newPermissions = mergedPermissions.filter { perm ->
                val type = perm["type"] as? String ?: "unknown"
                val kind = (perm["kind"] as? Double)?.toInt()
                val permKey = "$type:${kind ?: "null"}"

                // Check if this permission is already allowed for this app
                val alreadyAllowed = existingPermissions.any { existing ->
                    existing.type == type &&
                    existing.kind == kind &&
                    existing.allowed
                }

                if (alreadyAllowed) {
                    Log.d(TAG, "Filtering out already-allowed permission: $permKey for $appId")
                }

                !alreadyAllowed
            }

            // Store the filtered permissions list for use when saving
            mergedPermissionsList = newPermissions

            if (newPermissions.isEmpty()) {
                layout.addView(TextView(context).apply {
                    text = getString(R.string.permission_all_granted)
                    textSize = 14f
                    setPadding(0, 0, 0, 16)
                })
                return
            }

            layout.addView(TextView(context).apply {
                text = getString(R.string.permission_select_prompt)
                textSize = 14f
                setPadding(0, 0, 0, 16)
            })

            // Create a scrollable container for the permissions list
            val scrollView = android.widget.ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,  // Use weight to fill available space
                    1f  // Weight of 1 to expand
                ).apply {
                    // Set max height to prevent dialog from being too tall
                    // This will be constrained by the dialog's own size limits
                }
            }

            val permissionsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            newPermissions.forEach { perm ->
                val type = perm["type"] as? String ?: "unknown"
                val kind = (perm["kind"] as? Double)?.toInt()
                val permKey = "$type:${kind ?: "null"}"

                // Initialize as selected by default
                selectedPermissions[permKey] = true

                permissionsContainer.addView(CheckBox(context).apply {
                    text = buildString {
                        append(getHumanReadableName(type))
                        if (kind != null) {
                            append(" (")
                            append(getEventKindLabel(kind))
                            append(")")
                        }
                    }
                    textSize = 14f
                    setPadding(16, 4, 0, 4)
                    isChecked = true // All permissions selected by default
                    setOnCheckedChangeListener { _, isChecked ->
                        selectedPermissions[permKey] = isChecked
                    }
                })
            }

            scrollView.addView(permissionsContainer)
            layout.addView(scrollView)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse permissions JSON", e)
            layout.addView(TextView(context).apply {
                text = getString(R.string.permission_error_parsing)
                textSize = 14f
            })
        }
    }

    /**
     * Load existing permissions for a specific app from storage
     */
    private fun loadExistingPermissionsForApp(appId: String): List<Permission> {
        return try {
            val storageBridge = StorageBridge(requireContext())
            val existingJson = storageBridge.getItem("local", "nip55_permissions_v2")
                ?: return emptyList()

            val storageType = object : TypeToken<PermissionStorage>() {}.type
            val storage: PermissionStorage = gson.fromJson(existingJson, storageType)

            // Filter to only this app's permissions
            storage.permissions.filter { it.appId == appId }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load existing permissions for $appId", e)
            emptyList()
        }
    }

    /**
     * Load default permissions from assets/defaults.json
     */
    private fun loadDefaultPermissions(context: Context): List<Map<String, Any>> {
        return try {
            val jsonString = context.assets.open("defaults.json")
                .bufferedReader()
                .use { it.readText() }

            val configType = object : TypeToken<Map<String, Any>>() {}.type
            val config: Map<String, Any> = gson.fromJson(jsonString, configType)

            @Suppress("UNCHECKED_CAST")
            (config["permissions"] as? List<Map<String, Any>>) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default permissions from config", e)
            emptyList()
        }
    }

    /**
     * Merge requested permissions with default permissions, avoiding duplicates
     * Requested permissions take precedence (come first in the list)
     */
    private fun mergePermissions(
        requested: List<Map<String, Any>>,
        defaults: List<Map<String, Any>>
    ): List<Map<String, Any>> {
        val result = requested.toMutableList()

        // Track which permission keys we already have
        val existingKeys = requested.map { perm ->
            val type = perm["type"] as? String ?: "unknown"
            val kind = (perm["kind"] as? Double)?.toInt()
            "$type:${kind ?: "null"}"
        }.toSet()

        // Add defaults that aren't already in requested
        defaults.forEach { defaultPerm ->
            val type = defaultPerm["type"] as? String ?: "unknown"
            val kind = (defaultPerm["kind"] as? Double)?.toInt()
            val key = "$type:${kind ?: "null"}"

            if (key !in existingKeys) {
                result.add(defaultPerm)
                Log.d(TAG, "Adding default permission: $key")
            }
        }

        return result
    }

    private fun handleApprove(appId: String, isBulk: Boolean) {
        Log.d(TAG, "User approved permissions for $appId (remember: $rememberChoice)")

        // Record metrics for permission decision
        NIP55Metrics.recordPermissionDecision(approved = true)

        if (rememberChoice) {
            // Save permissions permanently to localStorage
            savePermissions(appId, isBulk, allowed = true)
        }
        // If rememberChoice is false, don't save - just approve this one request

        callback?.onApproved()
    }

    private fun handleDeny(appId: String, isBulk: Boolean) {
        Log.d(TAG, "User denied permissions for $appId (remember: $rememberChoice)")

        // Record metrics for permission decision
        NIP55Metrics.recordPermissionDecision(approved = false)

        if (rememberChoice) {
            savePermissions(appId, isBulk, allowed = false)
        }

        callback?.onDenied()
    }

    private fun savePermissions(appId: String, isBulk: Boolean, allowed: Boolean) {
        try {
            val storageBridge = StorageBridge(requireContext())
            val existingJson = storageBridge.getItem("local", "nip55_permissions_v2") ?: """{"version":2,"permissions":[]}"""

            val storageType = object : TypeToken<PermissionStorage>() {}.type
            val storage: PermissionStorage = gson.fromJson(existingJson, storageType)
            val permissions = storage.permissions.toMutableList()

            if (isBulk) {
                // Save bulk permissions - use the merged list (includes defaults) that was displayed
                mergedPermissionsList.forEach { perm ->
                    val type = perm["type"] as? String ?: return@forEach
                    val kind = (perm["kind"] as? Double)?.toInt()
                    val permKey = "$type:${kind ?: "null"}"

                    // Only save if this permission was selected (or if denying, save all as denied)
                    val shouldSave = if (allowed) {
                        selectedPermissions[permKey] == true
                    } else {
                        true // When denying, save all as denied
                    }

                    if (shouldSave) {
                        // Remove existing permission
                        permissions.removeAll { it.appId == appId && it.type == type && it.kind == kind }

                        // Add new permission
                        permissions.add(Permission(
                            appId = appId,
                            type = type,
                            kind = kind,
                            allowed = allowed,
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                }
            } else {
                // Save single permission
                val requestType = arguments?.getString(ARG_REQUEST_TYPE) ?: return
                val eventKind = if (arguments?.containsKey(ARG_EVENT_KIND) == true) {
                    arguments?.getInt(ARG_EVENT_KIND)
                } else null

                // Remove existing permission
                permissions.removeAll { it.appId == appId && it.type == requestType && it.kind == eventKind }

                // Add new permission
                permissions.add(Permission(
                    appId = appId,
                    type = requestType,
                    kind = eventKind,
                    allowed = allowed,
                    timestamp = System.currentTimeMillis()
                ))
            }

            // Save back to storage
            val updatedStorage = PermissionStorage(version = 2, permissions = permissions)
            storageBridge.setItem("local", "nip55_permissions_v2", gson.toJson(updatedStorage))

            Log.d(TAG, "Saved permissions to storage (allowed: $allowed)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save permissions", e)
        }
    }

    private fun getHumanReadableName(type: String): String {
        return when (type) {
            "get_public_key" -> "Get Public Key"
            "sign_event" -> "Sign Event"
            "nip04_encrypt" -> "NIP-04 Encrypt"
            "nip04_decrypt" -> "NIP-04 Decrypt"
            "nip44_encrypt" -> "NIP-44 Encrypt"
            "nip44_decrypt" -> "NIP-44 Decrypt"
            "decrypt_zap_event" -> "Decrypt Zap Event"
            else -> type
        }
    }

    private fun getEventKindLabel(kind: Int): String {
        return NostrEventKinds.getDisplayName(kind)
    }

    // Data classes (matching PWA format)
    data class PermissionStorage(
        val version: Int,
        val permissions: List<Permission>
    )

    data class Permission(
        val appId: String,
        val type: String,
        val kind: Int?,
        val allowed: Boolean,
        val timestamp: Long
    )
}
