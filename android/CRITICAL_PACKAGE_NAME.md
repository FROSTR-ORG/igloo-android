# ⚠️ CRITICAL: PACKAGE NAME MUST BE `com.frostr.igloo` - NO DEBUG SUFFIX ⚠️

## NEVER ADD `.debug` SUFFIX TO THE PACKAGE NAME

The package name **MUST** be exactly `com.frostr.igloo` for NIP-55 to work correctly.

### Why this matters:
- Amethyst and other NIP-55 clients look for the signer at `com.frostr.igloo`
- If the package has a `.debug` suffix (e.g., `com.frostr.igloo.debug`), the Content Resolver API will NOT work
- Clients cannot find the Content Provider and signing fails silently

### Current Configuration (CORRECT):
```gradle
// app/build.gradle
android {
    namespace 'com.frostr.igloo'
    defaultConfig {
        applicationId "com.frostr.igloo"  // ← NO SUFFIX
    }

    buildTypes {
        debug {
            applicationIdSuffix ""  // ← EXPLICITLY SET TO EMPTY STRING
        }
    }
}
```

### What NOT to do:
❌ NEVER set `applicationIdSuffix ".debug"` in debug build type
❌ NEVER change the applicationId to include `.debug`
❌ NEVER allow Gradle to automatically add debug suffixes

### Verification:
After building, verify the package name:
```bash
adb shell dumpsys package com.frostr.igloo | grep "Package"
```

Should show: `Package [com.frostr.igloo]` (NOT `com.frostr.igloo.debug`)

---

**THIS IS A CRITICAL CONFIGURATION - DO NOT CHANGE IT**
