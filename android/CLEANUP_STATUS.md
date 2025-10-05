# Tech Debt Cleanup Status

## Current Situation

I've executed Phases 1-4 of the aggressive tech debt cleanup. The PWA builds successfully, but the Android build is failing due to missing type definitions that were accidentally deleted.

## Completed Work

### Phase 1: Safe Deletions ✅
**PWA Deleted (8 files, 1,333 lines):**
- `src/types/global.ts` - 4 lines
- `src/styles/sessions.css` - 537 lines
- `src/styles/prompt.css` - 219 lines
- `src/components/prompt/action.tsx` - ~120 lines
- `src/components/prompt/event.tsx` - ~120 lines
- `src/components/prompt/index.tsx` - ~137 lines
- `src/components/prompt/README.md` - ~98 lines
- `src/components/permissions/README.md` - ~98 lines

**Android Deleted (5 files, 652 lines):**
- `app/src/main/java/com/frostr/igloo/LocalWebServer.java`
- `app/src/main/java/com/frostr/igloo/JavaScriptBridge.java`
- `app/src/main/java/com/frostr/igloo/ContentResolverBridge.java`
- `app/src/main/java/com/frostr/igloo/NIP55Bridge.java`
- `app/src/main/java/com/frostr/igloo/SecureStorage.java`

### Phase 2: PWA Prompt System Removal ✅
- Removed PromptManager from `app.tsx`
- Removed PromptProvider from `index.tsx`
- Deleted `src/context/prompt.tsx` (245 lines)
- Removed SessionsIcon from `icons.tsx` (24 lines)

### Phase 3: Android Background Service Cleanup ✅
- Deleted background WebSocket files:
  - `app/src/main/kotlin/com/frostr/igloo/WebSocketService.kt`
  - `app/src/main/kotlin/com/frostr/igloo/WebSocketManager.kt`
  - `app/src/main/kotlin/com/frostr/igloo/IglooBackgroundService.kt`
  - `app/src/main/kotlin/com/frostr/igloo/InvisibleBackgroundWebSocket.kt`
  - `app/src/main/kotlin/com/frostr/igloo/BackgroundNIP55Handler.kt`
- Removed IglooBackgroundService from AndroidManifest.xml
- Removed unused permissions from AndroidManifest.xml:
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_DATA_SYNC`
  - `WAKE_LOCK`
  - `POST_NOTIFICATIONS`
  - `SYSTEM_ALERT_WINDOW`

### Phase 4: Type Consolidation ✅
- Added `Permission` type alias to `permissions.ts` for compatibility
- Updated imports in `context/permissions.tsx` and `components/permissions/index.tsx`
- Moved type references from `prompt.ts` to `permissions.ts`

## Current Build Status

**PWA Build:** ✅ SUCCESS
```bash
cd /home/cscott/Repos/frostr/pwa && npm run build
# SUCCESS - build complete
```

**Android Build:** ❌ FAILED
```bash
cd /home/cscott/Repos/frostr/pwa/android && ./gradlew assembleDebug
# FAILED - Missing types: NIP55Request, NIP55Result, IglooBackgroundService
```

## Critical Issue

The deleted files contained shared data classes (`NIP55Request`, `NIP55Result`) that are still referenced by:
- `AsyncBridge.kt`
- `InvisibleNIP55Handler.kt`
- `MainActivity.kt`
- `PendingNIP55ResultRegistry.kt`

Additionally, references to the deleted `IglooBackgroundService` still exist in:
- `InvisibleNIP55Handler.kt` (lines 653, 659, 679)
- `MainActivity.kt` (line 179)

## Next Steps Required

1. **Find NIP55 Type Definitions:**
   - These types were likely in one of the deleted Kotlin files
   - Need to either:
     a) Restore them to a shared Models file, or
     b) Recreate them based on usage

2. **Remove IglooBackgroundService References:**
   - Comment out or remove all references to `IglooBackgroundService`
   - The background service has been removed, so these calls are dead code

3. **Rebuild and Test:**
   - Once types are restored/recreated, rebuild Android
   - Test NIP-55 permission flow
   - Deploy to device

## Recommended Fix Strategy

Create a new file `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/NIP55Models.kt` with the missing data classes, inferred from usage:

```kotlin
package com.frostr.igloo

data class NIP55Request(
    val id: String,
    val method: String,
    val params: Map<String, Any>?,
    val packageName: String
)

data class NIP55Result(
    val id: String,
    val result: String?,
    val error: String?
)
```

Then remove all `IglooBackgroundService` references.

## Files Modified but Not Committed

- `/home/cscott/Repos/frostr/pwa/src/index.tsx`
- `/home/cscott/Repos/frostr/pwa/src/components/app.tsx`
- `/home/cscott/Repos/frostr/pwa/src/components/util/icons.tsx`
- `/home/cscott/Repos/frostr/pwa/src/types/permissions.ts`
- `/home/cscott/Repos/frostr/pwa/src/context/permissions.tsx`
- `/home/cscott/Repos/frostr/pwa/src/components/permissions/index.tsx`
- `/home/cscott/Repos/frostr/pwa/android/app/src/main/AndroidManifest.xml`

## Summary

- **Total Lines Deleted:** ~2,200+ lines
- **Files Deleted:** 18 files
- **PWA Build:** ✅ Working
- **Android Build:** ❌ Needs type restoration
- **Deployment:** Blocked until Android build fixed
