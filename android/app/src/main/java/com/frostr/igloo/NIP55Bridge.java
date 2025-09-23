package com.frostr.igloo;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.webkit.JavascriptInterface;

public class NIP55Bridge {
    private static final String TAG = "NIP55Bridge";
    private Activity activity;
    private String pendingRequestId;

    public NIP55Bridge(Activity activity) {
        this.activity = activity;
    }

    public void setPendingRequestId(String requestId) {
        this.pendingRequestId = requestId;
        Log.d(TAG, "Set pending request ID: " + requestId);
    }

    @JavascriptInterface
    public void approveRequest(String result, String id, String event) {
        Log.d(TAG, "PWA approved request - result: " + result + ", id: " + id);

        Intent resultIntent = new Intent();
        resultIntent.putExtra("result", result);
        resultIntent.putExtra("package", activity.getPackageName());

        if (id != null && !id.isEmpty()) {
            resultIntent.putExtra("id", id);
        }

        if (event != null && !event.isEmpty()) {
            resultIntent.putExtra("event", event);
        }

        activity.setResult(Activity.RESULT_OK, resultIntent);
        Log.d(TAG, "Set result RESULT_OK with extras");

        // Finish activity to return to Amethyst
        activity.finish();
        Log.d(TAG, "Finished activity to return to calling app");
    }

    @JavascriptInterface
    public void denyRequest(String id, String reason) {
        Log.d(TAG, "PWA denied request - id: " + id + ", reason: " + reason);

        Intent resultIntent = new Intent();
        resultIntent.putExtra("package", activity.getPackageName());

        if (id != null && !id.isEmpty()) {
            resultIntent.putExtra("id", id);
        }

        if (reason != null && !reason.isEmpty()) {
            resultIntent.putExtra("error", reason);
        }

        activity.setResult(Activity.RESULT_CANCELED, resultIntent);
        Log.d(TAG, "Set result RESULT_CANCELED with extras");

        // Finish activity to return to Amethyst
        activity.finish();
        Log.d(TAG, "Finished activity to return to calling app");
    }

    @JavascriptInterface
    public String getPendingRequestId() {
        return pendingRequestId;
    }

    @JavascriptInterface
    public void log(String message) {
        Log.d(TAG, "PWA Log: " + message);
    }
}