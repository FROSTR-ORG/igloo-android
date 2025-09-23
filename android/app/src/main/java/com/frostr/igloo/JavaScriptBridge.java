package com.frostr.igloo;

import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;

public class JavaScriptBridge {
    private static final String TAG = "JavaScriptBridge";
    private SecureStorage secureStorage;

    public JavaScriptBridge(Context context) {
        this.secureStorage = new SecureStorage(context);
    }

    @JavascriptInterface
    public boolean storeSecret(String key, String value) {
        Log.d(TAG, "Storing secret for key: " + key);
        return secureStorage.storeSecret(key, value);
    }

    @JavascriptInterface
    public String getSecret(String key) {
        Log.d(TAG, "Retrieving secret for key: " + key);
        return secureStorage.retrieveSecret(key);
    }

    @JavascriptInterface
    public boolean hasSecret(String key) {
        boolean exists = secureStorage.hasSecret(key);
        Log.d(TAG, "Secret exists for key " + key + ": " + exists);
        return exists;
    }

    @JavascriptInterface
    public boolean deleteSecret(String key) {
        Log.d(TAG, "Deleting secret for key: " + key);
        return secureStorage.deleteSecret(key);
    }

    @JavascriptInterface
    public void clearAllSecrets() {
        Log.d(TAG, "Clearing all secrets");
        secureStorage.clearAll();
    }

    @JavascriptInterface
    public String getDeviceInfo() {
        return "Android Secure Storage Available";
    }

    @JavascriptInterface
    public void log(String message) {
        Log.d(TAG, "PWA Log: " + message);
    }
}