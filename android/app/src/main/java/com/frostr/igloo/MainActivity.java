package com.frostr.igloo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.util.Log;

public class MainActivity extends Activity {
    private static final String TAG = "IglooWrapper";
    private static final boolean DEVELOPMENT_MODE = true; // Set to false for production
    private static final String DEVELOPMENT_URL = "http://10.0.2.2:3000"; // Android emulator host mapping

    private LocalWebServer webServer;
    private WebView webView;
    private boolean isFirstLoad = true;
    private boolean isPWALoaded = false; // Track if PWA has been loaded at least once
    private NIP55Bridge nip55Bridge;
    private boolean sessionPersistenceSetup = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== ACTIVITY LIFECYCLE: onCreate ===");
        Log.d(TAG, "Process ID: " + android.os.Process.myPid());
        Log.d(TAG, "savedInstanceState: " + (savedInstanceState != null ? "exists" : "null"));

        // Start local web server only in production mode
        if (!DEVELOPMENT_MODE) {
            webServer = new LocalWebServer(this);
            webServer.start();
        }

        // Create WebView
        webView = new WebView(this);
        setContentView(webView);

        // Configure WebView
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Memory optimization settings
        webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setDatabasePath(getDir("database", MODE_PRIVATE).getPath());

        // Enable additional settings for development mode
        if (DEVELOPMENT_MODE) {
            webSettings.setAllowUniversalAccessFromFileURLs(true);
            webSettings.setAllowFileAccessFromFileURLs(true);
            webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            // Enable debugging only in development mode
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true);
            }
        } else {
            // Production optimizations
            webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
            // Disable debugging in production
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(false);
            }
        }

        // Add JavaScript bridge for secure storage
        JavaScriptBridge jsBridge = new JavaScriptBridge(this);
        webView.addJavascriptInterface(jsBridge, "AndroidSecureStorage");

        // Add NIP-55 bridge for result handling
        nip55Bridge = new NIP55Bridge(this);
        webView.addJavascriptInterface(nip55Bridge, "AndroidNIP55");

        // Set WebView client
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "Loading URL: " + url);
                return false; // Let WebView handle the URL
            }
        });


        // Set WebChromeClient to capture console logs
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String level = consoleMessage.messageLevel().name();
                String message = consoleMessage.message();
                String source = consoleMessage.sourceId();
                int line = consoleMessage.lineNumber();

                String logMessage = String.format("[PWA-%s] %s (%s:%d)",
                    level, message, source, line);

                switch (consoleMessage.messageLevel()) {
                    case ERROR:
                        Log.e(TAG, logMessage);
                        break;
                    case WARNING:
                        Log.w(TAG, logMessage);
                        break;
                    case DEBUG:
                        Log.d(TAG, logMessage);
                        break;
                    default:
                        Log.i(TAG, logMessage);
                        break;
                }
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                // When page is fully loaded, setup session persistence once
                if (newProgress == 100 && !sessionPersistenceSetup) {
                    sessionPersistenceSetup = true;
                    setupSessionPersistence();
                    restoreSessionPassword();
                }
            }
        });

        // Handle intent
        handleIntent(getIntent());
    }

    private void restoreSessionPassword() {
        Log.d(TAG, "Attempting to restore session password...");

        // Inject JavaScript to restore session password from Android secure storage
        String javascript = "javascript:" +
            "try {" +
            "  console.log('Android: Checking for stored session password...');" +
            "  var storedPassword = AndroidSecureStorage.getSecret('igloo_session_password');" +
            "  if (storedPassword && storedPassword !== '' && storedPassword !== null) {" +
            "    console.log('Android: Found stored session password, restoring to sessionStorage...');" +
            "    sessionStorage.setItem('igloo_session_password', storedPassword);" +
            "    console.log('Android: Session password restored successfully');" +
            "  } else {" +
            "    console.log('Android: No stored session password found');" +
            "  }" +
            "} catch (e) {" +
            "  console.error('Android: Failed to restore session password:', e.message);" +
            "}";

        webView.loadUrl(javascript);
    }

    private void setupSessionPersistence() {
        Log.d(TAG, "Setting up session persistence...");

        // Inject JavaScript to setup session persistence without overriding methods
        String javascript = "javascript:" +
            "try {" +
            "  console.log('Android: Setting up session persistence...');" +
            "  if (!window.androidSessionPersistence) {" +
            "    window.androidSessionPersistence = {" +
            "      savePassword: function(password) {" +
            "        if (password && password !== '') {" +
            "          console.log('Android: Saving session password to secure storage...');" +
            "          try {" +
            "            AndroidSecureStorage.storeSecret('igloo_session_password', password);" +
            "            console.log('Android: Session password saved to secure storage');" +
            "            return true;" +
            "          } catch (e) {" +
            "            console.error('Android: Failed to save session password:', e.message);" +
            "            return false;" +
            "          }" +
            "        }" +
            "        return false;" +
            "      }," +
            "      clearPassword: function() {" +
            "        console.log('Android: Clearing session password from secure storage...');" +
            "        try {" +
            "          AndroidSecureStorage.deleteSecret('igloo_session_password');" +
            "          console.log('Android: Session password cleared from secure storage');" +
            "          return true;" +
            "        } catch (e) {" +
            "          console.error('Android: Failed to clear session password:', e.message);" +
            "          return false;" +
            "        }" +
            "      }" +
            "    };" +
            "    console.log('Android: Session persistence API available as window.androidSessionPersistence');" +
            "  }" +
            "  console.log('Android: Session persistence setup complete');" +
            "} catch (e) {" +
            "  console.error('Android: Failed to setup session persistence:', e.message);" +
            "}";

        webView.loadUrl(javascript);
    }

    private String getBaseUrl() {
        if (DEVELOPMENT_MODE) {
            Log.d(TAG, "Using development URL: " + DEVELOPMENT_URL);
            return DEVELOPMENT_URL;
        } else {
            Log.d(TAG, "Using local web server URL");
            return webServer.getBaseUrl();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "=== ACTIVITY LIFECYCLE: onNewIntent ===");
        Log.d(TAG, "New intent received: " + intent.getAction() + ", data: " + intent.getData());
        setIntent(intent);  // Important: Update the current intent
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        // Handle update app shortcut
        if ("com.frostr.igloo.UPDATE_APP".equals(intent.getAction())) {
            Log.d(TAG, "Update app shortcut triggered");
            checkForUpdates();
            return;
        }

        Uri data = intent.getData();

        // Debug logging for isPWALoaded flag state
        Log.d(TAG, "=== INTENT HANDLING DEBUG ===");
        Log.d(TAG, "isPWALoaded flag: " + isPWALoaded);
        Log.d(TAG, "isFirstLoad flag: " + isFirstLoad);
        Log.d(TAG, "Intent action: " + intent.getAction());
        Log.d(TAG, "Intent data: " + (data != null ? data.toString() : "null"));
        Log.d(TAG, "==============================");

        if (!isPWALoaded) {
            // Very first PWA load
            String baseUrl = getBaseUrl();

            if (data != null) {
                String scheme = data.getScheme();
                Log.d(TAG, "First load with scheme: " + scheme + ", data: " + data.toString());

                if ("nostrsigner".equals(scheme) || "web+nostrsigner".equals(scheme)) {
                    String nip55Url = buildNIP55URL(intent, data);
                    baseUrl = getBaseUrl() + "/?nip55=" + Uri.encode(nip55Url);
                    Log.d(TAG, "First load PWA URL: " + baseUrl);

                    // Set pending request ID for result handling
                    String requestId = intent.getStringExtra("id");
                    if (requestId != null && nip55Bridge != null) {
                        nip55Bridge.setPendingRequestId(requestId);
                    }
                }
            }

            Log.d(TAG, "Loading PWA at: " + baseUrl);
            webView.loadUrl(baseUrl);
            isPWALoaded = true;
            isFirstLoad = false;

        } else {
            // Subsequent intents: use JavaScript injection to avoid PWA restart
            if (data != null) {
                String scheme = data.getScheme();
                Log.d(TAG, "Subsequent intent with scheme: " + scheme + ", data: " + data.toString());

                if ("nostrsigner".equals(scheme) || "web+nostrsigner".equals(scheme)) {
                    String nip55Url = buildNIP55URL(intent, data);
                    String encodedUrl = Uri.encode(nip55Url);

                    // Set pending request ID for result handling
                    String requestId = intent.getStringExtra("id");
                    if (requestId != null && nip55Bridge != null) {
                        nip55Bridge.setPendingRequestId(requestId);
                    }

                    // Use JavaScript injection to avoid PWA restart and maintain login state
                    // Use a safe method that doesn't rely on history.pushState to avoid security errors
                    String javascript = "javascript:" +
                        "console.log('Android: Processing subsequent NIP-55 request: " + nip55Url + "');" +
                        "if (window.location.origin !== 'null' && window.location.origin !== '') {" +
                        "  try {" +
                        "    window.history.pushState({}, '', '/?nip55=' + encodeURIComponent('" + encodedUrl + "'));" +
                        "    console.log('Android: URL updated to: ' + window.location.href);" +
                        "    setTimeout(() => {" +
                        "      console.log('Android: Dispatching popstate event');" +
                        "      window.dispatchEvent(new PopStateEvent('popstate', {}));" +
                        "    }, 100);" +
                        "  } catch (e) {" +
                        "    console.log('Android: pushState failed, using direct method: ' + e.message);" +
                        "    setTimeout(() => {" +
                        "      if (window.handleNIP55Request) {" +
                        "        console.log('Android: Calling handleNIP55Request directly');" +
                        "        window.handleNIP55Request('" + nip55Url + "');" +
                        "      } else {" +
                        "        console.log('Android: handleNIP55Request not available, reloading');" +
                        "        window.location.href = '/?nip55=' + encodeURIComponent('" + encodedUrl + "');" +
                        "      }" +
                        "    }, 500);" +
                        "  }" +
                        "} else {" +
                        "  console.log('Android: Origin is null, waiting for page load');" +
                        "  setTimeout(() => {" +
                        "    console.log('Android: Retrying URL update after delay');" +
                        "    if (window.location.origin !== 'null' && window.location.origin !== '') {" +
                        "      try {" +
                        "        window.history.pushState({}, '', '/?nip55=' + encodeURIComponent('" + encodedUrl + "'));" +
                        "        window.dispatchEvent(new PopStateEvent('popstate', {}));" +
                        "      } catch (e) {" +
                        "        window.location.href = '/?nip55=' + encodeURIComponent('" + encodedUrl + "');" +
                        "      }" +
                        "    } else {" +
                        "      window.location.href = '/?nip55=' + encodeURIComponent('" + encodedUrl + "');" +
                        "    }" +
                        "  }, 1000);" +
                        "}";

                    Log.d(TAG, "Injecting JavaScript for subsequent NIP-55 request: " + nip55Url);
                    webView.loadUrl(javascript);
                }
            }
        }
    }

    private String buildNIP55URL(Intent intent, Uri data) {
        // Extract the path/data from the original URI (without scheme)
        String originalData = data.toString();
        String pathData = "";
        if (originalData.startsWith("nostrsigner:")) {
            pathData = originalData.substring("nostrsigner:".length());
        } else if (originalData.startsWith("web+nostrsigner:")) {
            pathData = originalData.substring("web+nostrsigner:".length());
        }

        Log.d(TAG, "Building NIP-55 URL with path data: " + pathData);

        // Build URI with scheme and intent extras
        Uri.Builder builder = new Uri.Builder()
            .scheme("nostrsigner");

        // Add path data if present (for sign_event, encrypt, decrypt operations)
        if (!pathData.isEmpty() && !pathData.startsWith("?")) {
            builder.path(pathData.split("\\?")[0]); // Remove any existing query params
        }

        // Extract and add intent extras as query parameters
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value != null) {
                    Log.d(TAG, "Intent extra: " + key + " = " + value.toString());
                    builder.appendQueryParameter(key, value.toString());
                }
            }
        }

        // Extract existing query parameters from the original data URI
        if (pathData.contains("?")) {
            String queryPart = pathData.substring(pathData.indexOf("?") + 1);
            String[] params = queryPart.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=", 2);
                if (keyValue.length == 2) {
                    Log.d(TAG, "URI param: " + keyValue[0] + " = " + keyValue[1]);
                    builder.appendQueryParameter(keyValue[0], keyValue[1]);
                }
            }
        }

        String result = builder.build().toString();
        Log.d(TAG, "Built NIP-55 URL: " + result);
        return result;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }


    private void checkForUpdates() {
        Log.d(TAG, "Checking for app updates...");

        // In development mode, use local dev server
        if (DEVELOPMENT_MODE) {
            refreshFromDevServer();
        } else {
            // In production, this could check a remote endpoint for updates
            // For now, just refresh the local cache
            refreshLocalCache();
        }
    }

    private void refreshFromDevServer() {
        Log.d(TAG, "Refreshing from development server...");
        webView.clearCache(true);
        webView.clearHistory();

        // Reset state flags to force fresh load
        isPWALoaded = false;
        isFirstLoad = true;


        // Get current base URL (without any NIP-55 parameters)
        String baseUrl = getBaseUrl();
        Log.d(TAG, "Reloading fresh PWA from dev server: " + baseUrl);
        webView.loadUrl(baseUrl);

        // Update flags after load
        isPWALoaded = true;
        isFirstLoad = false;

        Log.d(TAG, "Development refresh completed");
    }

    private void refreshLocalCache() {
        Log.d(TAG, "Refreshing local app cache...");

        // Clear WebView cache to force reload of assets
        webView.clearCache(true);
        webView.clearHistory();

        // Reset state flags
        isPWALoaded = false;
        isFirstLoad = true;


        // Reload from local web server (in production, this serves bundled assets)
        String baseUrl = getBaseUrl();
        Log.d(TAG, "Reloading PWA with fresh cache: " + baseUrl);
        webView.loadUrl(baseUrl);

        // Update flags after load
        isPWALoaded = true;
        isFirstLoad = false;

        Log.d(TAG, "Local cache refresh completed");

        // In the future, this could:
        // 1. Check a remote version endpoint
        // 2. Download new assets if available
        // 3. Show user-friendly update notifications
        // 4. Track version numbers and show changelog
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "=== ACTIVITY LIFECYCLE: onStart ===");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "=== ACTIVITY LIFECYCLE: onResume ===");

        // Resume WebView operations
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "=== ACTIVITY LIFECYCLE: onPause ===");

        // Pause WebView to save memory and CPU
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "=== ACTIVITY LIFECYCLE: onStop ===");

        // Free up memory when app is not visible
        if (webView != null) {
            webView.freeMemory();
        }

        // Suggest garbage collection to free up memory
        System.gc();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "=== ACTIVITY LIFECYCLE: onDestroy ===");
        if (webServer != null) {
            webServer.stop();
        }
    }
}