# Android Codebase Tech Debt & Legacy Code Analysis

**Generated:** 2025-10-04
**Target:** `/home/cscott/Repos/frostr/pwa/android/`
**Package:** `com.frostr.igloo`
**Architecture:** PWA wrapper + Polyfill bridges + NIP-55 support

---

## Executive Summary

The Android wrapper has accumulated significant tech debt from multiple architectural pivots. The codebase contains **5 completely unused Java bridge classes**, duplicate permission systems, legacy WebSocket infrastructure in the background service that competes with the polyfill architecture, and over-engineered abstractions that don't align with the alpha's core mission.

**Critical Finding:** The app is trying to do two contradictory things simultaneously:
1. **Polyfill Architecture** (MainActivity): PWA uses Android-backed bridges for WebSocket, Storage, Camera
2. **Duplicate Infrastructure** (IglooBackgroundService): Background service has its own WebSocket managers, event queues, and relay connection systems

This architectural confusion leads to ~25% unnecessary code and complexity that hinders debugging and maintenance.

---

## Critical Issues

### 1. **Completely Unused Java Bridges (DELETE IMMEDIATELY)**

These 5 Java files are **NEVER imported or used** anywhere in the Kotlin codebase:

| File | Lines | Purpose | Status |
|------|-------|---------|--------|
| `LocalWebServer.java` | 162 | Legacy HTTP server for serving PWA | **UNUSED** - PWA served via `igloo://` protocol now |
| `JavaScriptBridge.java` | 55 | Old SecureStorage interface | **UNUSED** - Replaced by `StorageBridge.kt` |
| `ContentResolverBridge.java` | 205 | Old ContentProvider bridge | **UNUSED** - Never referenced |
| `NIP55Bridge.java` | 78 | Old NIP-55 interface | **UNUSED** - Replaced by `UnifiedSigningBridge.kt` |
| `SecureStorage.java` | 152 | Android Keystore wrapper | **USED** - But only by unused Java bridges |

**Impact:** These 5 files (652 lines) serve no purpose and confuse the architecture.

**Action:** Delete all 5 files. `SecureStorage.java` functionality is fully duplicated in `StorageBridge.kt`.

---

### 2. **Architectural Conflict: Dual WebSocket Systems**

The app has **TWO completely separate WebSocket implementations**:

#### System 1: Polyfill Architecture (Correct for PWA)
- **Location:** `WebSocketBridge.kt` in MainActivity
- **Purpose:** Provides WebSocket API to PWA JavaScript via polyfill
- **Status:** ✅ Working correctly, aligns with architecture

#### System 2: Background Infrastructure (Conflicting)
- **Location:** `IglooBackgroundService.kt` + 7 manager classes
- **Components:**
  - `WebSocketManager.kt` (600+ lines)
  - `RelayConnectionManager.kt` (400+ lines)
  - `SubscriptionManager.kt` (300+ lines)
  - `NostrEventQueue.kt` (350+ lines)
  - `NostrEventHandler.kt` (350+ lines)
  - `BatteryPowerManager.kt` (330+ lines)
  - `NetworkManager.kt` (200+ lines)

**The Problem:**
- Background service tries to manage WebSocket connections independently
- PWA already manages WebSockets via polyfill bridge
- Two systems compete for same relay connections
- Background service can't actually complete PWA loading (Android WebView limitation)
- Adds ~2500 lines of unused/conflicting infrastructure

**Root Cause:**
IglooBackgroundService was designed before the polyfill architecture was established. It's an abandoned architectural direction that was never cleaned up.

**Recommended Action:**
1. **Keep:** `BatteryPowerManager.kt`, `NetworkManager.kt` (useful utilities)
2. **Delete:** All WebSocket/Relay/Subscription/Event managers from background service
3. **Simplify:** IglooBackgroundService to ONLY handle NIP-55 request routing

---

### 3. **Over-Engineered NIP-55 Flow**

Current flow has 4+ hand-offs for a simple signing request:

```
External App
  → InvisibleNIP55Handler (parse, validate, check permissions)
    → MainActivity (queue management, AsyncBridge)
      → PWA JavaScript (actual signing)
        → Result back through chain
```

**Unnecessary Complexity:**
- `ContentResolverRequest` data class and queue in MainActivity (lines 31-36, 51-76)
- Concurrent queue management with `ConcurrentLinkedQueue` and `AtomicBoolean`
- Broadcast receiver system for ContentProvider replies
- Dual intent handling paths (traditional vs ContentResolver)

**Why It's Over-Engineered:**
- For an alpha, NIP-55 requests are infrequent (not thousands/hour)
- Queue system designed for concurrency that doesn't exist in practice
- ContentProvider adds complexity without clear benefit over direct intents

**Recommended Simplification:**
```
External App
  → InvisibleNIP55Handler (parse, validate, check permissions)
    → MainActivity (direct AsyncBridge call)
      → PWA (signing)
```

---

### 4. **Abandoned Background Signing Attempt**

See `NIP55_BACKGROUND_SIGNING_ANALYSIS.md` - The background service attempted "headless" PWA loading for fast signing, but it's fundamentally impossible due to Android WebView requirements.

**Evidence of Abandonment:**
- IglooBackgroundService.loadPWA() has detailed logging showing it never completes
- PWA requires visible window context to render and execute JavaScript
- Service creates 1x1 pixel invisible overlay (lines 455-473) - doesn't work
- Code remains but is non-functional

**What Should Happen:**
All signing MUST go through MainActivity with visible WebView. Background service should NOT attempt PWA loading.

**Files to Clean:**
- Remove PWA loading logic from `IglooBackgroundService.kt` (lines 429-666)
- Remove service binding code from `InvisibleNIP55Handler.kt` (lines 651-745)

---

### 5. **Duplicate Permission Storage Systems**

Three different places manage permissions:

1. **Primary:** `StorageBridge.kt` → `nip55_permissions_v2` in EncryptedSharedPreferences
2. **Legacy:** `Permission` data class in `IglooContentProvider.kt` (lines 29-35)
3. **Duplicate:** Permission checking logic in both:
   - `InvisibleNIP55Handler.kt` (lines 576-649)
   - `IglooContentProvider.kt` (lines 318-395)

**Problem:** Same permission checking logic copy-pasted with subtle differences. Changes must be made in multiple places.

**Action:** Extract to single `PermissionManager.kt` utility class.

---

## Legacy Code to Delete

### Category 1: Unused Java Bridges (652 lines)
```
android/app/src/main/java/com/frostr/igloo/
├── LocalWebServer.java               [DELETE - 162 lines]
├── JavaScriptBridge.java             [DELETE - 55 lines]
├── ContentResolverBridge.java        [DELETE - 205 lines]
├── NIP55Bridge.java                  [DELETE - 78 lines]
└── SecureStorage.java                [DELETE - 152 lines]
```

**Replacement:** All functionality exists in Kotlin bridges:
- LocalWebServer → IglooWebViewClient.kt (igloo:// protocol)
- JavaScriptBridge → StorageBridge.kt
- SecureStorage → StorageBridge.kt (EncryptedSharedPreferences)
- NIP55Bridge → UnifiedSigningBridge.kt + AsyncBridge.kt

### Category 2: Background Service WebSocket Infrastructure (~2500 lines)
```
android/app/src/main/kotlin/com/frostr/igloo/managers/
├── WebSocketManager.kt               [SIMPLIFY - keep battery/network utilities only]
├── RelayConnectionManager.kt         [DELETE - 400 lines]
├── SubscriptionManager.kt            [DELETE - 300 lines]
├── NostrEventQueue.kt                [DELETE - 350 lines]
└── NostrEventHandler.kt              [DELETE - 350 lines]
```

**Keep:**
- `BatteryPowerManager.kt` (useful for app state monitoring)
- `NetworkManager.kt` (useful for connectivity checks)

**Rationale:** PWA manages its own WebSocket connections via polyfill. Background service doesn't need parallel infrastructure.

### Category 3: Debug Infrastructure (Production Code)
```
android/app/src/main/kotlin/com/frostr/igloo/debug/
├── DebugConfig.kt                    [REVIEW - hardcoded DEBUG_ENABLED = true]
├── NIP55DebugLogger.kt               [SIMPLIFY - excessive logging in alpha]
└── RequestTracer.kt                  [REVIEW - needed?]
```

**Issue:** Debug flags are hardcoded `true` in supposedly production code. Alpha should use BuildConfig.DEBUG instead.

### Category 4: Legacy Data Models
```
android/app/src/main/kotlin/com/frostr/igloo/models/
└── NostrModels.kt                    [REVIEW - likely contains unused model classes]
```

**Action:** Audit and remove models not used by remaining managers.

### Category 5: Abandoned Service Binding
```
InvisibleNIP55Handler.kt:
- Lines 651-745: Service binding logic       [DELETE]
- Lines 674-745: Background service processing [DELETE]
```

**Rationale:** Background signing doesn't work. All requests should go to MainActivity.

---

## Build System Cleanup

### AndroidManifest.xml Issues

```xml
<!-- Line 8-12: Excessive permissions -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

**Issue:** SYSTEM_ALERT_WINDOW permission is dangerous and not needed if background signing is removed.

**Recommendation:**
- Remove SYSTEM_ALERT_WINDOW (not needed without overlay)
- Review if WAKE_LOCK is necessary
- POST_NOTIFICATIONS only if actually showing notifications

### app/build.gradle Issues

```gradle
// Line 50-51: Legacy secure storage library
implementation 'androidx.security:security-crypto:1.1.0-alpha06'
```

**Issue:** StorageBridge uses EncryptedSharedPreferences, but this alpha library might not be needed if we're using standard Android Keystore.

**Action:** Verify if security-crypto is actually used. If not, remove dependency.

```gradle
// Lines 53-58: CameraX dependencies (6 dependencies)
implementation 'androidx.camera:camera-core:1.4.0'
implementation 'androidx.camera:camera-camera2:1.4.0'
implementation 'androidx.camera:camera-lifecycle:1.4.0'
implementation 'androidx.camera:camera-view:1.4.0'
implementation 'com.google.mlkit:barcode-scanning:17.3.0'
```

**Action:** Verify these are all necessary. MLKit barcode scanning adds significant size.

---

## Inconsistent Logging Patterns

### Pattern Chaos:
- MainActivity: `TAG = "SecureIglooWrapper"`
- InvisibleNIP55Handler: `TAG = "InvisibleNIP55Handler"`
- IglooBackgroundService: `TAG = "IglooBackgroundService"`
- Bridges: Mixed patterns

### Debug Logging Levels:
- Some classes use `Log.d()` for everything
- Others use `Log.i()` / `Log.e()` appropriately
- DebugConfig adds conditional logging but isn't consistently used

**Recommendation:**
- Standardize TAG naming: `"Igloo:ClassName"`
- Use `Log.i()` for flow, `Log.d()` for details (remove in release), `Log.e()` for errors
- Remove DebugConfig or make it BuildConfig-based

---

## Architecture Recommendations

### Current State: Confused Hybrid
```
MainActivity (always visible)
  ├─ WebView with PWA
  ├─ Polyfill Bridges ✅ (correct)
  │   ├─ StorageBridge
  │   ├─ WebSocketBridge
  │   ├─ ModernCameraBridge
  │   └─ UnifiedSigningBridge
  └─ NIP-55 handling via AsyncBridge ✅

IglooBackgroundService (foreground service)
  ├─ Duplicate WebSocket infrastructure ❌
  ├─ Event queue system ❌
  ├─ Failed PWA loading attempts ❌
  └─ Service binding complexity ❌

InvisibleNIP55Handler (translucent activity)
  ├─ Permission checking ✅
  ├─ Request parsing ✅
  └─ Complex routing logic ⚠️ (over-engineered)

IglooContentProvider (content provider)
  ├─ Background signing via ContentResolver ✅ (concept)
  └─ Duplicate permission checking ❌
```

### Recommended: Simplified Alpha Architecture

```
MainActivity (WebView + PWA)
  ├─ WebView with PWA (always loaded when app running)
  ├─ Polyfill Bridges
  │   ├─ StorageBridge (EncryptedSharedPreferences)
  │   ├─ WebSocketBridge (OkHttp)
  │   ├─ ModernCameraBridge (CameraX)
  │   └─ AsyncBridge (NIP-55 communication)
  └─ Background-capable (moveTaskToBack for invisible signing)

InvisibleNIP55Handler (thin routing layer)
  ├─ Parse & validate NIP-55 request
  ├─ Check permissions (PermissionManager utility)
  └─ Forward to MainActivity → done

NIP55PermissionDialog (native dialog)
  ├─ Single permission prompt ✅
  └─ Bulk permission prompt ✅

IglooContentProvider (optional enhancement)
  ├─ ContentResolver API for background signing
  └─ Shares PermissionManager with handler

REMOVED:
  ✗ IglooBackgroundService (not needed)
  ✗ All WebSocket managers
  ✗ Event queue infrastructure
  ✗ Service binding complexity
  ✗ Java bridges
```

**Key Insight:** MainActivity can run in background with `moveTaskToBack(true)`. No separate service needed for "background" signing. The PWA + polyfills ARE the architecture.

---

## Action Plan

### Phase 1: Delete Dead Code (Immediate - Low Risk)
**Goal:** Remove 100% unused code to reduce confusion

1. Delete unused Java bridges:
   ```bash
   rm android/app/src/main/java/com/frostr/igloo/LocalWebServer.java
   rm android/app/src/main/java/com/frostr/igloo/JavaScriptBridge.java
   rm android/app/src/main/java/com/frostr/igloo/ContentResolverBridge.java
   rm android/app/src/main/java/com/frostr/igloo/NIP55Bridge.java
   rm android/app/src/main/java/com/frostr/igloo/SecureStorage.java
   ```

2. Update build.gradle to remove Java source directory if now empty:
   ```gradle
   // Remove or verify no other Java files exist
   ```

3. Remove entire Java package directory if empty:
   ```bash
   rmdir android/app/src/main/java/com/frostr/igloo/
   ```

**Validation:** App still compiles. No import errors.

### Phase 2: Simplify Background Service (Medium Risk)
**Goal:** Remove non-functional PWA loading and WebSocket duplication

1. Delete WebSocket managers:
   ```bash
   rm android/app/src/main/kotlin/com/frostr/igloo/managers/RelayConnectionManager.kt
   rm android/app/src/main/kotlin/com/frostr/igloo/managers/SubscriptionManager.kt
   rm android/app/src/main/kotlin/com/frostr/igloo/managers/NostrEventQueue.kt
   rm android/app/src/main/kotlin/com/frostr/igloo/managers/NostrEventHandler.kt
   ```

2. Keep utility managers:
   ```bash
   # Keep: BatteryPowerManager.kt, NetworkManager.kt
   # Review: WebSocketManager.kt (might contain only utility code)
   ```

3. Gut IglooBackgroundService to minimal:
   - Remove PWA loading (lines 429-666)
   - Remove WebSocket setup (lines 316-369)
   - Keep only: notification, foreground service management
   - Question: Is this service even needed? MainActivity can run in background.

4. Remove service binding from InvisibleNIP55Handler:
   - Delete lines 651-745 (service binding logic)
   - Simplify to always route to MainActivity

**Validation:** NIP-55 flow still works via MainActivity. No crashes.

### Phase 3: Consolidate Permission Management (Medium Risk)
**Goal:** Single source of truth for permissions

1. Create `PermissionManager.kt`:
   ```kotlin
   object PermissionManager {
       fun checkPermission(
           context: Context,
           callingApp: String,
           requestType: String,
           eventKind: Int? = null
       ): PermissionStatus

       fun savePermission(...)
       fun deletePermission(...)
   }
   ```

2. Refactor both permission check sites:
   - InvisibleNIP55Handler.checkPermission() → PermissionManager
   - IglooContentProvider.hasAutomaticPermission() → PermissionManager

3. Move `Permission` data class to PermissionManager.kt

**Validation:** Permission checks work identically. No behavior change.

### Phase 4: Simplify Queue System (Low-Medium Risk)
**Goal:** Remove over-engineered concurrency for simple sequential flow

1. Review MainActivity content resolver queue:
   - Lines 51-76: Queue infrastructure
   - Lines 936-1008: Queue processing

2. Question: Do we actually get concurrent ContentProvider requests?
   - If NO: Remove queue, process requests synchronously
   - If YES: Keep but simplify (remove atomic operations overkill)

3. Simplify broadcast receiver system:
   - ContentProvider reply mechanism (lines 158-196 in IglooContentProvider)
   - Can this be direct callback instead?

**Validation:** ContentProvider signing requests still work.

### Phase 5: Build System Cleanup (Low Risk)
**Goal:** Remove unused dependencies, fix permissions

1. AndroidManifest.xml:
   ```xml
   <!-- Review and potentially remove: -->
   <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
   <uses-permission android:name="android.permission.WAKE_LOCK" />
   ```

2. app/build.gradle:
   - Audit CameraX dependencies (are all 5 needed?)
   - Verify security-crypto usage
   - Check if all dependencies are actually imported

3. Remove IglooBackgroundService from manifest if deleted:
   ```xml
   <!-- Lines 72-75 -->
   <service android:name=".IglooBackgroundService" ... />
   ```

**Validation:** App builds, installs, runs without permission errors.

### Phase 6: Debug Infrastructure (Low Priority)
**Goal:** Clean up debug code in production paths

1. Fix DebugConfig.kt:
   ```kotlin
   const val DEBUG_ENABLED = BuildConfig.DEBUG  // Not hardcoded true
   ```

2. Review NIP55DebugLogger usage:
   - Is this needed in alpha?
   - Should it be behind BuildConfig.DEBUG?

3. Audit Log.d() statements:
   - Too many?
   - Should some be removed for release build?

**Validation:** Debug builds have logging, release builds are clean.

---

## Estimated Impact

### Lines of Code Reduction
| Category | Files | Lines | % of Codebase |
|----------|-------|-------|---------------|
| Unused Java bridges | 5 | 652 | ~5% |
| Background WebSocket infrastructure | 5 | ~2500 | ~20% |
| Failed PWA loading in service | 1 | ~250 | ~2% |
| Over-engineered queue system | 1 | ~150 | ~1% |
| **Total Removable** | **12+** | **~3,550** | **~28%** |

### Complexity Reduction
- **Before:** 4-component NIP-55 flow with service binding
- **After:** 2-component NIP-55 flow (Handler → MainActivity)
- **Before:** Dual WebSocket systems
- **After:** Single polyfill-based system
- **Before:** 3 permission check implementations
- **After:** 1 PermissionManager utility

### Maintenance Benefits
- Simpler debugging (one flow, not two competing systems)
- Easier onboarding (clear polyfill architecture)
- Faster builds (fewer files to compile)
- Clearer intent (alpha focused on core mission)

---

## Questions for Decision

1. **IglooBackgroundService:** Delete entirely or keep minimal version?
   - PWA WebSocket connections work fine in MainActivity
   - What's the actual benefit of the service?

2. **ContentProvider:** Keep or remove?
   - Adds complexity (broadcast receivers, queue management)
   - Does any app actually use ContentResolver API?
   - Traditional intents work fine

3. **CameraX:** Are all 5 dependencies actually used?
   - camera-core, camera-camera2, camera-lifecycle, camera-view
   - Plus ML Kit barcode-scanning
   - Is barcode scanning actually implemented in PWA?

4. **EncryptedSharedPreferences:** Actually needed?
   - StorageBridge uses it
   - But data is already encrypted by PWA before storage
   - Is double-encryption necessary?

5. **Debug Logging:** How much to keep in alpha?
   - Current: Very verbose
   - Helpful for debugging but clutters logcat
   - Should it be conditional on BuildConfig.DEBUG?

---

## Risk Assessment

### Low Risk (Do Now)
- ✅ Delete unused Java bridges (proven unused)
- ✅ Fix DebugConfig hardcoded flags
- ✅ Remove service binding from InvisibleNIP55Handler
- ✅ Consolidate permission checking logic

### Medium Risk (Test Thoroughly)
- ⚠️ Delete background service managers
- ⚠️ Simplify IglooBackgroundService or remove it
- ⚠️ Simplify content resolver queue system
- ⚠️ Remove failed PWA loading logic

### Higher Risk (Needs Analysis First)
- 🔍 Remove IglooContentProvider entirely
- 🔍 Change storage encryption strategy
- 🔍 Remove CameraX dependencies

---

## Testing Checklist

After each phase, verify:

- [ ] App builds without errors
- [ ] App installs on device
- [ ] PWA loads successfully in MainActivity
- [ ] All polyfill bridges work (Storage, WebSocket, Camera)
- [ ] NIP-55 login flow works (get_public_key)
- [ ] NIP-55 permission prompts appear
- [ ] NIP-55 auto-approved signing works
- [ ] ContentProvider signing works (if keeping)
- [ ] App survives background/foreground cycle
- [ ] No permission errors in logcat
- [ ] No crashes during normal usage

---

## Conclusion

The Android wrapper suffers from **architectural drift** - multiple attempted solutions to the same problem that were never cleaned up. The polyfill bridge architecture is correct and working, but it's buried under legacy approaches:

1. **Old approach:** Java bridges (unused)
2. **Second approach:** Background service with WebSocket managers (incomplete)
3. **Current approach:** Polyfill bridges in MainActivity (working!)

**Recommendation:** Embrace the polyfill architecture fully. Delete everything that contradicts or duplicates it. This will reduce codebase by ~28% and eliminate architectural confusion.

For an **alpha release**, simplicity is more valuable than feature completeness. A clean, understandable architecture with fewer components is better than a complex system with redundant parts.

**Priority Order:**
1. Delete dead code (immediate)
2. Simplify background service (high value)
3. Consolidate permissions (reduces bugs)
4. Clean build system (minor wins)

Total cleanup effort: **2-3 days of careful refactoring**
Maintenance benefit: **Permanent reduction in complexity**
