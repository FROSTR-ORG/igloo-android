# NIP-55 Signing Pipeline Architecture Report

**Comprehensive Analysis of NIP-55 Implementation Across Three Applications**

**Date**: 2025-10-05
**Applications Analyzed**: Amethyst (client), Igloo (signer), Amber (reference signer)
**Protocol**: NIP-55 (Nostr Implementation Possibility 55) - Android Signer Protocol

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Protocol Overview: NIP-55](#protocol-overview-nip-55)
3. [Amethyst - Client Implementation](#amethyst---client-implementation)
4. [Amber - Reference Signer Implementation](#amber---reference-signer-implementation)
5. [Igloo - Advanced Signer Implementation](#igloo---advanced-signer-implementation)
6. [Complete Signing Flow Sequence Diagrams](#complete-signing-flow-sequence-diagrams)
7. [Architectural Comparisons](#architectural-comparisons)
8. [Known Issues and Lessons Learned](#known-issues-and-lessons-learned)
9. [Technical Deep Dives](#technical-deep-dives)
10. [Recommendations](#recommendations)

---

## Executive Summary

This report documents the complete NIP-55 signing pipeline architecture across three Android applications:

- **Amethyst**: A Nostr client that requests signing operations from external signer apps
- **Amber**: The reference implementation signer that handles NIP-55 requests via Intent
- **Igloo**: An advanced signer with PWA-based cryptography and sophisticated permission management

### Key Findings

1. **Dual Request Methods**: Amethyst implements both background (ContentResolver) and foreground (Intent) request methods, with automatic fallback
2. **Cross-Task Communication**: Igloo implements a novel registry pattern to handle result delivery across Android task boundaries
3. **Background Signing Limitation**: Headless background signing (without UI) is fundamentally blocked by Android WebView rendering requirements
4. **Permission Models**: Different apps use different permission strategies - Amber shows UI for every request, Igloo attempts granular permission caching

### Working vs. Non-Working Flows

✅ **Working Flows**:
- Amethyst → Amber: All operations (always shows UI)
- Amethyst → Igloo: Login and permission prompts
- Amethyst → Igloo: Pre-approved operations WITH MainActivity visibility

❌ **Non-Working Flow**:
- Amethyst → Igloo: True headless background signing (PWA cannot load in Service context)

---

## Protocol Overview: NIP-55

### What is NIP-55?

NIP-55 defines a protocol for Android applications to delegate Nostr event signing to external signer applications using Android Intents. This enables separation of concerns:

- **Client apps** (like Amethyst) focus on UI/UX
- **Signer apps** (like Amber/Igloo) handle private key security and cryptographic operations

### Core Operations

| Operation | Purpose | Required Parameters |
|-----------|---------|---------------------|
| `get_public_key` | Login / identity verification | Optional: bulk permissions array |
| `sign_event` | Sign Nostr event | `event` (unsigned JSON) |
| `nip04_encrypt` | Legacy DM encryption | `plaintext`, `pubkey` |
| `nip04_decrypt` | Legacy DM decryption | `ciphertext`, `pubkey` |
| `nip44_encrypt` | Modern encryption | `plaintext`, `pubkey` |
| `nip44_decrypt` | Modern decryption | `ciphertext`, `pubkey` |
| `decrypt_zap_event` | Decrypt private zap | `event` (zap request JSON) |

### Communication Mechanisms

NIP-55 supports two communication methods:

1. **Foreground Intent** (traditional, always supported):
   - Uses `nostrsigner://` URI scheme
   - Launches signer app via `startActivityForResult()`
   - Returns result via `setResult()` + `finish()`
   - Always shows signer app UI (activity becomes visible)
   - Default timeout: 30 seconds

2. **Background ContentResolver** (optional optimization):
   - Uses `content://[package].[operation]` URI
   - Queries signer's ContentProvider for instant results
   - No UI shown if permission pre-approved
   - Client falls back to foreground if unsupported

---

## Amethyst - Client Implementation

**Repository**: `/home/cscott/Repos/frostr/pwa/repos/amethyst`
**Language**: Kotlin
**Architecture**: Modular library design with dual-method support

### Key Components

#### 1. NostrSignerExternal
**File**: `quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/client/NostrSignerExternal.kt`

Main signer interface that coordinates between background and foreground handlers.

**Initialization** (lines 41-50):
```kotlin
class NostrSignerExternal(
    pubKey: HexKey,
    packageName: String,
    contentResolver: ContentResolver,
) : NostrSigner(pubKey), IActivityLauncher {
    val backgroundQuery = BackgroundRequestHandler(pubKey, packageName, contentResolver)
    val foregroundQuery = ForegroundRequestHandler(pubKey, packageName)
```

**Request Strategy** (lines 66-92):
```kotlin
override suspend fun <T : Event> sign(
    createdAt: Long, kind: Int, tags: Array<Array<String>>, content: String,
): T {
    val unsignedEvent = Event(...)

    // Try background first, fall back to foreground
    val result = backgroundQuery.sign(unsignedEvent)
                 ?: foregroundQuery.sign(unsignedEvent)

    if (result is SignerResult.RequestAddressed.Successful<SignResult>) {
        return result.result.event as T
    }
    throw convertExceptions("Could not sign", result)
}
```

**Key Design Pattern**: Try background ContentResolver first (fast, no UI), automatically fall back to foreground Intent if:
- ContentProvider returns `null` (not implemented)
- ContentProvider throws exception
- Permission not granted

#### 2. BackgroundRequestHandler
**File**: `quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/client/handlers/BackgroundRequestHandler.kt`

Handles background ContentResolver queries.

**Structure** (lines 43-56):
```kotlin
class BackgroundRequestHandler(
    loggedInUser: HexKey,
    packageName: String,
    contentResolver: ContentResolver,
) {
    val login = LoginQuery(packageName, contentResolver)
    val sign = SignQuery(loggedInUser, packageName, contentResolver)
    val nip04Encrypt = Nip04EncryptQuery(loggedInUser, packageName, contentResolver)
    val nip04Decrypt = Nip04DecryptQuery(loggedInUser, packageName, contentResolver)
    val nip44Encrypt = Nip44EncryptQuery(loggedInUser, packageName, contentResolver)
    val nip44Decrypt = Nip44DecryptQuery(loggedInUser, packageName, contentResolver)
    val decryptZap = DecryptZapQuery(loggedInUser, packageName, contentResolver)
    val deriveKey = DeriveKeyQuery(loggedInUser, packageName, contentResolver)
}
```

Each query type builds a `content://` URI and calls `contentResolver.query()`.

**Example Query** (LoginQuery.kt, lines 39-52):
```kotlin
val uri = "content://$packageName.${CommandType.GET_PUBLIC_KEY}".toUri()

fun query(): SignerResult<PubKeyResult> =
    contentResolver.query(uri, LOGIN) { cursor ->
        val pubkeyHex = cursor.getStringByName("result")
        if (!pubkeyHex.isNullOrBlank()) {
            SignerResult.RequestAddressed.Successful(PubKeyResult(pubkeyHex, packageName))
        } else {
            SignerResult.RequestAddressed.ReceivedButCouldNotPerform()
        }
    }
```

**ContentResolver URI Format**:
```
content://[signer_package].[operation]
Example: content://com.greenart7c3.nostrsigner.sign_event
```

**Returns**: `SignerResult` with typed result or error state

#### 3. ForegroundRequestHandler
**File**: `quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/client/handlers/ForegroundRequestHandler.kt`

Handles foreground Intent-based requests with user interaction.

**Structure** (lines 42-47):
```kotlin
class ForegroundRequestHandler(
    val loggedInUser: HexKey,
    val packageName: String,
    foregroundApprovalTimeout: Long = 30000,  // Default 30 second timeout
) {
    val launcher = IntentRequestManager(foregroundApprovalTimeout)
}
```

**Sign Request** (lines 49-53):
```kotlin
suspend fun sign(unsignedEvent: Event) =
    launcher.launchWaitAndParse(
        requestIntentBuilder = { SignRequest.assemble(unsignedEvent, loggedInUser, packageName) },
        parser = { intent -> SignResponse.parse(intent, unsignedEvent) },
    )
```

#### 4. IntentRequestManager
**File**: `quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/api/foreground/IntentRequestManager.kt`

Core foreground request lifecycle manager with timeout support.

**Key Features**:
- Generates unique request IDs (32 random characters)
- Stores coroutine continuations in LRU cache (max 2000 entries)
- Implements timeout using `tryAndWait()` utility
- Handles batch responses (multiple results in one Intent)

**Request Flow** (lines 113-150):
```kotlin
suspend fun <T : IResult> launchWaitAndParse(
    requestIntentBuilder: () -> Intent,
    parser: (intent: IntentResult) -> SignerResult.RequestAddressed<T>,
): SignerResult.RequestAddressed<T> =
    appLauncher?.let { launcher ->
        val requestIntent = requestIntentBuilder()
        val callId = RandomInstance.randomChars(32)

        requestIntent.putExtra("id", callId)
        requestIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        try {
            val resultIntent = tryAndWait(foregroundApprovalTimeout) { continuation ->
                continuation.invokeOnCancellation {
                    awaitingRequests.remove(callId)
                }

                awaitingRequests.put(callId, continuation)
                launcher.invoke(requestIntent)
            }

            when (resultIntent) {
                null -> SignerResult.RequestAddressed.TimedOut()
                else -> parser(resultIntent)
            }
        } catch (e: ActivityNotFoundException) {
            SignerResult.RequestAddressed.SignerNotFound()
        }
    } ?: SignerResult.RequestAddressed.NoActivityToLaunchFrom()
```

**Response Handling** (lines 80-97):
```kotlin
fun newResponse(data: Intent) {
    val results = data.getStringExtra("results")
    if (results != null) {
        // Batch response - multiple results at once
        IntentResult.fromJsonArray(results).forEach { result ->
            if (result.id != null) {
                awaitingRequests[result.id]?.resume(result)
                awaitingRequests.remove(result.id)
            }
        }
    } else {
        // Single response
        val result = IntentResult.fromIntent(data)
        if (result.id != null) {
            awaitingRequests[result.id]?.resume(result)
            awaitingRequests.remove(result.id)
        }
    }
}
```

#### 5. SignRequest Assembly
**File**: `quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/api/foreground/intents/requests/SignRequest.kt`

Creates Intent for sign_event operation.

**Intent Format** (lines 30-42):
```kotlin
fun assemble(event: Event, loggedInUser: HexKey, packageName: String): Intent {
    val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:${event.toJson()}".toUri())
    intent.`package` = packageName
    intent.putExtra("type", CommandType.SIGN_EVENT.code)
    intent.putExtra("current_user", loggedInUser)
    return intent
}
```

**Intent Structure**:
- **Action**: `ACTION_VIEW`
- **Data URI**: `nostrsigner:[unsigned_event_json]`
- **Package**: Target signer package (e.g., `com.greenart7c3.nostrsigner`)
- **Extras**:
  - `type`: Operation type string (`"sign_event"`)
  - `current_user`: Logged-in user's public key (hex)
  - `id`: Unique request ID (added by IntentRequestManager)

### Timeout Behavior

**Default Timeout**: 30,000ms (30 seconds) - configured in ForegroundRequestHandler constructor

**Timeout Results**:
- `SignerResult.RequestAddressed.TimedOut()` - Thrown as exception by `convertExceptions()`
- Exception type: `SignerExceptions.TimedOutException`
- User sees error: "Could not sign: User didn't accept or reject in time"

**Timeout Handling** (NostrSignerExternal.kt, lines 193):
```kotlin
is SignerResult.RequestAddressed.TimedOut<*> ->
    SignerExceptions.TimedOutException("$title: User didn't accept or reject in time.")
```

### Request Lifecycle Summary

1. **Initiate Request**: App calls signer method (e.g., `sign()`)
2. **Background Attempt**: Query ContentProvider via `content://` URI
3. **Background Result Check**:
   - If successful: Return result immediately
   - If null/error: Continue to step 4
4. **Foreground Fallback**:
   - Generate unique request ID
   - Build Intent with `nostrsigner://` URI
   - Launch signer app with `startActivityForResult()`
   - Store coroutine continuation in cache
5. **User Interaction**: Signer app shows UI, user approves/denies
6. **Result Delivery**: Signer calls `setResult()` with result Intent
7. **Resume Coroutine**: IntentRequestManager resumes waiting coroutine
8. **Parse Response**: Extract result from Intent extras
9. **Return to Caller**: Deliver signed event/encrypted data/etc.

---

## Amber - Reference Signer Implementation

**Repository**: `/home/cscott/Repos/frostr/pwa/repos/Amber`
**Language**: Kotlin + Jetpack Compose
**Architecture**: Traditional Activity-based with Compose UI

### Overview

Amber is the canonical NIP-55 signer implementation by the protocol author. It demonstrates the standard approach:
- Always shows UI for every request
- No background signing optimization
- Simple, reliable, consistent UX

### Key Components

#### 1. MainActivity
**File**: `app/src/main/java/com/greenart7c3/nostrsigner/MainActivity.kt`

**onCreate Flow** (lines 61-267):
1. Start foreground service (`Amber.instance.startService()`)
2. Set app foreground flag (`Amber.isAppInForeground = true`)
3. Show loading screen while app initializes
4. Check if launched from history (clear stale intents)
5. Get calling package name from `callingPackage`
6. Authenticate user (biometrics/PIN if configured)
7. Show AccountScreen with request handling UI

**Intent Handling** (lines 83-90):
```kotlin
val packageName = callingPackage
val appName = if (packageName != null) {
    val info = applicationContext.packageManager.getApplicationInfo(packageName, 0)
    applicationContext.packageManager.getApplicationLabel(info).toString()
} else {
    null
}
```

**Authentication Gate** (lines 112-160):
- Checks last auth time vs. configured interval
- Shows biometric/PIN prompt if time elapsed
- Blocks UI until authenticated
- Configurable intervals: Every time, 1min, 5min, 10min

**onNewIntent** (lines 283-287):
```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    mainViewModel.onNewIntent(intent, callingPackage)
}
```

Delegates intent processing to MainViewModel.

### Amber Request Flow

```
External App (Amethyst)
    ↓
Send Intent: nostrsigner://[data]
    extras: type, current_user, id
    ↓
Android System
    ↓
Launch Amber MainActivity
    callingPackage = [amethyst_package]
    ↓
Amber onCreate/onNewIntent
    ↓
Show Authentication (if needed)
    ↓
MainViewModel.onNewIntent()
    ↓
Parse request from Intent
    ↓
Show AccountScreen with request UI
    ↓
User approves/denies
    ↓
setResult(RESULT_OK/RESULT_CANCELED, resultIntent)
finish()
    ↓
Android System
    ↓
Return to Amethyst
```

### Key Differences from Igloo

| Feature | Amber | Igloo |
|---------|-------|-------|
| UI Visibility | Always visible | Attempts invisible signing |
| Permission Model | Per-request approval | Granular caching with prompt/allow/deny |
| Cryptography | Kotlin native | PWA JavaScript (custom algorithms) |
| Background Service | Simple foreground service | Attempted on-demand PWA loading |
| Cross-Task Communication | Direct setResult() | Custom registry pattern |
| Architecture | Traditional Activity | Hybrid Activity + WebView + PWA |

### Amber Strengths

✅ **Simple and Reliable**: Every request shows UI, no hidden state
✅ **Consistent UX**: Users always see what they're signing
✅ **No Async Complexity**: Synchronous request-response via Activity lifecycle
✅ **Reference Implementation**: Demonstrates standard NIP-55 behavior

---

## Igloo - Advanced Signer Implementation

**Repository**: `/home/cscott/Repos/frostr/pwa`
**Languages**: Kotlin (Android), TypeScript (PWA)
**Architecture**: Hybrid - Android shell hosting React PWA with bridge communication

### Architecture Overview

Igloo implements a sophisticated three-layer architecture:

1. **Android Layer**: Intent handling, permission checking, WebView hosting
2. **Bridge Layer**: Secure communication between Kotlin and JavaScript
3. **PWA Layer**: Custom cryptography, session management, UI components

### Android Components

#### 1. InvisibleNIP55Handler
**File**: `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt`

**Purpose**: Transparent Intent receiver that routes NIP-55 requests without showing UI.

**Theme Configuration** (AndroidManifest.xml):
```xml
<activity
    android:name=".InvisibleNIP55Handler"
    android:theme="@android:style/Theme.Translucent.NoTitleBar"
    android:excludeFromRecents="true"
    android:launchMode="singleTop">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="nostrsigner" />
    </intent-filter>
</activity>
```

**onCreate Flow** (lines 58-143):
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "NIP-55 request received from external app (onCreate)")
    processNIP55Intent(intent)
}
```

**Request Processing** (lines 88-143):
```kotlin
private fun processNIP55Intent(intent: Intent) {
    try {
        val newRequest = parseNIP55Request(intent)

        // Deduplicate by event ID for sign_event
        val isDuplicate = checkForDuplicate(newRequest)
        if (isDuplicate) {
            Log.w(TAG, "Ignoring duplicate request")
            return
        }

        // Add to queue
        pendingRequests.add(newRequest)

        // Process next request
        processNextRequest()
    } catch (e: Exception) {
        returnError("Failed to parse request: ${e.message}")
        finish()
    }
}
```

**Permission Routing** (lines 167-201):
```kotlin
private fun processNextRequest() {
    isProcessingRequest = true
    originalRequest = pendingRequests.first()

    // Special handling for get_public_key with bulk permissions
    if (originalRequest.type == "get_public_key" &&
        originalRequest.params.containsKey("permissions")) {
        showBulkPermissionDialog()
        return
    }

    // Check permissions
    val permissionStatus = checkPermission(originalRequest)

    when (permissionStatus) {
        "denied" -> returnError("Permission denied")
        "allowed" -> launchMainActivityForFastSigning()
        "prompt_required" -> showSinglePermissionDialog()
    }
}
```

**Permission Checking with Kind Support** (lines 708-773):
```kotlin
private fun checkPermission(request: NIP55Request): String {
    val storageBridge = StorageBridge(this)
    val permissionsJson = storageBridge.getItem("local", "nip55_permissions_v2")
        ?: return "prompt_required"

    val storage = gson.fromJson(permissionsJson, PermissionStorage::class.java)
    val permissions = storage.permissions

    // Extract event kind for sign_event
    var eventKind: Int? = null
    if (request.type == "sign_event" && request.params.containsKey("event")) {
        val eventMap = gson.fromJson<Map<String, Any>>(eventJson, Map::class.java)
        eventKind = (eventMap["kind"] as? Double)?.toInt()
    }

    // For sign_event, check kind-specific permission first, then wildcard
    if (request.type == "sign_event" && eventKind != null) {
        val kindSpecific = permissions.find { p ->
            p.appId == request.callingApp &&
            p.type == request.type &&
            p.kind == eventKind
        }
        if (kindSpecific != null) {
            return if (kindSpecific.allowed) "allowed" else "denied"
        }

        // Fall back to wildcard
        val wildcard = permissions.find { p ->
            p.appId == request.callingApp &&
            p.type == request.type &&
            p.kind == null
        }
        if (wildcard != null) {
            return if (wildcard.allowed) "allowed" else "denied"
        }
    }

    return "prompt_required"
}
```

**Fast Signing Launch** (lines 425-479):
```kotlin
private fun launchMainActivityForFastSigning() {
    // Start foreground service to keep process alive
    NIP55SigningService.start(this)

    // Register callback for result delivery
    PendingNIP55ResultRegistry.registerCallback(originalRequest.id,
        object : PendingNIP55ResultRegistry.ResultCallback {
            override fun onResult(result: NIP55Result) {
                if (result.ok && result.result != null) {
                    returnResult(result.result)
                } else {
                    returnError(result.reason ?: "Signing failed")
                }
                cleanup()
            }
        })

    // Set timeout
    timeoutHandler.postDelayed({
        PendingNIP55ResultRegistry.cancelCallback(originalRequest.id)
        if (!isCompleted) {
            returnError("Signing operation timeout")
            cleanup()
        }
    }, REQUEST_TIMEOUT_MS)  // 30 seconds

    // Launch MainActivity with signing data
    val mainIntent = Intent(this, MainActivity::class.java).apply {
        action = "com.frostr.igloo.NIP55_SIGNING"
        putExtra("nip55_request_id", originalRequest.id)
        putExtra("nip55_request_type", originalRequest.type)
        putExtra("nip55_request_calling_app", originalRequest.callingApp)
        putExtra("nip55_request_params", gson.toJson(originalRequest.params))
        putExtra("nip55_permission_status", "allowed")
        putExtra("nip55_show_prompt", false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    startActivity(mainIntent)
    moveTaskToBack(true)  // Hide this activity, show MainActivity
}
```

**Result Delivery** (lines 208-252):
```kotlin
private fun returnResult(result: String) {
    if (isCompleted) return
    isCompleted = true

    pendingRequests.removeAll { it.id == originalRequest.id }
    isProcessingRequest = false

    val resultIntent = Intent().apply {
        putExtra("signature", result)
        putExtra("result", result)
        putExtra("id", originalRequest.id)

        // For sign_event, include signed event
        if (originalRequest.type == "sign_event") {
            putExtra("event", result)
        }

        // For get_public_key, include package name
        if (originalRequest.type == "get_public_key") {
            putExtra("package", packageName)
        }
    }

    Log.d(TAG, "Returning RESULT_OK to calling app")
    setResult(RESULT_OK, resultIntent)
    finish()  // Android automatically returns to calling activity
}
```

#### 2. PendingNIP55ResultRegistry
**File**: `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/PendingNIP55ResultRegistry.kt`

**Purpose**: Thread-safe singleton for cross-task result delivery.

**Problem**: InvisibleNIP55Handler runs in Amethyst's task, MainActivity runs in Igloo's task. Standard `startActivityForResult()` doesn't work across task boundaries.

**Solution**: Application-wide singleton registry accessible from both contexts.

**Implementation** (lines 18-74):
```kotlin
object PendingNIP55ResultRegistry {
    private const val TAG = "NIP55ResultRegistry"

    // Thread-safe map of request ID to callback
    private val pendingCallbacks = ConcurrentHashMap<String, ResultCallback>()

    fun registerCallback(requestId: String, callback: ResultCallback) {
        Log.d(TAG, "Registering callback for request: $requestId")
        pendingCallbacks[requestId] = callback
    }

    fun deliverResult(requestId: String, result: NIP55Result): Boolean {
        Log.d(TAG, "Attempting to deliver result for request: $requestId")
        val callback = pendingCallbacks.remove(requestId)

        return if (callback != null) {
            Log.d(TAG, "✓ Callback found, invoking...")
            try {
                callback.onResult(result)
                true
            } catch (e: Exception) {
                Log.e(TAG, "✗ Callback threw exception", e)
                false
            }
        } else {
            Log.w(TAG, "✗ No callback registered for request: $requestId")
            false
        }
    }

    fun cancelCallback(requestId: String) {
        pendingCallbacks.remove(requestId)
    }

    interface ResultCallback {
        fun onResult(result: NIP55Result)
    }
}
```

**Usage Pattern**:
1. InvisibleNIP55Handler registers callback before launching MainActivity
2. MainActivity executes signing operation via PWA
3. MainActivity calls `PendingNIP55ResultRegistry.deliverResult()`
4. Registry invokes InvisibleNIP55Handler's callback
5. InvisibleNIP55Handler calls `setResult()` and `finish()`
6. Result returns to Amethyst

#### 3. MainActivity
**File**: `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/MainActivity.kt`

**Purpose**: WebView host for PWA, handles NIP-55 execution.

**onCreate Actions** (lines 65-136):
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Register as active instance
    activeInstance = this

    // Clear WebView cache (development mode)
    clearWebViewCache()

    when (intent.action) {
        "com.frostr.igloo.SHOW_PERMISSION_DIALOG" -> {
            handlePermissionDialogRequest()
        }
        "com.frostr.igloo.NIP55_SIGNING" -> {
            val showPrompt = intent.getBooleanExtra("nip55_show_prompt", false)
            if (!showPrompt) {
                // Fast signing - skip UI overlay
            }
            initializeSecureWebView()
            loadSecurePWA()
            handleNIP55Request()
        }
        else -> {
            // Normal PWA startup
            initializeSecureWebView()
            loadSecurePWA()
            handleIntent(intent)
        }
    }
}
```

**WebView Initialization** (lines 435-497):
```kotlin
private fun initializeSecureWebView() {
    setContentView(R.layout.activity_main)

    webView = WebView(this)
    webViewContainer.addView(webView)

    configureSecureWebView()  // Security settings
    registerSecureBridges()   // Add JavaScript interfaces

    webView.webViewClient = IglooWebViewClient(this)
    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress == 100 && !isSecurePWALoaded) {
                isSecurePWALoaded = true
                onSecurePWAReady()
            }
        }
    }
}
```

**Bridge Registration** (lines 535-578):
```kotlin
private fun registerSecureBridges() {
    // AsyncBridge for NIP-55 communication
    asyncBridge = AsyncBridge(webView)
    asyncBridge.initialize()

    // WebSocket bridge
    webSocketBridge = WebSocketBridge(webView)
    webView.addJavascriptInterface(webSocketBridge, "WebSocketBridge")

    // Storage bridge (encrypted Android Keystore)
    storageBridge = StorageBridge(this)
    webView.addJavascriptInterface(storageBridge, "StorageBridge")

    // Camera bridge
    cameraBridge = ModernCameraBridge(this, webView)
    webView.addJavascriptInterface(cameraBridge, "CameraBridge")

    // Signing bridge
    signingService = UnifiedSigningService(this, webView)
    signingBridge = UnifiedSigningBridge(this, webView)
    signingBridge.initialize(signingService)
    webView.addJavascriptInterface(signingBridge, "UnifiedSigningBridge")
}
```

**NIP-55 Request Processing** (lines 348-429):
```kotlin
private fun processNIP55Request(request: NIP55Request, callingApp: String,
                                  replyPendingIntent: PendingIntent?) {
    if (!::webView.isInitialized) {
        initializeSecureWebView()
        loadSecurePWA()
    }

    activityScope.launch {
        // Wait for PWA to load (max 30 seconds)
        var waitCount = 0
        while (!isSecurePWALoaded && waitCount < 30) {
            delay(1000)
            waitCount++
        }

        if (!isSecurePWALoaded) {
            sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                putExtra("error", "PWA failed to load")
            })
            return@launch
        }

        // Call AsyncBridge for NIP-55 execution
        val result = asyncBridge.callNip55Async(
            request.type, request.id, request.callingApp, request.params
        )

        withContext(Dispatchers.Main) {
            if (result.ok && result.result != null) {
                sendReply(replyPendingIntent, RESULT_OK, Intent().apply {
                    putExtra("id", request.id)
                    putExtra("result", result.result)
                    if (request.type == "sign_event") {
                        putExtra("event", result.result)
                    }
                })
            } else {
                sendReply(replyPendingIntent, RESULT_CANCELED, Intent().apply {
                    putExtra("error", result.reason ?: "Request failed")
                })
            }
        }
    }
}
```

**Result Delivery Methods**:

1. **Registry Pattern** (for cross-task):
```kotlin
val delivered = PendingNIP55ResultRegistry.deliverResult(requestId, result)
```

2. **Direct setResult** (for same-task):
```kotlin
private fun sendReply(pendingIntent: PendingIntent?, resultCode: Int, data: Intent) {
    setResult(resultCode, data)
    finish()
}
```

#### 4. UnifiedSigningBridge
**File**: `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/bridges/UnifiedSigningBridge.kt`

**Purpose**: JavaScript interface for PWA to Android signing communication.

**JavaScript Interface** (lines 59-99):
```kotlin
@JavascriptInterface
fun signEvent(eventJson: String, callbackId: String): String {
    val requestId = callbackId
    pendingCallbacks[requestId] = callbackId

    val signingRequest = SigningRequest(
        id = requestId,
        type = "sign_event",
        payload = eventJson,
        callingApp = PWA_CALLER_ID,
        timestamp = System.currentTimeMillis()
    )

    bridgeScope.launch {
        handlePWASigningRequest(signingRequest)
    }

    return gson.toJson(mapOf(
        "requestId" to requestId,
        "status" to "processing"
    ))
}
```

### PWA Components

#### 1. NIP55Bridge Component
**File**: `/home/cscott/Repos/frostr/pwa/src/components/nip55-bridge.tsx`

**Purpose**: React component that initializes the NIP-55 JavaScript API.

**Initialization** (lines 21-66):
```typescript
useEffect(() => {
  // Initialize when node is online or locked
  if (node.status === 'online' || node.status === 'locked') {
    try {
      // Create signing bridge function
      const signing_bridge = create_signing_bridge()

      // Create consolidated bridge interface
      const bridge: NIP55Bridge = {
        ready: true,
        nodeClient: node.client || null,
        autoSign: executeAutoSigning
      }

      // Expose on window.nostr
      if (!window.nostr) {
        window.nostr = {}
      }
      window.nostr.nip55 = signing_bridge
      window.nostr.bridge = bridge

      set_bridge_ready(true)
      console.log(`NIP-55 bridge initialized (status: ${node.status})`)
    } catch (error) {
      console.error('Failed to initialize NIP-55 bridge:', error)
    }
  }
}, [node.client, node.status])
```

#### 2. Signer Library
**File**: `/home/cscott/Repos/frostr/pwa/src/lib/signer.ts`

**Main Signing Bridge** (lines 145-175):
```typescript
export function create_signing_bridge(): NIP55WindowAPI {
  return async (request: NIP55Request): Promise<NIP55Result> => {
    const start_time = Date.now()

    try {
      if (!request.id || !request.type) {
        throw new Error('Invalid request: missing id or type')
      }

      // Execute signing directly - Android already checked permissions
      const result = await executeAutoSigning(request)

      const duration = Date.now() - start_time
      console.log(`Auto-signing completed in ${duration}ms`)

      return result
    } catch (error) {
      return {
        ok: false,
        type: request.type,
        id: request.id,
        reason: error instanceof Error ? error.message : 'Unknown error'
      }
    }
  }
}
```

**Auto-Signing Execution** (lines 57-132):
```typescript
export async function executeAutoSigning(request: NIP55Request): Promise<NIP55Result> {
  try {
    // Check bridge availability
    if (!window.nostr?.bridge?.ready) {
      throw new Error('NIP-55 bridge not ready')
    }

    let nodeClient = window.nostr.bridge.nodeClient

    // For get_public_key, can read from settings when locked
    if (!nodeClient && request.type === 'get_public_key') {
      const stored_settings_json = localStorage.getItem('igloo-pwa')
      if (stored_settings_json) {
        const settings = JSON.parse(stored_settings_json)
        if (settings.pubkey) {
          return {
            ok: true,
            type: request.type,
            id: request.id,
            result: settings.pubkey
          }
        }
      }
      throw new Error('No public key available')
    }

    // For signing operations, wait for auto-unlock if session password exists
    if (!nodeClient && request.type !== 'get_public_key') {
      const sessionPassword = sessionStorage.getItem('igloo_session_password')
      if (sessionPassword) {
        nodeClient = await waitForNodeClient(3000)
        if (!nodeClient) {
          throw new Error('Auto-unlock timed out')
        }
      } else {
        throw new Error('Node is locked')
      }
    }

    // Create signer and execute
    const signer = new BifrostSignDevice(nodeClient)
    const result = await executeSigningOperation(signer, request)

    return {
      ok: true,
      type: request.type,
      id: request.id,
      result: result || ''
    }
  } catch (error) {
    return {
      ok: false,
      type: request.type,
      id: request.id,
      reason: error instanceof Error ? error.message : 'Unknown signing error'
    }
  }
}
```

**Operation Dispatcher** (lines 14-33):
```typescript
export async function executeSigningOperation(
  signer: BifrostSignDevice,
  request: NIP55Request
): Promise<any> {
  switch (request.type) {
    case 'get_public_key':
      return signer.get_pubkey()
    case 'sign_event':
      return await signer.sign_event(request.event)
    case 'nip04_encrypt':
      return await signer.nip04_encrypt(request.pubkey, request.plaintext)
    case 'nip04_decrypt':
      return await signer.nip04_decrypt(request.pubkey, request.ciphertext)
    case 'nip44_encrypt':
      return await signer.nip44_encrypt(request.pubkey, request.plaintext)
    case 'nip44_decrypt':
      return await signer.nip44_decrypt(request.pubkey, request.ciphertext)
    case 'decrypt_zap_event':
      throw new Error('decrypt_zap_event not implemented')
    default:
      throw new Error(`Unknown request type: ${request.type}`)
  }
}
```

#### 3. Bridge Type Definitions
**File**: `/home/cscott/Repos/frostr/pwa/src/types/bridge.ts`

```typescript
export interface NIP55Bridge {
  ready: boolean
  nodeClient: BifrostNode | null
  autoSign: (request: NIP55Request) => Promise<NIP55Result>
}

declare global {
  interface Window {
    nostr: {
      nip55?: (request: NIP55Request) => Promise<NIP55Result>
      bridge?: NIP55Bridge
    }
  }
}
```

---

## Complete Signing Flow Sequence Diagrams

### Flow 1: Amethyst → Amber (Always Shows UI)

```
┌──────────┐                  ┌─────────────┐                  ┌────────┐
│ Amethyst │                  │Android System│                 │ Amber  │
└────┬─────┘                  └──────┬──────┘                  └───┬────┘
     │                               │                              │
     │ 1. user action (post, like)   │                              │
     ├──────────────────────────────►│                              │
     │                               │                              │
     │ 2. NostrSignerExternal.sign() │                              │
     │    ├─ Try background query    │                              │
     │    │  (returns null - not impl)│                             │
     │    └─ Fall back to foreground │                              │
     │                               │                              │
     │ 3. Build Intent               │                              │
     │    nostrsigner://[event_json] │                              │
     │    extras: type, current_user │                              │
     │    id: [random_32_chars]      │                              │
     ├──────────────────────────────►│                              │
     │                               │                              │
     │                               │ 4. Launch Amber MainActivity │
     │                               │     callingPackage=amethyst  │
     │                               ├─────────────────────────────►│
     │                               │                              │
     │                               │                              │ 5. onCreate()
     │                               │                              │    - Start service
     │                               │                              │    - Authenticate user
     │                               │                              │
     │                               │                              │ 6. Parse Intent
     │                               │                              │    - Extract event
     │                               │                              │    - Get app name
     │                               │                              │
     │                               │                              │ 7. Show UI
     │                               │                              │    "Amethyst wants
     │                               │                              │     to sign event"
     │                               │                              │
     │                               │                              │ 8. User clicks
     │                               │                              │    "Approve"
     │                               │                              │
     │                               │                              │ 9. Sign event
     │                               │                              │    with private key
     │                               │                              │
     │                               │ 10. setResult(RESULT_OK)     │
     │                               │     extras:                  │
     │                               │       id=[request_id]        │
     │                               │       signature=[sig]        │
     │                               │       event=[signed_event]   │
     │                               │◄─────────────────────────────┤
     │                               │                              │
     │                               │ 11. finish()                 │
     │                               │◄─────────────────────────────┤
     │                               │                              │
     │ 12. onActivityResult()        │                              │
     │     resultCode=RESULT_OK      │                              │
     │◄──────────────────────────────┤                              │
     │                               │                              │
     │ 13. Parse result              │                              │
     │     Extract signed event      │                              │
     │                               │                              │
     │ 14. Publish to relays         │                              │
     │                               │                              │

Total Time: ~2-5 seconds (depends on user interaction)
UI Visibility: Amber always visible
```

### Flow 2: Amethyst → Igloo (Permission Prompt Required)

```
┌──────────┐     ┌─────────────────┐     ┌──────────┐     ┌──────────────────┐
│ Amethyst │     │ Invisible       │     │ Registry │     │    MainActivity  │
│          │     │ NIP55Handler    │     │          │     │    (WebView+PWA) │
└────┬─────┘     └────┬────────────┘     └────┬─────┘     └────┬─────────────┘
     │                │                        │                │
     │ 1. Sign request│                        │                │
     ├───────────────►│                        │                │
     │  Intent:       │                        │                │
     │  nostrsigner://│                        │                │
     │                │                        │                │
     │                │ 2. onCreate()          │                │
     │                │    Parse request       │                │
     │                │                        │                │
     │                │ 3. checkPermission()   │                │
     │                │    → "prompt_required" │                │
     │                │                        │                │
     │                │ 4. registerCallback()  │                │
     │                ├───────────────────────►│                │
     │                │    (requestId)         │                │
     │                │                        │                │
     │                │ 5. showSinglePermissionDialog()         │
     │                │    Launch MainActivity │                │
     │                │    with dialog action  │                │
     │                ├────────────────────────┼───────────────►│
     │                │    Intent extras:      │                │
     │                │    - app_id            │                │
     │                │    - request_type      │                │
     │                │    - request_id        │                │
     │                │    - nip55_request_*   │                │
     │                │                        │                │
     │                │ 6. moveTaskToBack(true)│                │
     │                │    (hide self)         │                │
     │                │                        │                │
     │                │                        │                │ 7. onCreate()
     │                │                        │                │    action=SHOW_PERMISSION_DIALOG
     │                │                        │                │
     │                │                        │                │ 8. initializeSecureWebView()
     │                │                        │                │    - WebView created
     │                │                        │                │    - Bridges registered
     │                │                        │                │
     │                │                        │                │ 9. loadSecurePWA()
     │                │                        │                │    igloo://app/index.html
     │                │                        │                │
     │                │                        │                │ 10. Show native dialog
     │                │                        │                │     NIP55PermissionDialog
     │                │                        │                │     "Amethyst wants to
     │                │                        │                │      sign_event (kind 1)"
     │                │                        │                │
     │                │                        │                │ 11. User clicks "Approve"
     │                │                        │                │     Permission saved to
     │                │                        │                │     encrypted storage
     │                │                        │                │
     │                │                        │                │ 12. Wait for PWA load
     │                │                        │                │     (onProgressChanged=100%)
     │                │                        │                │
     │                │                        │                │ 13. processNIP55Request()
     │                │                        │                │     asyncBridge.callNip55Async()
     │                │                        │                │
     │                │                        │                │ 14. PWA: window.nostr.nip55()
     │                │                        │                │     - Check bridge ready
     │                │                        │                │     - Get nodeClient
     │                │                        │                │     - Create signer
     │                │                        │                │     - Sign event
     │                │                        │                │
     │                │                        │                │ 15. Return result
     │                │                        │                │     { ok: true,
     │                │                        │                │       result: signed_event }
     │                │                        │                │
     │                │                        │ 16. deliverResult()
     │                │    onResult() callback │                │
     │                │◄───────────────────────┼────────────────┤
     │                │    NIP55Result         │                │
     │                │                        │                │
     │                │ 17. returnResult()     │                │
     │                │     setResult(RESULT_OK)                │
     │                │     Intent extras:     │                │
     │                │       signature        │                │
     │                │       result           │                │
     │                │       event            │                │
     │                │     finish()           │                │
     │                │                        │                │
     │ 18. Result     │                        │                │
     │◄───────────────┤                        │                │
     │    RESULT_OK   │                        │                │
     │                │                        │                │

Total Time: ~3-7 seconds
UI Visibility: MainActivity visible for dialog + signing
Cross-Task Communication: Via PendingNIP55ResultRegistry
```

### Flow 3: Amethyst → Igloo (Permission Already Allowed - Fast Signing)

```
┌──────────┐     ┌─────────────────┐     ┌──────────┐     ┌──────────────────┐
│ Amethyst │     │ Invisible       │     │ Registry │     │    MainActivity  │
│          │     │ NIP55Handler    │     │          │     │    (WebView+PWA) │
└────┬─────┘     └────┬────────────┘     └────┬─────┘     └────┬─────────────┘
     │                │                        │                │
     │ 1. Sign request│                        │                │
     ├───────────────►│                        │                │
     │                │                        │                │
     │                │ 2. onCreate()          │                │
     │                │    Parse request       │                │
     │                │                        │                │
     │                │ 3. checkPermission()   │                │
     │                │    Read encrypted storage               │
     │                │    Find: {             │                │
     │                │      appId: "amethyst" │                │
     │                │      type: "sign_event"│                │
     │                │      kind: 1           │                │
     │                │      allowed: true     │                │
     │                │    }                   │                │
     │                │    → "allowed" ✓       │                │
     │                │                        │                │
     │                │ 4. Start NIP55SigningService            │
     │                │    (keeps process alive)                │
     │                │                        │                │
     │                │ 5. registerCallback()  │                │
     │                ├───────────────────────►│                │
     │                │                        │                │
     │                │ 6. setTimeout(30s)     │                │
     │                │                        │                │
     │                │ 7. launchMainActivityForFastSigning()   │
     │                ├────────────────────────┼───────────────►│
     │                │    Intent extras:      │                │
     │                │    - nip55_request_id  │                │
     │                │    - nip55_request_type│                │
     │                │    - nip55_request_params               │
     │                │    - nip55_permission_status="allowed"  │
     │                │    - nip55_show_prompt=false            │
     │                │    Flags:              │                │
     │                │    - FLAG_ACTIVITY_NEW_TASK             │
     │                │    - FLAG_ACTIVITY_CLEAR_TOP            │
     │                │                        │                │
     │                │ 8. moveTaskToBack(true)│                │
     │                │    (invisible handler) │                │
     │                │                        │                │
     │                │                        │                │ 9. onCreate() or
     │                │                        │                │    onNewIntent()
     │                │                        │                │    action=NIP55_SIGNING
     │                │                        │                │
     │                │                        │                │ 10. PWA already loaded?
     │                │                        │                │     YES → skip to step 13
     │                │                        │                │     NO → continue
     │                │                        │                │
     │                │                        │                │ 11. initializeSecureWebView()
     │                │                        │                │     (if not already done)
     │                │                        │                │
     │                │                        │                │ 12. loadSecurePWA()
     │                │                        │                │     Wait for 100% load
     │                │                        │                │     Time: ~1-2 seconds
     │                │                        │                │
     │                │                        │                │ 13. processNIP55Request()
     │                │                        │                │     asyncBridge.callNip55Async()
     │                │                        │                │
     │                │                        │                │ 14. PWA: executeAutoSigning()
     │                │                        │                │     - Bridge ready? ✓
     │                │                        │                │     - Node locked?
     │                │                        │                │       → Check session password
     │                │                        │                │       → Auto-unlock (if available)
     │                │                        │                │     - Create BifrostSignDevice
     │                │                        │                │     - Sign event with crypto lib
     │                │                        │                │     Time: ~50-200ms
     │                │                        │                │
     │                │                        │ 15. deliverResult()
     │                │    Callback invoked    │                │
     │                │◄───────────────────────┼────────────────┤
     │                │    NIP55Result {       │                │
     │                │      ok: true,         │                │
     │                │      result: signed_event               │
     │                │    }                   │                │
     │                │                        │                │
     │                │ 16. returnResult()     │                │
     │                │     setResult(RESULT_OK)                │
     │                │     Intent extras:     │                │
     │                │       signature        │                │
     │                │       result           │                │
     │                │       id               │                │
     │                │       event (signed)   │                │
     │                │     finish()           │                │
     │                │                        │                │
     │ 17. Result     │                        │                │
     │◄───────────────┤                        │                │
     │    RESULT_OK   │                        │                │
     │                │                        │                │

Total Time:
  - First request (PWA not loaded): ~2-3 seconds
  - Subsequent requests (PWA loaded): ~200-500ms

UI Visibility:
  - MainActivity MAY become visible briefly (Android task switching)
  - Splash overlay DISABLED for debugging (can be enabled)
  - InvisibleNIP55Handler stays invisible

Performance Bottleneck: WebView initialization and PWA loading
```

### Flow 4: Amethyst Background Query → Igloo (Not Implemented)

```
┌──────────┐
│ Amethyst │
└────┬─────┘
     │
     │ 1. NostrSignerExternal.sign()
     │    Try background first...
     │
     │ 2. BackgroundRequestHandler.sign()
     │    Build ContentResolver query
     │
     │ 3. ContentResolver.query()
     │    URI: content://com.frostr.igloo.sign_event
     │    Selection: [event_json, pubkey, current_user]
     ├────────────────────────────────────────────────┐
     │                                                 │
     │                                                 │
     │                         ┌───────────────────────▼────────┐
     │                         │ Igloo ContentProvider           │
     │                         │ (NOT IMPLEMENTED)               │
     │                         │                                 │
     │                         │ Would need to:                  │
     │                         │ 1. Check permissions            │
     │                         │ 2. Load PWA on-demand           │
     │                         │ 3. Execute signing              │
     │                         │ 4. Return signed event          │
     │                         │                                 │
     │                         │ Problem:                        │
     │                         │ - ContentProvider runs in       │
     │                         │   background (Service context)  │
     │                         │ - WebView cannot fully load     │
     │                         │   without window attachment     │
     │                         │ - PWA gets stuck at 80% progress│
     │                         │                                 │
     │ 4. Query returns null   │ See: NIP55_BACKGROUND_SIGNING_  │
     │    (not implemented)    │      ANALYSIS.md for details    │
     │◄────────────────────────┴─────────────────────────────────┘
     │
     │ 5. Fall back to foreground Intent
     │    (See Flow 3 above)
     │

Status: NOT IMPLEMENTED due to WebView limitations
Alternative: Direct Intent to MainActivity (Flow 3)
```

---

## Architectural Comparisons

### Request Initiation

| App | Background Method | Foreground Method | Fallback Strategy |
|-----|------------------|-------------------|-------------------|
| **Amethyst** | ContentResolver query | Intent with timeout | Auto fallback on null |
| **Amber** | Not supported | Intent only | N/A |
| **Igloo** | Not implemented | Intent with routing | N/A (client-side fallback) |

### Permission Management

| App | Permission Storage | Permission Granularity | UI Behavior |
|-----|-------------------|----------------------|-------------|
| **Amethyst** | Client-side cache | Per-signer app | Remembers signer choice |
| **Amber** | Per-request approval | None (always prompt) | Always shows UI |
| **Igloo** | Encrypted Keystore | Per-app, per-operation, per-kind | Caches granular permissions |

**Igloo Permission Structure**:
```json
{
  "permissions": [
    {
      "appId": "com.vitorpamplona.amethyst",
      "type": "sign_event",
      "kind": 1,
      "allowed": true
    },
    {
      "appId": "com.vitorpamplona.amethyst",
      "type": "sign_event",
      "kind": null,
      "allowed": false
    }
  ]
}
```

Lookup precedence:
1. Exact match (appId + type + kind)
2. Wildcard match (appId + type + kind=null)
3. Default: prompt_required

### Cryptography Implementation

| App | Language | Library | Key Storage |
|-----|----------|---------|-------------|
| **Amethyst** | Kotlin | Secp256k1 JNI | N/A (client, not signer) |
| **Amber** | Kotlin | Native secp256k1 | Android Keystore |
| **Igloo** | TypeScript (PWA) | @noble/ciphers, @frostr/bifrost | WebView localStorage + Android Keystore backup |

**Why Igloo Uses PWA Crypto**:
- Custom cryptographic implementations not available in Kotlin libraries
- Bifrost protocol requires specific signing algorithms
- PWA allows code reuse across web and mobile
- Android Keystore used for session backup only

### WebView Architecture

| Feature | Igloo | Impact |
|---------|-------|--------|
| **WebView Mode** | Custom protocol (`igloo://app/`) | Secure asset loading |
| **JavaScript Bridges** | WebSocketBridge, StorageBridge, CameraBridge, UnifiedSigningBridge | Polyfill web APIs |
| **Storage Backend** | Android Keystore (AES256-GCM) | Encrypted at rest |
| **Service Worker** | Supported | PWA offline capabilities |
| **Network Access** | Blocked by WebViewClient | Only `igloo://` allowed |

### Cross-Task Communication Patterns

**Problem**: InvisibleNIP55Handler runs in Amethyst's task, MainActivity runs in Igloo's task.

**Standard Android Solution** (doesn't work):
```kotlin
// This FAILS across task boundaries
startActivityForResult(intent, REQUEST_CODE)
```

**Igloo's Solution**:
```kotlin
// 1. Register callback in singleton registry
PendingNIP55ResultRegistry.registerCallback(requestId, callback)

// 2. Launch MainActivity with NEW_TASK flag
startActivity(intent.addFlags(FLAG_ACTIVITY_NEW_TASK))

// 3. MainActivity executes signing, delivers to registry
PendingNIP55ResultRegistry.deliverResult(requestId, result)

// 4. Registry invokes callback in original context
callback.onResult(result)

// 5. Original handler returns to caller
setResult(RESULT_OK)
finish()
```

**Why This Works**:
- Singleton object lives in Application scope (shared across all activities)
- Thread-safe ConcurrentHashMap
- Callback interfaces allow cross-context communication
- No dependency on Activity lifecycle

---

## Known Issues and Lessons Learned

### From NIP55_BACKGROUND_SIGNING_ANALYSIS.md

#### Issue 1: WebView Rendering in Service Context

**Problem**: WebView cannot reach 100% loading progress when created in a Service context (no visible window).

**Manifestation**:
```
IglooBackgroundService: [PWA LOAD] Progress: 10%
IglooBackgroundService: [PWA LOAD] Progress: 70%
IglooBackgroundService: [PWA LOAD] Progress: 80%
[... stuck forever at 80% ...]
IglooBackgroundService: [PWA LOAD] ✗ Failed to load PWA (timeout)
```

**Root Cause**:
- Android WebView is fundamentally a UI component (extends View)
- Requires attachment to WindowManager for full rendering pipeline
- Final rendering phases (JavaScript execution, layout finalization) need window context
- Service has no natural window attachment point

**Attempted Solutions**:

1. **Headless WebView** (failed):
   ```kotlin
   webView = WebView(applicationContext).apply {
       layoutParams = ViewGroup.LayoutParams(1, 1)
   }
   // Result: Stuck at 80% progress
   ```

2. **Window Attachment with TYPE_APPLICATION_OVERLAY** (failed):
   ```kotlin
   windowManager.addView(webView, WindowManager.LayoutParams(
       1, 1,
       WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
       WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
       PixelFormat.TRANSLUCENT
   ))
   // Result: BadTokenException - permission denied
   // Requires SYSTEM_ALERT_WINDOW runtime permission
   ```

3. **Window Attachment with TYPE_TOAST** (failed):
   ```kotlin
   @Suppress("DEPRECATION")
   WindowManager.LayoutParams.TYPE_TOAST
   // Result: Code changes didn't deploy (Kotlin cache issue?)
   ```

4. **MainActivity Delegation with FLAG_ACTIVITY_NO_ANIMATION** (failed):
   ```kotlin
   Intent(this, MainActivity::class.java).apply {
       flags = Intent.FLAG_ACTIVITY_NO_ANIMATION
   }
   // Result: MainActivity still visible, multiple instances created
   ```

**Current Status**: ❌ Background signing not possible

**Workaround**: Accept that MainActivity must be visible for signing operations (same as Amber)

#### Issue 2: Kotlin Compilation Cache Mystery

**Problem**: Code changes to IglooBackgroundService didn't reflect in running app despite successful build.

**Observed**:
- Source code showed `Step 1: Creating invisible overlay window...`
- Gradle build succeeded
- APK installed via `adb install -r`
- Logs showed `Step 1: Creating WebView in service context...` (old code)

**Attempted Fixes**:
- Force stop + restart app
- `adb uninstall` + `adb install`
- Clean build (`./gradlew clean build`)
- Restart Android Studio

**Suspected Causes**:
- Kotlin incremental compilation cache corruption
- Service persisting after APK update (Android keeps services alive)
- DEX compilation caching stale bytecode

**Resolution**: Never fully resolved. Eventually abandoned background service approach.

#### Issue 3: Cross-Task Result Delivery

**Problem**: `startActivityForResult()` doesn't work when launching activity in different task.

**Initial Symptom**:
```kotlin
// InvisibleNIP55Handler (in Amethyst's task)
startActivityForResult(mainIntent, 0)

// MainActivity (in Igloo's task)
setResult(RESULT_OK, resultIntent)
finish()

// InvisibleNIP55Handler.onActivityResult()
// NEVER CALLED - Android doesn't deliver result across tasks
```

**Solution**: Custom registry pattern (PendingNIP55ResultRegistry)

**Lessons Learned**:
- Android task boundaries are strict isolation boundaries
- Standard IPC mechanisms (startActivityForResult, LocalBroadcastManager) don't work
- Application-scope singletons CAN bridge task boundaries
- Thread safety critical (ConcurrentHashMap, @Volatile)

#### Issue 4: Permission Dialog Architecture

**Problem**: Where to show permission dialogs - InvisibleNIP55Handler or MainActivity?

**Constraint**: Dialogs require Activity context (not just Application context)

**Initial Attempt**: Show dialog in InvisibleNIP55Handler
```kotlin
// This FAILS - InvisibleNIP55Handler is invisible (translucent theme)
// Dialog shows but user can't see it!
```

**Solution**: Launch MainActivity with special action
```kotlin
Intent(this, MainActivity::class.java).apply {
    action = "com.frostr.igloo.SHOW_PERMISSION_DIALOG"
    putExtra("app_id", callingApp)
    putExtra("request_type", requestType)
}
```

MainActivity shows native Android dialog, stores permission, then executes signing.

#### Issue 5: Multiple Signing Instances

**Problem**: Launching MainActivity for every signing request created multiple instances in task stack.

**Symptom**:
```
[Igloo Task Stack]
MainActivity (instance 1 - original)
MainActivity (instance 2 - signing request 1)
MainActivity (instance 3 - signing request 2)
MainActivity (instance 4 - signing request 3)
```

**Solution**: Use `FLAG_ACTIVITY_CLEAR_TOP` to reuse existing instance
```kotlin
Intent(this, MainActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
```

With `CLEAR_TOP`, Android delivers to existing instance via `onNewIntent()` instead of creating new one.

#### Issue 6: Deduplication of Signing Requests

**Problem**: Amethyst sends duplicate sign_event requests when user double-taps "post" button.

**Impact**: Without deduplication, user sees two permission prompts for same event.

**Solution**: Event ID-based deduplication in InvisibleNIP55Handler
```kotlin
val isDuplicate = if (newRequest.type == "sign_event") {
    val eventId = extractEventId(newRequest.params["event"])
    pendingRequests.any { extractEventId(it.params["event"]) == eventId }
} else {
    pendingRequests.any { it.id == newRequest.id }
}

if (isDuplicate) {
    Log.w(TAG, "Ignoring duplicate request")
    return
}
```

**Key Insight**: Use event ID (deterministic hash), not request ID (random)

#### Issue 7: State Management with singleTop Launch Mode

**Problem**: InvisibleNIP55Handler uses `singleTop` launch mode. When new intent arrives via `onNewIntent()`, stale state from previous request can block processing.

**Symptom**:
```
onNewIntent: isProcessingRequest=true, isCompleted=true
// New request ignored because flags still set from previous timeout!
```

**Solution**: Reset state in `onNewIntent()`
```kotlin
override fun onNewIntent(newIntent: Intent?) {
    super.onNewIntent(newIntent)

    // CRITICAL: Reset state for new request
    isProcessingRequest = false
    isCompleted = false

    setIntent(newIntent)
    processNIP55Intent(newIntent)
}
```

**Lesson**: With `singleTop`, activity instances are reused. Always reset mutable state in `onNewIntent()`.

### Performance Insights

**PWA Loading Time**:
- Cold start (MainActivity not running): ~2-3 seconds
- Warm start (MainActivity backgrounded): ~1-2 seconds
- Hot (MainActivity foreground): ~200-500ms

**Signing Operation Time**:
- After PWA loaded: ~50-200ms
- Including PWA load: ~2-3 seconds total

**Bottlenecks**:
1. WebView initialization (~500ms)
2. PWA bundle download from assets (~1s)
3. React hydration (~500ms)
4. Bifrost node initialization (~500ms if not cached)

**Optimization Opportunities**:
- Keep MainActivity process alive (foreground service)
- Preload PWA in background on app launch
- Cache Bifrost node in memory
- Use signing overlay to hide PWA during fast signing

### Security Considerations

**Igloo Security Model**:

1. **Encrypted Storage**:
   - All permissions stored in Android Keystore
   - AES256-GCM encryption
   - Hardware-backed if available
   - Biometric unlock supported

2. **Network Isolation**:
   - WebView blocks all network requests
   - Only `igloo://` protocol allowed
   - WebSocket polyfill uses OkHttp (controlled)
   - No XSS risk (no external content)

3. **Intent Validation**:
   - Calling package verified
   - Request parameters validated
   - JSON parsing protected
   - Public key format checked (64 hex chars)

4. **Permission Model**:
   - Explicit user approval required
   - Granular by app, operation, and event kind
   - Revocable at any time
   - Denial recorded (won't prompt again)

**Attack Surface**:

1. **Malicious App Registration**:
   - Any app can send `nostrsigner://` intent
   - Mitigation: Show app name in permission dialog
   - User must explicitly approve

2. **Intent Injection**:
   - Malicious intent with crafted data
   - Mitigation: Validate all parameters, use try/catch
   - Worst case: Denial of service (crash), not key exposure

3. **WebView Exploitation**:
   - JavaScript bridge methods are attack vectors
   - Mitigation: @JavascriptInterface only on trusted methods
   - No remote code execution (no network access)

4. **Registry Manipulation**:
   - Other apps in same process could call registry
   - Mitigation: Igloo runs in isolated process
   - Registry only accessible within Igloo package

---

## Technical Deep Dives

### Deep Dive 1: Android Task Model and NIP-55

**Task**: Container for Activity stack, managed by Android system.

**Key Concepts**:

1. **Task Affinity**:
   - Activities declare preferred task via `android:taskAffinity`
   - Default: package name
   - Amethyst task affinity: `com.vitorpamplona.amethyst`
   - Igloo task affinity: `com.frostr.igloo`

2. **Launch Modes**:
   - `standard`: New instance every time
   - `singleTop`: Reuse if already on top
   - `singleTask`: Reuse and clear activities above
   - `singleInstance`: Only activity in task

3. **Intent Flags**:
   - `FLAG_ACTIVITY_NEW_TASK`: Create new task or switch to existing
   - `FLAG_ACTIVITY_CLEAR_TOP`: Clear activities above target
   - `FLAG_ACTIVITY_SINGLE_TOP`: Don't create duplicate on top

**NIP-55 Task Flow**:

```
[Amethyst Task]                    [Igloo Task]
┌────────────────┐                ┌────────────────┐
│  MainActivity  │                │  MainActivity  │
│   (Amethyst)   │                │    (Igloo)     │
│                │                │                │
│ [launches]     │                │                │
│       ↓        │                │                │
│ InvisibleNIP55 │◄──[launches]───┤                │
│    Handler     │   NEW_TASK     │                │
│ (transparent)  │                │                │
│                │                │                │
│ taskAffinity = │                │                │
│  [Amethyst]    │                │                │
└────────────────┘                └────────────────┘
      ↓ FLAG_ACTIVITY_NEW_TASK
      ↓ (switches to Igloo task)
      ↓
┌────────────────┐
│  MainActivity  │
│    (Igloo)     │
│                │
│ [executes]     │
│ [delivers via] │
│  [registry]    │
│       ↓        │
└────────────────┘
      ↓
┌────────────────┐
│ InvisibleNIP55 │
│    Handler     │
│ [receives]     │
│ [returns via]  │
│  setResult()   │
└────────────────┘
      ↓ finish()
      ↓ (returns to Amethyst task)
      ↓
┌────────────────┐
│  MainActivity  │
│   (Amethyst)   │
│ [receives]     │
│  RESULT_OK     │
└────────────────┘
```

**Why Standard IPC Fails**:
- `startActivityForResult()` expects result from same task
- Result delivery uses Activity stack traversal
- Different tasks = different stacks = no delivery path

**Why Registry Works**:
- Object lives in Application scope (process-wide)
- Accessible from all tasks in same process
- Survives Activity lifecycle transitions
- Thread-safe concurrent access

### Deep Dive 2: WebView Progressive Rendering

**WebView Loading Stages**:

| Progress | Stage | Activities | Requires Window |
|----------|-------|------------|----------------|
| 0% | Initial | WebView created | No |
| 10% | Connection | HTTP request sent | No |
| 20-40% | Download | HTML/CSS/JS received | No |
| 50-70% | Parsing | DOM construction | No |
| 80% | **Layout** | **CSS layout, measure/position** | **YES** |
| 90% | **Render** | **Paint to screen** | **YES** |
| 100% | **JavaScript** | **React hydration, execution** | **YES** |

**Why Service Context Fails at 80%**:

1. **Layout Phase Requires Window Metrics**:
   ```kotlin
   // Layout engine needs to know:
   - Screen width
   - Screen height
   - Pixel density
   - ViewPort dimensions

   // In Activity: windowManager.defaultDisplay provides these
   // In Service: NO DISPLAY = layout calculations fail
   ```

2. **Rendering Requires Surface**:
   ```kotlin
   // WebView rendering pipeline:
   View.measure()  // Needs parent dimensions
       ↓
   View.layout()   // Needs window coordinates
       ↓
   View.draw()     // Needs Canvas (from Surface)
       ↓
   GPU upload      // Needs SurfaceFlinger attachment

   // Service has no Surface = rendering pipeline broken
   ```

3. **JavaScript Requires Rendered DOM**:
   ```javascript
   // React checks for DOM before hydrating:
   if (document.getElementById('root')) {
       ReactDOM.hydrate(...)
   } else {
       // BLOCKED - DOM never fully rendered
   }
   ```

**Proof from Logs**:
```
[Activity Context]
Progress: 10% → 70% → 80% → 90% → 100% ✓
Time: ~2 seconds

[Service Context]
Progress: 10% → 70% → 80% → [STUCK]
Time: 30s timeout → failure
```

**Alternative Approaches Considered**:

1. **Offscreen WebView Rendering**:
   - Android P+ supports `WebView.setOffscreenPreRaster(true)`
   - Still requires initial window attachment
   - Doesn't solve 80% → 100% transition

2. **Headless Chrome**:
   - Full Chrome browser without UI
   - Not available as Android library
   - Massive footprint (~100MB)

3. **Native Crypto Implementation**:
   - Rewrite cryptography in Kotlin
   - Custom algorithms not in standard libraries
   - Risk of incompatible implementations
   - Would lose web version compatibility

**Conclusion**: Android architecture fundamentally prevents headless WebView operations.

### Deep Dive 3: AsyncBridge Communication

**AsyncBridge**: Custom WebMessageListener-based bridge for async PWA ↔ Kotlin communication.

**Architecture**:

```kotlin
// Kotlin Side
class AsyncBridge(private val webView: WebView) {
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<NIP55Result>>()

    fun initialize() {
        webView.addWebMessageListener(
            "asyncBridge",
            setOf("*"),
            WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, replyProxy ->
                val data = JSONObject(message.data)
                val callId = data.getString("callId")
                val result = parseNIP55Result(data)

                pendingCalls[callId]?.complete(result)
                pendingCalls.remove(callId)
            }
        )
    }

    suspend fun callNip55Async(type: String, id: String, host: String, params: Map<String, String>): NIP55Result {
        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<NIP55Result>()
        pendingCalls[callId] = deferred

        val message = JSONObject().apply {
            put("callId", callId)
            put("method", "nip55")
            put("type", type)
            put("id", id)
            put("host", host)
            put("params", JSONObject(params))
        }

        webView.evaluateJavascript(
            "window.postMessage(${message}, '*')",
            null
        )

        return withTimeout(30000) {
            deferred.await()
        }
    }
}
```

```javascript
// JavaScript Side (PWA)
window.addEventListener('message', async (event) => {
    if (event.data.method === 'nip55') {
        const { callId, type, id, host, params } = event.data

        try {
            const result = await window.nostr.nip55({
                type, id, host, ...params
            })

            // Reply via asyncBridge
            asyncBridge.postMessage(JSON.stringify({
                callId,
                ok: result.ok,
                result: result.result,
                reason: result.reason
            }))
        } catch (error) {
            asyncBridge.postMessage(JSON.stringify({
                callId,
                ok: false,
                reason: error.message
            }))
        }
    }
})
```

**Advantages over JavascriptInterface**:

1. **Async Support**: Native Promise/async-await on both sides
2. **Type Safety**: JSON parsing with error handling
3. **Timeout Handling**: Kotlin `withTimeout()` prevents hangs
4. **Concurrent Calls**: Multiple requests in flight simultaneously
5. **Modern API**: WebMessageListener (Android 6+) vs deprecated `addJavascriptInterface`

**Message Flow**:
```
Kotlin                          JavaScript
  │                                 │
  │ 1. callNip55Async()             │
  │    - Generate callId            │
  │    - Create deferred            │
  │    - Store in map               │
  │                                 │
  │ 2. evaluateJavascript()         │
  │    postMessage({...})           │
  ├────────────────────────────────►│
  │                                 │
  │                                 │ 3. Handle message event
  │                                 │    - Parse request
  │                                 │    - Call window.nostr.nip55()
  │                                 │    - Await crypto operation
  │                                 │
  │ 4. WebMessageListener           │
  │    callback triggered           │
  │◄────────────────────────────────┤ asyncBridge.postMessage()
  │                                 │
  │ 5. Find deferred by callId      │
  │    - Complete promise           │
  │    - Remove from map            │
  │                                 │
  │ 6. Return result                │
  │    to caller                    │
  │                                 │
```

---

## Recommendations

### For Igloo Development

1. **Accept MainActivity Visibility**:
   - Stop attempting invisible background signing
   - Focus on making visible signing fast and smooth
   - Use signing overlay (splash screen) during operation
   - Keep PWA loaded in foreground service for instant availability

2. **Optimize PWA Loading**:
   - Implement PWA preloading on app launch
   - Keep WebView process alive with foreground service
   - Cache Bifrost node initialization
   - Consider lazy loading heavy components

3. **Enhance Permission UX**:
   - Show "Remember this choice" checkbox
   - Add permission management UI (view/revoke)
   - Implement bulk permission approval flow
   - Consider wildcard permissions (all kinds for an app)

4. **ContentProvider Implementation**:
   - Consider implementing limited ContentProvider for `get_public_key` only
   - Can return cached public key without signing
   - Provides instant background response for login flows
   - Other operations still require MainActivity

5. **Error Handling**:
   - Add retry logic for timeout failures
   - Show better error messages to user
   - Implement crash reporting (catch uncaught exceptions)
   - Log all NIP-55 flows for debugging

### For Amethyst Integration

1. **Timeout Configuration**:
   - Consider increasing timeout to 60s for first-time operations
   - Show loading indicator immediately when sending intent
   - Allow user to cancel pending request

2. **Signer Selection**:
   - Cache user's preferred signer app
   - Provide "always use this signer" option
   - Fall back to system chooser if preferred not available

3. **Batch Operations**:
   - Group multiple sign requests when possible
   - Use batch response format (multiple results in one Intent)
   - Reduce number of task switches

### For NIP-55 Protocol

1. **Standardize ContentProvider**:
   - Define standard ContentProvider contract in NIP-55
   - Specify URI format, selection arguments, result columns
   - Make it optional but recommended

2. **Timeout Recommendations**:
   - Suggest minimum 30s timeout for foreground requests
   - Longer timeouts (60s+) for permission prompts
   - Allow signers to request more time via interim response

3. **Batch Response Format**:
   - Standardize multiple results in single Intent
   - Use JSON array in `results` extra
   - Allows signer to process queue efficiently

4. **Permission Model**:
   - Define standard permission structures
   - Recommend granularity levels (app, operation, kind)
   - Suggest expiration mechanism (time-based, count-based)

### Security Best Practices

1. **Input Validation**:
   - Always validate calling package
   - Parse JSON defensively (try/catch)
   - Limit request size (prevent DoS)
   - Check public key format (64 hex chars)

2. **Permission Storage**:
   - Use encrypted storage (Android Keystore)
   - Require biometric/PIN for permission changes
   - Allow users to view/revoke permissions

3. **Intent Security**:
   - Validate all Intent extras before use
   - Don't trust `android:exported` alone
   - Log suspicious requests
   - Rate limit requests per app

---

## Conclusion

The NIP-55 signing pipeline represents a sophisticated inter-application communication pattern that pushes the boundaries of Android's task model and activity lifecycle. This analysis has revealed:

1. **Dual-Method Support is Optimal**: Amethyst's approach of trying background ContentResolver first, falling back to foreground Intent, provides the best balance of performance and reliability.

2. **Background Signing is Fundamentally Limited**: Android WebView architecture prevents true headless operation. Services cannot render WebView to 100%. This is not a bug in Igloo - it's an Android platform limitation.

3. **Cross-Task Communication Requires Innovation**: Standard Android IPC mechanisms fail across task boundaries. Custom registry patterns (like PendingNIP55ResultRegistry) are necessary for sophisticated flows.

4. **Permission Granularity Matters**: Igloo's kind-specific permissions provide better UX than Amber's always-prompt model, but require more complex storage and lookup logic.

5. **PWA-Based Cryptography is Viable**: Despite the WebView limitations, using a PWA for custom cryptography works well in visible-activity scenarios. The hybrid architecture enables code reuse and modern development practices.

### Working Architecture Summary

✅ **What Works Today**:
- Amethyst → Amber: All operations (always visible)
- Amethyst → Igloo: Login with permission prompts
- Amethyst → Igloo: Signing with permission prompts
- Amethyst → Igloo: Pre-approved signing with MainActivity visibility
- Cross-task result delivery via custom registry

❌ **What Doesn't Work**:
- True headless background signing (WebView limitation)
- ContentProvider-based background queries (not implemented due to WebView limitation)
- Invisible signing without MainActivity becoming visible

### Final Recommendations

**For Igloo**:
- Accept MainActivity visibility as requirement
- Optimize for speed (PWA preloading, caching)
- Enhance permission UX (management screen, bulk approval)
- Consider limited ContentProvider for `get_public_key` only

**For Protocol Evolution**:
- Standardize ContentProvider contract (optional extension)
- Define batch response format
- Recommend timeout values
- Specify permission model guidelines

**For Ecosystem**:
- Document WebView limitations for future implementers
- Share cross-task communication patterns
- Establish security best practices
- Create reference implementations for both client and signer

---

## Appendices

### Appendix A: File References

**Amethyst Files**:
- `/home/cscott/Repos/frostr/pwa/repos/amethyst/quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/client/NostrSignerExternal.kt`
- `/home/cscott/Repos/frostr/pwa/repos/amethyst/quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/client/handlers/BackgroundRequestHandler.kt`
- `/home/cscott/Repos/frostr/pwa/repos/amethyst/quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/client/handlers/ForegroundRequestHandler.kt`
- `/home/cscott/Repos/frostr/pwa/repos/amethyst/quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/api/foreground/IntentRequestManager.kt`
- `/home/cscott/Repos/frostr/pwa/repos/amethyst/quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/api/foreground/intents/requests/SignRequest.kt`
- `/home/cscott/Repos/frostr/pwa/repos/amethyst/quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/api/background/queries/SignQuery.kt`
- `/home/cscott/Repos/frostr/pwa/repos/amethyst/quartz/src/androidMain/kotlin/com/vitorpamplona/quartz/nip55AndroidSigner/api/background/queries/LoginQuery.kt`

**Amber Files**:
- `/home/cscott/Repos/frostr/pwa/repos/Amber/app/src/main/java/com/greenart7c3/nostrsigner/MainActivity.kt`

**Igloo Android Files**:
- `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt`
- `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/MainActivity.kt`
- `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/PendingNIP55ResultRegistry.kt`
- `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/bridges/UnifiedSigningBridge.kt`

**Igloo PWA Files**:
- `/home/cscott/Repos/frostr/pwa/src/components/nip55-bridge.tsx`
- `/home/cscott/Repos/frostr/pwa/src/lib/signer.ts`
- `/home/cscott/Repos/frostr/pwa/src/types/bridge.ts`

**Documentation**:
- `/home/cscott/Repos/frostr/pwa/android/NIP55_BACKGROUND_SIGNING_ANALYSIS.md`

### Appendix B: Key Data Structures

**NIP55Request** (Kotlin):
```kotlin
data class NIP55Request(
    val id: String,
    val type: String,
    val params: Map<String, String>,
    val callingApp: String,
    val timestamp: Long
)
```

**NIP55Result** (Kotlin):
```kotlin
data class NIP55Result(
    val ok: Boolean,
    val type: String,
    val id: String,
    val result: String?,
    val reason: String? = null
)
```

**NIP55Request** (TypeScript):
```typescript
interface NIP55Request {
  id: string
  type: 'get_public_key' | 'sign_event' | 'nip04_encrypt' | 'nip04_decrypt' | 'nip44_encrypt' | 'nip44_decrypt' | 'decrypt_zap_event'
  host: string
  event?: string
  pubkey?: string
  plaintext?: string
  ciphertext?: string
}
```

**NIP55Result** (TypeScript):
```typescript
interface NIP55Result {
  ok: boolean
  type: string
  id: string
  result?: string
  reason?: string
}
```

**Permission** (Igloo Storage):
```kotlin
data class Permission(
    val appId: String,
    val type: String,
    val kind: Int?,
    val allowed: Boolean
)

data class PermissionStorage(
    val permissions: List<Permission>
)
```

---

**Report Version**: 1.0
**Date**: 2025-10-05
**Author**: Architecture Analysis System
**Status**: Comprehensive analysis complete
