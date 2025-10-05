# NIP-55 Background Signing Architecture Analysis

## Executive Summary

This document analyzes the current state of NIP-55 background signing implementation in the Igloo Android application. The app successfully implements NIP-55 login and permission prompts, but encounters fundamental Android WebView limitations when attempting true headless background signing for pre-approved operations.

**Current Status:**
- ✅ NIP-55 Login: Working perfectly
- ✅ Permission Prompts: Working perfectly
- ❌ Background Signing (pre-approved): **Not working** due to WebView rendering limitations

---

## Application Architecture

### Core Components

#### 1. **Progressive Web Application (PWA)**
- **Location**: `src/` directory (React + TypeScript)
- **Purpose**: Implements custom cryptography for Nostr signing operations
- **Key Features**:
  - Custom crypto implementations (no standard Kotlin libraries available)
  - User session and key management
  - Runs inside Android WebView
  - Communicates with native code via `AsyncBridge` (WebMessageListener API)

#### 2. **MainActivity** (`MainActivity.kt` - 721 lines)
- **WebView Host**: Loads and maintains the PWA at `http://localhost:3000`
- **Visible UI**: Handles user interactions requiring UI (login, prompts)
- **PWA State**:
  - Always has PWA fully loaded when app is running
  - Contains user session and cryptographic keys
  - Successfully reaches 100% load progress

#### 3. **InvisibleNIP55Handler** (`InvisibleNIP55Handler.kt` - 526 lines)
- **Entry Point**: Receives NIP-55 intents from external apps (e.g., Amethyst)
- **Translucent Activity**: Theme configured as invisible to user
- **Routing Logic**:
  - Parses and validates NIP-55 requests
  - Checks permissions from encrypted storage
  - Routes based on permission status:
    - `"denied"` → Return error immediately
    - `"prompt_required"` → Launch MainActivity with prompt UI
    - `"allowed"` → **Attempt headless signing** (this is where problems occur)

#### 4. **IglooBackgroundService** (`IglooBackgroundService.kt` - 784 lines)
- **Foreground Service**: Manages background operations
- **WebSocket Manager**: Handles persistent Nostr relay connections
- **On-Demand PWA Loading**: Attempts to load PWA when needed for signing
- **Current Issue**: Cannot successfully load PWA to completion

#### 5. **PendingNIP55ResultRegistry** (`PendingNIP55ResultRegistry.kt`)
- **Cross-Task Communication**: Singleton registry for result delivery
- **Purpose**: Bridges task boundaries between InvisibleNIP55Handler (runs in external app's task) and MainActivity (runs in Igloo's task)
- **Mechanism**: Thread-safe callback registration and delivery

---

## Current Signing Flow

### 1. Working Flow: Permission Prompts
```
External App (Amethyst)
    ↓ (nostrsigner: intent)
InvisibleNIP55Handler
    ↓ (check permission → "prompt_required")
    ↓ (register callback in PendingNIP55ResultRegistry)
    ↓ (launch MainActivity with startActivityForResult)
MainActivity
    ↓ (PWA already loaded to 100%)
    ↓ (show permission prompt UI)
    ↓ (user approves/denies)
    ↓ (PWA performs crypto operation)
    ↓ (deliver result via PendingNIP55ResultRegistry)
InvisibleNIP55Handler
    ↓ (receive result from registry)
    ↓ (return to Amethyst via setResult + finish)
External App (Amethyst) ✅
```

**Status**: ✅ **Working perfectly**

### 2. Broken Flow: Background Signing (Pre-Approved)
```
External App (Amethyst)
    ↓ (nostrsigner: intent)
InvisibleNIP55Handler
    ↓ (check permission → "allowed")
    ↓ (bind to IglooBackgroundService)
IglooBackgroundService
    ↓ (attempt to load PWA on-demand)
    ↓ (WebView creation in Service context)
    ↓ (loadUrl("http://localhost:3000"))
    ❌ (STUCK at 80% progress)
    ❌ (PWA never reaches 100%, never signals ready)
    ⏱️ (timeout after 30 seconds)
    ↓ (return error to InvisibleNIP55Handler)
InvisibleNIP55Handler
    ↓ (return error to Amethyst)
External App (Amethyst) ❌
```

**Status**: ❌ **Fails - PWA cannot load in background service**

---

## The Core Problem: Headless WebView Rendering

### Observed Behavior

When `IglooBackgroundService` attempts to load the PWA:

```kotlin
// IglooBackgroundService.kt (lines 448-530)
webView = WebView(applicationContext).apply {
    layoutParams = ViewGroup.LayoutParams(1, 1)
}

webView!!.webChromeClient = object : WebChromeClient() {
    override fun onProgressChanged(view: WebView, progress: Int) {
        Log.d(TAG, "[PWA LOAD] Progress: $progress%")
        if (progress == 100) {
            onPWALoaded()  // Never called!
        }
    }
}

webView!!.loadUrl("http://localhost:3000")
```

**Logs show:**
```
[PWA LOAD] Progress: 10%
[PWA LOAD] Progress: 70%
[PWA LOAD] Progress: 80%
[PWA LOAD] waitForPWAReady: Still waiting... (timeout after 30s)
```

**WebView never reaches 100% progress** when running in Service context without a visible window.

### Why This Happens

1. **Android WebView Rendering Requirements**:
   - WebView is fundamentally designed as a UI component
   - Requires attachment to a window for full rendering pipeline
   - Service context has no natural window attachment point

2. **Progressive Rendering**:
   - WebView can partially load (up to 70-80%) without window
   - Final rendering phases (JavaScript execution, layout finalization) require window attachment
   - Modern React PWAs need full rendering to initialize

3. **No User Session in Service**:
   - Even if WebView could load, service has no user session
   - PWA would need to re-authenticate
   - Cryptographic keys are tied to MainActivity's PWA instance

---

## Attempted Solutions

### Attempt 1: Headless WebView (Initial Approach)
**Method**: Create WebView directly in Service context
**Result**: ❌ Stuck at 80% progress
**Reason**: WebView not attached to window, cannot complete rendering

### Attempt 2: Window Attachment with TYPE_APPLICATION_OVERLAY
**Method**:
```kotlin
windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
overlayParams = WindowManager.LayoutParams(
    1, 1,  // 1x1 pixel
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    PixelFormat.TRANSLUCENT
)
windowManager?.addView(webView, overlayParams)
```

**Result**: ❌ Permission denied
**Error**: `BadTokenException: Unable to add window -- permission denied for window type 2038`
**Reason**: `TYPE_APPLICATION_OVERLAY` requires `SYSTEM_ALERT_WINDOW` permission with runtime user approval

### Attempt 3: Window Attachment with TYPE_TOAST
**Method**:
```kotlin
@Suppress("DEPRECATION")
WindowManager.LayoutParams.TYPE_TOAST  // Doesn't require special permission
```

**Result**: ❌ Builds succeeded but changes not reflected in running app
**Issue**: Kotlin compilation cache or service persistence prevented new code from running
**Observed**: Logs still showed old code path without window attachment

### Attempt 4: MainActivity Delegation with FLAG_ACTIVITY_NO_ANIMATION
**Method**:
```kotlin
Intent(this, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NO_ANIMATION
    // ... signing request data
}
startActivityForResult(mainIntent, 0)
```

**Result**: ❌ Ugly focus switching and multiple instances
**Issues**:
- MainActivity still became visible despite NO_ANIMATION flag
- Created multiple Igloo instances in task stack
- User experience degraded (visible app switching)

**Status**: Reverted immediately

---

## Technical Constraints

### 1. WebView Lifecycle Constraints
- **Problem**: WebView is a View component, designed for Activity context
- **Service Context**: No natural lifecycle for View attachment
- **Window Requirement**: Full rendering requires window manager attachment

### 2. Cross-Task Communication
- **Problem**: InvisibleNIP55Handler runs in external app's task (Amethyst)
- **MainActivity**: Runs in Igloo's own task
- **Standard APIs**: `startActivityForResult` doesn't work across task boundaries
- **Current Solution**: `PendingNIP55ResultRegistry` singleton works, but requires visible activity launch

### 3. Session State Isolation
- **MainActivity**: Contains loaded PWA with user session and keys
- **IglooBackgroundService**: Separate instance, would need separate PWA load and auth
- **No Shared State**: WebView instances cannot share JavaScript execution contexts

### 4. Custom Cryptography Dependency
- **PWA Required**: All crypto operations must run in PWA (custom implementations)
- **No Native Alternative**: Cannot replicate crypto in Kotlin (custom, non-standard algorithms)
- **JavaScript Execution**: Must have fully-rendered WebView with running JavaScript environment

---

## Current Working State

### What Works ✅

1. **NIP-55 Login** (`get_public_key` with permissions)
   - InvisibleNIP55Handler → MainActivity (with prompt)
   - User approves permissions
   - PWA returns public key
   - Result delivered back to caller app

2. **NIP-55 Permission Prompts** (any operation requiring approval)
   - InvisibleNIP55Handler checks storage
   - If not approved, launches MainActivity with prompt
   - User sees UI, approves/denies
   - Result delivered via PendingNIP55ResultRegistry
   - Works perfectly across task boundaries

3. **MainActivity-Based Signing** (when user is present)
   - MainActivity's PWA is always loaded
   - Has user session and keys
   - Can sign immediately when UI is acceptable

### What Doesn't Work ❌

1. **Background Signing for Pre-Approved Operations**
   - Permission stored as "allowed" in encrypted storage
   - Should sign without UI/focus switching
   - Currently fails because:
     - IglooBackgroundService cannot load PWA
     - WebView stuck at 80% progress
     - No window attachment working solution
     - 30-second timeout → error returned to caller

---

## Alternative Architectures Considered

### Option 1: Accept MainActivity Visibility
**Approach**: Always delegate to MainActivity, even for "allowed" requests
**Pros**: Works reliably, PWA always loaded
**Cons**:
- Visible focus switching (UX degradation)
- Multiple instances in task stack
- Not truly "background" signing
- User sees Igloo app flash on screen

**Status**: Rejected due to poor UX

### Option 2: Native Kotlin Crypto
**Approach**: Implement signing in Kotlin instead of PWA
**Pros**: No WebView needed, true background signing
**Cons**:
- **Impossible**: Uses custom cryptography with no Kotlin libraries
- Would require complete crypto reimplementation
- Risk of incompatible implementations

**Status**: Not viable due to custom crypto

### Option 3: Persistent MainActivity in Background
**Approach**: Keep MainActivity running invisibly, bind to it from InvisibleNIP55Handler
**Pros**: PWA would stay loaded
**Cons**:
- MainActivity is an Activity, must be visible or paused
- Cannot have invisible running Activity
- Android lifecycle would destroy it
- Violates Android design principles

**Status**: Architecturally impossible

### Option 4: IPC with Running MainActivity
**Approach**: If MainActivity is already running, communicate directly instead of launching new instance
**Current Issue**:
- How to detect if MainActivity is running and in foreground?
- How to send signing request to existing instance?
- What if MainActivity is paused/stopped?

**Status**: Partially explored, needs investigation

---

## Comparison with Other Signers

### Amber (Reference Implementation)
**Behavior**: Always shows MainActivity for ALL signing operations
- Even for pre-approved operations, shows UI briefly
- Acceptable UX because it's consistent
- Users expect to see the signer app

**Key Difference**: Amber doesn't attempt headless signing

### Desired Behavior (Igloo)
**Goal**: True background signing for approved operations
- No focus switching
- No visible UI
- Seamless experience in calling app

**Challenge**: Requires headless WebView with custom crypto

---

## Open Questions for Expert Review

### 1. WebView Rendering in Service Context
**Question**: Is there any way to make WebView fully render (100% progress) in a Service context without visible window attachment?

**Constraints**:
- Service lifecycle (no Activity)
- TYPE_TOAST deprecated, may stop working
- TYPE_APPLICATION_OVERLAY requires user permission
- Must execute JavaScript fully (React PWA initialization)

### 2. Window Attachment Alternatives
**Question**: Are there other window types or attachment methods that:
- Don't require runtime permissions
- Allow full WebView rendering
- Work in Service context
- Don't cause visible UI

**Attempted**:
- ❌ TYPE_APPLICATION_OVERLAY (permission denied)
- ❌ TYPE_TOAST (deprecated, changes not taking effect)

### 3. Activity Communication Patterns
**Question**: How to communicate with running MainActivity instance from InvisibleNIP55Handler across task boundaries?

**Current Method**: Launch new MainActivity instance
**Problem**: Creates duplicate instances, visible switching

**Needed**: Way to detect and bind to existing MainActivity if running

### 4. Persistent WebView Architecture
**Question**: Can WebView persist across activity lifecycle in a Service?

**Constraints**:
- WebView is View (needs Activity context)
- Service is not UI component
- View lifecycle tied to window
- JavaScript context must stay alive

### 5. Build/Compilation Mystery
**Question**: Why did window attachment code changes not reflect in running app?

**Observations**:
- Source code verified correct (Step 1: Creating invisible overlay window...)
- Gradle build succeeded
- APK installed
- Logs showed old code path (Step 1: Creating WebView in service context...)
- Force-stop and reinstall didn't help

**Possible Causes**:
- Kotlin incremental compilation cache?
- Service persistence after APK update?
- DEX compilation issue?

---

## Log Evidence

### Successful MainActivity Load (Working)
```
MainActivity: [PWA LOAD] Progress: 10%
MainActivity: [PWA LOAD] Progress: 70%
MainActivity: [PWA LOAD] Progress: 80%
MainActivity: [PWA LOAD] Progress: 90%
MainActivity: [PWA LOAD] Progress: 100%
MainActivity: [PWA LOAD] ✓ PWA fully loaded and ready
MainActivity: PWA State: ACTIVE
```

### Failed Background Service Load (Broken)
```
IglooBackgroundService: [PWA LOAD] Starting on-demand PWA load...
IglooBackgroundService: [PWA LOAD] Step 1: Creating WebView in service context...
IglooBackgroundService: [PWA LOAD] ✓ WebView created
IglooBackgroundService: [PWA LOAD] Progress: 10%
IglooBackgroundService: [PWA LOAD] Progress: 70%
IglooBackgroundService: [PWA LOAD] Progress: 80%
IglooBackgroundService: [PWA LOAD] waitForPWAReady: Still waiting... (908ms elapsed)
IglooBackgroundService: [PWA LOAD] waitForPWAReady: Still waiting... (1910ms elapsed)
[... timeout continues for 30 seconds ...]
IglooBackgroundService: [PWA LOAD] ✗ Failed to load PWA
IglooBackgroundService: kotlinx.coroutines.JobCancellationException: Job was cancelled
```

### Window Attachment Permission Error
```
WindowManager: Couldn't add view: android.webkit.WebView{...}
WindowManager: android.view.WindowManager$BadTokenException:
    Unable to add window ... -- permission denied for window type 2038
```

---

## Recommendations Requested

1. **WebView in Service**: Viable approach or fundamental limitation?

2. **Window Attachment**: Any permission-free window types that work?

3. **Architecture Alternative**: Better pattern for this use case?

4. **Detection Pattern**: Way to find and use existing MainActivity instance?

5. **Compilation Issue**: How to ensure Kotlin changes actually deploy?

6. **Comparative Analysis**: How do other apps (Amber, etc.) handle this? Are we over-engineering?

---

## Code References

- **InvisibleNIP55Handler**: `app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt`
  - Entry point: `onCreate()` (line 52)
  - Permission routing: lines 67-86
  - Background service binding: `bindToBackgroundServiceAndSign()` (line 435)

- **IglooBackgroundService**: `app/src/main/kotlin/com/frostr/igloo/IglooBackgroundService.kt`
  - PWA loading: `loadPWA()` (line 448)
  - Progress tracking: `WebChromeClient` (line 506)
  - NIP-55 processing: `processNIP55Request()` (line 676)

- **MainActivity**: `app/src/main/kotlin/com/frostr/igloo/MainActivity.kt`
  - PWA hosting: `onCreate()` (line 85)
  - NIP-55 handling: `handleNIP55SigningRequest()` (line 126)

- **PendingNIP55ResultRegistry**: `app/src/main/kotlin/com/frostr/igloo/PendingNIP55ResultRegistry.kt`
  - Cross-task communication (entire file)

---

## Appendix: NIP-55 Protocol Context

**NIP-55** (Nostr Implementation Possibility 55) defines external signer communication:
- Apps request signing via `nostrsigner:` URI scheme
- Signer app receives intent, performs operation, returns result
- Designed for security: user approves permissions once, then automatic signing

**Key Operations**:
- `get_public_key`: Login (get user's public key)
- `sign_event`: Sign Nostr event
- `nip04_encrypt/decrypt`: Legacy encryption
- `nip44_encrypt/decrypt`: Modern encryption
- `decrypt_zap_event`: Decrypt payment request

**Permission Model**:
- First request: Show prompt, user approves/denies
- Subsequent requests: Check stored permission
  - If denied: Return error
  - If allowed: **Should sign without UI** ← This is the broken part

---

**Document Version**: 1.0
**Date**: 2025-10-01
**Status**: Background signing blocked by WebView rendering limitations
