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
    private var rememberChoice = false
    private val selectedPermissions = mutableMapOf<String, Boolean>() // key: "type:kind", value: selected

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

        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(android.R.layout.select_dialog_item, null)

        // Create custom view programmatically (no XML layout needed)
        val dialogView = createDialogView(appId, isBulk)

        builder.setView(dialogView)
            .setTitle(if (isBulk) "Grant Permissions" else "Permission Request")
            .setPositiveButton("Allow") { _, _ ->
                handleApprove(appId, isBulk)
            }
            .setNegativeButton("Deny") { _, _ ->
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
            text = "Application: $appId"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })

        // Permission list
        if (isBulk) {
            addBulkPermissionsList(layout, context)
        } else {
            addSinglePermissionInfo(layout, context)
        }

        // Remember choice checkbox
        layout.addView(CheckBox(context).apply {
            text = "Remember my choice"
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                rememberChoice = isChecked
            }
            setPadding(0, 24, 0, 0)
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
        val permissionsJson = arguments?.getString(ARG_PERMISSIONS_JSON) ?: "[]"

        try {
            val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
            val permissions: List<Map<String, Any>> = gson.fromJson(permissionsJson, listType)

            layout.addView(TextView(context).apply {
                text = "Select permissions to grant:"
                textSize = 14f
                setPadding(0, 0, 0, 16)
            })

            permissions.forEach { perm ->
                val type = perm["type"] as? String ?: "unknown"
                val kind = (perm["kind"] as? Double)?.toInt()
                val permKey = "$type:${kind ?: "null"}"

                // Initialize as selected by default
                selectedPermissions[permKey] = true

                layout.addView(CheckBox(context).apply {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse permissions JSON", e)
            layout.addView(TextView(context).apply {
                text = "Error parsing permissions"
                textSize = 14f
            })
        }
    }

    private fun handleApprove(appId: String, isBulk: Boolean) {
        Log.d(TAG, "User approved permissions for $appId (remember: $rememberChoice)")

        if (rememberChoice) {
            // Save permissions permanently to localStorage
            savePermissions(appId, isBulk, allowed = true)
        }
        // If rememberChoice is false, don't save - just approve this one request

        callback?.onApproved()
    }

    private fun handleDeny(appId: String, isBulk: Boolean) {
        Log.d(TAG, "User denied permissions for $appId (remember: $rememberChoice)")

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
                // Save bulk permissions - only save selected ones
                val permissionsJson = arguments?.getString(ARG_PERMISSIONS_JSON) ?: "[]"
                val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
                val requestedPerms: List<Map<String, Any>> = gson.fromJson(permissionsJson, listType)

                requestedPerms.forEach { perm ->
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
        return when (kind) {
            0 -> "Metadata"
            1 -> "Text Note"
            3 -> "Contacts"
            4 -> "Direct Message"
            5 -> "Event Deletion"
            6 -> "Repost"
            7 -> "Reaction"
            22242 -> "Relay Auth"
            23194 -> "Wallet Info"
            23195 -> "Wallet Request"
            24133 -> "Nostr Connect"
            27235 -> "NWC Request"
            30023 -> "Long-form Content"
            30078 -> "App Data"
            else -> "Kind $kind"
        }
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
