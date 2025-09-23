package com.frostr.igloo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * NIP-55 Content Provider for background signing operations
 *
 * This provider enables seamless auto-approval of previously permitted NIP-55 requests
 * without requiring the full UI activation that the Intent-based approach needs.
 *
 * URI format: content://com.frostr.igloo.nip55/operation?data=<base64_encoded_request>
 */
public class NIP55ContentProvider extends ContentProvider {
    private static final String TAG = "NIP55ContentProvider";
    private static final String AUTHORITY = "com.frostr.igloo.nip55";
    private static final String OPERATION_PATH = "operation";

    // URI matcher codes
    private static final int OPERATION = 1;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        uriMatcher.addURI(AUTHORITY, OPERATION_PATH, OPERATION);
    }

    private SecureStorage secureStorage;
    private SharedPreferences sharedPreferences;

    @Override
    public boolean onCreate() {
        Log.d(TAG, "NIP55ContentProvider onCreate");
        Context context = getContext();
        if (context == null) {
            Log.e(TAG, "Context is null in onCreate");
            return false;
        }

        secureStorage = new SecureStorage(context);
        sharedPreferences = context.getSharedPreferences("igloo_settings", Context.MODE_PRIVATE);

        Log.d(TAG, "NIP55ContentProvider initialized successfully");
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                       @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        Log.d(TAG, "Query called with URI: " + uri.toString());

        int match = uriMatcher.match(uri);
        if (match != OPERATION) {
            Log.e(TAG, "Unknown URI: " + uri);
            return null;
        }

        String data = uri.getQueryParameter("data");
        if (data == null || data.isEmpty()) {
            Log.e(TAG, "No data parameter in URI");
            return null;
        }

        try {
            // Decode the NIP-55 request
            String requestJson = new String(android.util.Base64.decode(data, android.util.Base64.DEFAULT));
            Log.d(TAG, "Decoded request: " + requestJson);

            JSONObject request = new JSONObject(requestJson);
            String result = processNIP55Request(request);

            // Return result as cursor
            MatrixCursor cursor = new MatrixCursor(new String[]{"result", "success"});
            if (result != null) {
                cursor.addRow(new Object[]{result, "true"});
                Log.d(TAG, "Returning successful result");
            } else {
                cursor.addRow(new Object[]{null, "false"});
                Log.d(TAG, "Returning failure result");
            }

            return cursor;
        } catch (Exception e) {
            Log.e(TAG, "Error processing request", e);
            MatrixCursor cursor = new MatrixCursor(new String[]{"result", "success", "error"});
            cursor.addRow(new Object[]{null, "false", e.getMessage()});
            return cursor;
        }
    }

    /**
     * Process a NIP-55 request and return the result if auto-approved
     */
    private String processNIP55Request(JSONObject request) {
        try {
            String host = request.optString("host", "");
            String type = request.optString("type", "");

            Log.d(TAG, "Processing request - host: " + host + ", type: " + type);

            // Check if we have an existing permission for this request
            if (!hasExistingPermission(request)) {
                Log.d(TAG, "No existing permission found, rejecting request");
                return null;
            }

            Log.d(TAG, "Permission found, executing operation");

            // Execute the operation based on type
            switch (type) {
                case "get_public_key":
                    return getPublicKey();

                case "sign_event":
                    JSONObject event = request.optJSONObject("event");
                    return signEvent(event);

                case "nip04_encrypt":
                    String pubkey04 = request.optString("pubkey", "");
                    String plaintext04 = request.optString("plaintext", "");
                    return nip04Encrypt(pubkey04, plaintext04);

                case "nip04_decrypt":
                    String pubkey04d = request.optString("pubkey", "");
                    String ciphertext04 = request.optString("ciphertext", "");
                    return nip04Decrypt(pubkey04d, ciphertext04);

                case "nip44_encrypt":
                    String pubkey44 = request.optString("pubkey", "");
                    String plaintext44 = request.optString("plaintext", "");
                    return nip44Encrypt(pubkey44, plaintext44);

                case "nip44_decrypt":
                    String pubkey44d = request.optString("pubkey", "");
                    String ciphertext44 = request.optString("ciphertext", "");
                    return nip44Decrypt(pubkey44d, ciphertext44);

                default:
                    Log.e(TAG, "Unknown operation type: " + type);
                    return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing NIP-55 request", e);
            return null;
        }
    }

    /**
     * Check if an existing permission exists for the given request
     */
    private boolean hasExistingPermission(JSONObject request) {
        try {
            String host = request.optString("host", "");
            String type = request.optString("type", "");

            // Get stored permissions from SharedPreferences
            String permissionsJson = sharedPreferences.getString("perms", "[]");
            JSONArray permissions = new JSONArray(permissionsJson);

            for (int i = 0; i < permissions.length(); i++) {
                JSONObject policy = permissions.getJSONObject(i);

                if ("sign_event".equals(type)) {
                    JSONObject eventObj = request.optJSONObject("event");
                    int kind = eventObj != null ? eventObj.optInt("kind", 0) : 0;

                    JSONArray events = policy.optJSONArray("event");
                    if (events != null) {
                        for (int j = 0; j < events.length(); j++) {
                            JSONObject eventRecord = events.getJSONObject(j);
                            if (host.equals(eventRecord.optString("host", "")) &&
                                kind == eventRecord.optInt("kind", -1) &&
                                eventRecord.optBoolean("accept", false)) {
                                Log.d(TAG, "Found matching event permission");
                                return true;
                            }
                        }
                    }
                } else {
                    JSONArray actions = policy.optJSONArray("action");
                    if (actions != null) {
                        for (int j = 0; j < actions.length(); j++) {
                            JSONObject actionRecord = actions.getJSONObject(j);
                            if (host.equals(actionRecord.optString("host", "")) &&
                                type.equals(actionRecord.optString("action", "")) &&
                                actionRecord.optBoolean("accept", false)) {
                                Log.d(TAG, "Found matching action permission");
                                return true;
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "No matching permission found");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking permissions", e);
            return false;
        }
    }

    /**
     * Get the public key - this would interface with the secure storage/signing system
     */
    private String getPublicKey() {
        try {
            // Try to get the public key from secure storage
            String pubkey = secureStorage.retrieveSecret("user_pubkey");
            if (pubkey != null && !pubkey.isEmpty()) {
                Log.d(TAG, "Retrieved public key from secure storage");
                return pubkey;
            }

            // Fallback: Try to get from SharedPreferences
            String fallbackPubkey = sharedPreferences.getString("user_pubkey", null);
            if (fallbackPubkey != null) {
                Log.d(TAG, "Retrieved public key from SharedPreferences fallback");
                return fallbackPubkey;
            }

            Log.w(TAG, "No public key found in storage");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving public key", e);
            return null;
        }
    }

    /**
     * Sign an event - this would interface with the secure storage/signing system
     */
    private String signEvent(JSONObject event) {
        try {
            Log.d(TAG, "Attempting to sign event: " + event.toString());

            // Try to get the private key from secure storage
            String privateKey = secureStorage.retrieveSecret("user_private_key");
            if (privateKey == null || privateKey.isEmpty()) {
                Log.w(TAG, "No private key found in secure storage");
                return null;
            }

            // For now, return a signed-looking result
            // TODO: Implement actual cryptographic signing
            String eventId = event.optString("id", "");
            String pubkey = event.optString("pubkey", "");

            Log.d(TAG, "Signing event with ID: " + eventId + " for pubkey: " + pubkey);

            // Create a mock signature for now (should be replaced with actual signing)
            String mockSignature = "a1b2c3d4e5f6" + eventId.substring(0, Math.min(8, eventId.length())) + "signed";

            Log.d(TAG, "Generated signature: " + mockSignature);
            return mockSignature;

        } catch (Exception e) {
            Log.e(TAG, "Error signing event", e);
            return null;
        }
    }

    /**
     * NIP-04 encryption
     */
    private String nip04Encrypt(String pubkey, String plaintext) {
        // TODO: Implement actual NIP-04 encryption
        Log.d(TAG, "NIP-04 encrypting for pubkey: " + pubkey);
        return "placeholder_encrypted";
    }

    /**
     * NIP-04 decryption
     */
    private String nip04Decrypt(String pubkey, String ciphertext) {
        // TODO: Implement actual NIP-04 decryption
        Log.d(TAG, "NIP-04 decrypting from pubkey: " + pubkey);
        return "placeholder_decrypted";
    }

    /**
     * NIP-44 encryption
     */
    private String nip44Encrypt(String pubkey, String plaintext) {
        // TODO: Implement actual NIP-44 encryption
        Log.d(TAG, "NIP-44 encrypting for pubkey: " + pubkey);
        return "placeholder_encrypted";
    }

    /**
     * NIP-44 decryption
     */
    private String nip44Decrypt(String pubkey, String ciphertext) {
        // TODO: Implement actual NIP-44 decryption
        Log.d(TAG, "NIP-44 decrypting from pubkey: " + pubkey);
        return "placeholder_decrypted";
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        int match = uriMatcher.match(uri);
        switch (match) {
            case OPERATION:
                return "vnd.android.cursor.dir/vnd.frostr.igloo.nip55";
            default:
                return null;
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        // Not supported for NIP-55 operations
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        // Not supported for NIP-55 operations
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                     @Nullable String[] selectionArgs) {
        // Not supported for NIP-55 operations
        return 0;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Log.d(TAG, "Call method: " + method);

        if ("nip55_request".equals(method) && extras != null) {
            String requestJson = extras.getString("request");
            if (requestJson != null) {
                try {
                    JSONObject request = new JSONObject(requestJson);
                    String result = processNIP55Request(request);

                    Bundle response = new Bundle();
                    response.putString("result", result);
                    response.putBoolean("success", result != null);
                    return response;
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing request JSON", e);
                    Bundle response = new Bundle();
                    response.putBoolean("success", false);
                    response.putString("error", e.getMessage());
                    return response;
                }
            }
        }

        return super.call(method, arg, extras);
    }
}