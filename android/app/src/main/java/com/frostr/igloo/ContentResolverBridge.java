package com.frostr.igloo;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bridge between the PWA WebView and the NIP55ContentProvider
 *
 * This bridge enables the PWA to query the ContentProvider for background
 * signing operations when permissions are already granted.
 */
public class ContentResolverBridge {
    private static final String TAG = "ContentResolverBridge";
    private static final String AUTHORITY = "com.frostr.igloo.nip55";
    private static final Uri OPERATION_URI = Uri.parse("content://" + AUTHORITY + "/operation");

    private Context context;

    public ContentResolverBridge(Context context) {
        this.context = context;
    }

    /**
     * Check if a NIP-55 request can be auto-approved via ContentProvider
     * @param requestJson The NIP-55 request as JSON string
     * @return Result JSON string or null if not auto-approved
     */
    @JavascriptInterface
    public String checkAutoApproval(String requestJson) {
        Log.d(TAG, "Checking auto-approval for request: " + requestJson);

        try {
            // Encode the request as base64 for URI parameter
            String encodedRequest = android.util.Base64.encodeToString(
                requestJson.getBytes(), android.util.Base64.DEFAULT
            );

            Uri queryUri = OPERATION_URI.buildUpon()
                .appendQueryParameter("data", encodedRequest)
                .build();

            Log.d(TAG, "Querying ContentProvider with URI: " + queryUri);

            // Query the ContentProvider
            Cursor cursor = context.getContentResolver().query(
                queryUri, null, null, null, null
            );

            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        String result = cursor.getString(cursor.getColumnIndex("result"));
                        String success = cursor.getString(cursor.getColumnIndex("success"));
                        String error = null;

                        int errorIndex = cursor.getColumnIndex("error");
                        if (errorIndex >= 0) {
                            error = cursor.getString(errorIndex);
                        }

                        Log.d(TAG, "ContentProvider result - success: " + success +
                              ", result: " + result + ", error: " + error);

                        if ("true".equals(success) && result != null) {
                            JSONObject response = new JSONObject();
                            response.put("result", result);
                            response.put("success", true);
                            return response.toString();
                        } else {
                            JSONObject response = new JSONObject();
                            response.put("success", false);
                            if (error != null) {
                                response.put("error", error);
                            }
                            return response.toString();
                        }
                    }
                } finally {
                    cursor.close();
                }
            }

            Log.d(TAG, "No result from ContentProvider");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error checking auto-approval", e);
            try {
                JSONObject response = new JSONObject();
                response.put("success", false);
                response.put("error", e.getMessage());
                return response.toString();
            } catch (JSONException je) {
                return null;
            }
        }
    }

    /**
     * Alternative method using ContentProvider call() method
     * @param requestJson The NIP-55 request as JSON string
     * @return Result JSON string or null if not auto-approved
     */
    @JavascriptInterface
    public String processRequestViaCall(String requestJson) {
        Log.d(TAG, "Processing request via call method: " + requestJson);

        try {
            Bundle extras = new Bundle();
            extras.putString("request", requestJson);

            Bundle result = context.getContentResolver().call(
                OPERATION_URI, "nip55_request", null, extras
            );

            if (result != null) {
                boolean success = result.getBoolean("success", false);
                String resultData = result.getString("result");
                String error = result.getString("error");

                Log.d(TAG, "Call result - success: " + success +
                      ", result: " + resultData + ", error: " + error);

                JSONObject response = new JSONObject();
                response.put("success", success);
                if (resultData != null) {
                    response.put("result", resultData);
                }
                if (error != null) {
                    response.put("error", error);
                }
                return response.toString();
            }

            Log.d(TAG, "No result from ContentProvider call");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error processing request via call", e);
            try {
                JSONObject response = new JSONObject();
                response.put("success", false);
                response.put("error", e.getMessage());
                return response.toString();
            } catch (JSONException je) {
                return null;
            }
        }
    }

    /**
     * Check if the ContentProvider is available and accessible
     * @return true if available, false otherwise
     */
    @JavascriptInterface
    public boolean isContentProviderAvailable() {
        try {
            Bundle result = context.getContentResolver().call(
                OPERATION_URI, "ping", null, null
            );
            boolean available = result != null;
            Log.d(TAG, "ContentProvider availability: " + available);
            return available;
        } catch (Exception e) {
            Log.d(TAG, "ContentProvider not available: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get debug information about the ContentProvider
     * @return JSON string with debug info
     */
    @JavascriptInterface
    public String getDebugInfo() {
        try {
            JSONObject debug = new JSONObject();
            debug.put("authority", AUTHORITY);
            debug.put("operationUri", OPERATION_URI.toString());
            debug.put("available", isContentProviderAvailable());
            debug.put("timestamp", System.currentTimeMillis());

            Log.d(TAG, "Debug info: " + debug.toString());
            return debug.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Error creating debug info", e);
            return "{}";
        }
    }

    /**
     * Log method for debugging from PWA
     * @param message Message to log
     */
    @JavascriptInterface
    public void log(String message) {
        Log.d(TAG, "PWA Log: " + message);
    }
}