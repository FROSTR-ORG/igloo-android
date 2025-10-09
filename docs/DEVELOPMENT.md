# Development Guide for Igloo PWA (NIP-55 Signer)

This guide covers the complete development workflow for building, deploying, and testing the Igloo PWA Android application.

---

## Build Process

### 1. PWA Build (TypeScript/React)

Build the PWA from the **root PWA directory** (`/home/cscott/Repos/frostr/pwa`):

```bash
cd /home/cscott/Repos/frostr/pwa
npm run build
```

**Output**: Creates `dist/` directory with:
- `app.js` - Main application bundle
- `sw.js` - Service worker
- `index.html` - Entry point
- `manifest.json` - PWA manifest
- `styles/` - CSS files
- `icons/` - App icons

**TypeScript Type Check** (optional but recommended):
```bash
npx tsc --noEmit
```

### 2. Android APK Build

Build the Android wrapper from the **android directory** (`/home/cscott/Repos/frostr/pwa/android`):

```bash
cd /home/cscott/Repos/frostr/pwa/android
./gradlew assembleDebug
```

**Output**: Creates APK at `app/build/outputs/apk/debug/app-debug.apk`

**Important**: The Gradle build automatically copies PWA assets from `../dist/` into the APK during the build process.

### 3. Clean Build (When Needed)

If you update the PWA and Gradle shows everything as `UP-TO-DATE`, do a clean build:

```bash
cd /home/cscott/Repos/frostr/pwa/android
./gradlew clean
./gradlew assembleDebug
```

### 4. Complete Build Script (All-in-One)

From the **android directory**:

```bash
cd /home/cscott/Repos/frostr/pwa && \
npm run build && \
cd android && \
./gradlew clean && \
./gradlew assembleDebug
```

---

## Installation & Deployment

### Install to Device

**Standard Install** (preserves app data):
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Fresh Install** (clears all data - use sparingly):
```bash
adb uninstall com.frostr.igloo
adb install app/build/outputs/apk/debug/app-debug.apk
```

**⚠️ WARNING**: Do NOT use `adb shell pm clear com.frostr.igloo` during normal development. This wipes:
- User's private keys
- Permission grants
- All app settings
- LocalStorage data

Only clear app data when explicitly testing first-run scenarios or resetting permissions.

---

## Critical Configuration: Package Name

### ⚠️ NEVER USE `.debug` SUFFIX

**Rule**: The package name **MUST** be exactly `com.frostr.igloo` for NIP-55 to work.

**Why**: Amethyst and other NIP-55 clients look for the signer at `com.frostr.igloo`. A debug suffix breaks Content Resolver lookups and Intent resolution.

**Verification**:
```bash
adb shell dumpsys package com.frostr.igloo | grep "Package"
```

**Expected**: `Package [com.frostr.igloo]`
**Wrong**: `Package [com.frostr.igloo.debug]`

**Current Configuration** (in `app/build.gradle`):
```gradle
android {
    namespace 'com.frostr.igloo'
    defaultConfig {
        applicationId "com.frostr.igloo"  // ← NO SUFFIX
    }

    buildTypes {
        debug {
            applicationIdSuffix ""  // ← EXPLICITLY EMPTY
        }
    }
}
```

See `/home/cscott/Repos/frostr/pwa/android/CRITICAL_PACKAGE_NAME.md` for full details.

---

## Testing Process

### 1. Pre-Test Setup

**Clear logs before testing**:
```bash
adb logcat -c
```

**Launch the app**:
```bash
adb shell am start -n com.frostr.igloo/.MainActivity
```

### 2. Monitor Logs (Real-time)

**Igloo + Amethyst Combined Monitoring**:
```bash
adb logcat -s \
  "SecureIglooWrapper:*" \
  "InvisibleNIP55Handler:*" \
  "NIP55ResultRegistry:*" \
  "MainActivity:*" \
  "Amethyst:*" \
  "AndroidRuntime:E" \
  "*:F"
```

**Igloo Only** (focused debugging):
```bash
adb logcat -s \
  "SecureIglooWrapper:*" \
  "InvisibleNIP55Handler:*" \
  "MainActivity:*" \
  "AndroidRuntime:E" \
  "*:F"
```

**WebView/JavaScript Errors**:
```bash
adb logcat -s "chromium:I" "chromium:W" "chromium:E"
```

### 3. Recommended Log Monitoring Methods

#### Method 1: Fresh Logs from Recent Events (RECOMMENDED)

**⚠️ BEST PRACTICE**: Use `adb logcat -t <count>` to get fresh logs from recent events.

```bash
# Get last 500 lines (most recent events)
adb logcat -t 500 -s \
  "SecureIglooWrapper:*" \
  "InvisibleNIP55Handler:*" \
  "MainActivity:*" \
  "AndroidRuntime:E" \
  "*:F"
```

**When to use**: After any test run, crash, or user action. This pulls the most recent logs directly from the device log buffer.

**Why it works**:
- Gets fresh data from the actual logcat buffer
- No timeout issues
- Timestamps show exactly when events occurred
- Can quickly verify if logs are from the test you just ran

#### Method 2: Post-Test Crash Log Capture

**When to use**: Immediately after a crash to capture the stack trace.

```bash
# Save last 200 lines to file for analysis
adb logcat -d -t 200 > /tmp/crash.log
```

Then read the file:
```bash
cat /tmp/crash.log | grep -A 20 "FATAL EXCEPTION"
```

**Benefits**:
- Captures complete crash stack trace
- Can be saved and analyzed later
- No risk of timeout
- Preserves exact state at crash time

#### Method 3: Background Monitoring During Testing

**When to use**: When actively testing and want continuous monitoring.

```bash
# Start in background before testing
adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*" "MainActivity:*" "AndroidRuntime:E" "*:F" &
```

**⚠️ WARNING**: Background bash shell logs become STALE quickly. Do NOT rely on BashOutput from shells started hours ago.

**Rule**: If a background bash was started more than 15 minutes ago, use Method 1 instead.

#### Verification: Check Log Timestamps

**CRITICAL**: Always check timestamps when analyzing logs.

```bash
# Use -v time to see timestamps
adb logcat -t 100 -v time -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*"
```

**Example output**:
```
10-05 14:32:15.123  1234  1234 D SecureIglooWrapper: PWA loaded successfully
```

**If timestamps are more than 5 minutes old when debugging a "just happened" crash, you're looking at STALE data.**

See `/home/cscott/Repos/frostr/pwa/android/CRITICAL_ALWAYS_CHECK_FRESH_LOGS.md` for complete details.

### 4. Test NIP-55 Signing Flow

**Full Test Sequence**:
1. Open Amethyst
2. Navigate to Settings → Security & Privacy → Signing Key
3. Select "Use External Signer" → Choose Igloo
4. Grant permissions when prompted
5. Attempt to create a post or perform an action requiring signing
6. **Expected**: Igloo activity launches, signing completes, returns to Amethyst
7. **Check logs** for any errors or timeouts

**Common Issues to Watch For**:
- Amethyst timeout: `TimedOutException: User didn't accept or reject in time`
- Missing parameters: `Missing required parameters for nip44_decrypt`
- PWA not loading: `Waiting for PWA to load... (26/30)`
- Job cancellation: `JobCancellationException`

### 5. Verify NIP-55 Intent Flow

**Check if Igloo is registered as NIP-55 handler**:
```bash
adb shell dumpsys package com.frostr.igloo | grep -A 20 "Activity filter"
```

**Expected output** should include:
- `android.intent.action.VIEW`
- `android.intent.category.BROWSABLE`
- `scheme: "nostrsigner"`

**Manually trigger NIP-55 intent**:
```bash
adb shell am start -a android.intent.action.VIEW \
  -d "nostrsigner:?type=get_public_key&package=com.example.test"
```

---

## Architecture Overview

### NIP-55 Flow (Intent-Based, No Content Resolver)

1. **Amethyst** sends NIP-55 request via Intent with `nostrsigner:` URI
2. **InvisibleNIP55Handler** receives Intent, parses request
3. **MainActivity** is launched with NIP-55 data, loads PWA
4. **PWA** displays prompt or auto-signs based on permissions
5. **MainActivity** receives result from PWA, calls `setResult()`
6. **Result delivered** back to Amethyst via `onActivityResult()`

**Key Components**:
- `InvisibleNIP55Handler.kt` - Thin routing layer, receives `nostrsigner:` URIs
- `MainActivity.kt` - Hosts PWA, handles signing execution
- `PendingNIP55ResultRegistry.kt` - Cross-task result delivery using callbacks
- `UnifiedSigningBridge.kt` - JavaScript↔Kotlin bridge for signing operations

**Content Resolver Removed**: We previously used Content Resolver, but it caused Amethyst to be killed for "excessive binder traffic during cached". The Intent-only architecture keeps Amethyst in foreground state during signing.

See `/home/cscott/Repos/frostr/pwa/android/NIP55_BACKGROUND_SIGNING_ANALYSIS.md` for details on why Content Resolver was removed.

---

## Development Workflow

### Standard Development Cycle

**IMPORTANT FOR CLAUDE CODE**: After making any code changes, you MUST complete ALL of these steps in sequence:

```bash
# 1. Make code changes to PWA (src/) or Android (android/app/src/)

# 2. Build PWA (if PWA changed)
cd /home/cscott/Repos/frostr/pwa
npm run build

# 3. Build APK
cd android
./gradlew assembleDebug  # Use clean if PWA was updated

# 4. Install to device (REQUIRED - do not skip this step)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 5. Clear logs and prepare for testing (REQUIRED)
adb logcat -c

# 6. Monitor logs (REQUIRED - start monitoring before user tests)
adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*" "MainActivity:*" "AndroidRuntime:E" "*:F"
```

**Rule**: After building the APK, ALWAYS proceed to install, clear logs, and start monitoring. Do not stop after the build step.

### Quick Iteration (Kotlin-only changes)

If you only changed Kotlin code (no PWA changes):

```bash
cd /home/cscott/Repos/frostr/pwa/android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
# Then start monitoring before user tests
adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*" "MainActivity:*" "AndroidRuntime:E" "*:F"
```

### TypeScript Type Reorganization

When reorganizing types:
1. Move interfaces/types to appropriate files in `src/types/`
2. Update imports in consuming files
3. Run type check: `npx tsc --noEmit`
4. Check conventions compliance (see `docs/CONVENTIONS.md`)
5. Build and test

**Conventions**:
- Functions/variables: `snake_case`
- Types/Interfaces: `PascalCase`
- Constants: `SCREAMING_SNAKE_CASE`
- Vertical alignment of colons in interfaces
- Import alignment on `from` keyword

---

## Debugging Tips

### PWA Won't Load (Timeout after 30 seconds)

**Symptoms**:
```
SecureIglooWrapper: Waiting for PWA to load... (26/30)
```

**Causes**:
1. JavaScript error in PWA preventing `window.IglooPWA.ready()` call
2. Missing or outdated PWA assets in APK
3. Service Worker crash

**Debug Steps**:
1. Check for JavaScript errors: `adb logcat -s "chromium:*"`
2. Verify PWA was rebuilt and APK was cleaned: `./gradlew clean assembleDebug`
3. Check service worker: Look for SW registration errors in chromium logs

### Amethyst Timeouts

**Symptoms**:
```
TimedOutException: Could not sign: User didn't accept or reject in time.
```

**Causes**:
1. PWA not loaded in time (see above)
2. Signing operation taking too long
3. User didn't respond to prompt

**Debug Steps**:
1. Check if MainActivity is visible and responsive
2. Monitor UnifiedSigningBridge logs for signing progress
3. Check for coroutine cancellation: `JobCancellationException`

### Missing Parameters Errors

**Symptoms**:
```
Missing required parameters for nip44_decrypt
```

**Causes**:
1. Amethyst sent malformed NIP-55 request
2. Intent parsing failed in InvisibleNIP55Handler
3. PWA received incomplete data

**Debug Steps**:
1. Check InvisibleNIP55Handler logs for parsed intent data
2. Enable NIP-55 debug logging (see `NIP55DebugLogger.kt`)
3. Verify request format matches NIP-55 spec

---

## File Locations

### Key Documentation
- `/home/cscott/Repos/frostr/pwa/CLAUDE.md` - Project overview for Claude
- `/home/cscott/Repos/frostr/pwa/docs/CONVENTIONS.md` - Coding standards
- `/home/cscott/Repos/frostr/pwa/android/README.md` - Android architecture docs
- `/home/cscott/Repos/frostr/pwa/src/README.md` - PWA source code docs

### Critical Config Files
- `/home/cscott/Repos/frostr/pwa/android/CRITICAL_PACKAGE_NAME.md`
- `/home/cscott/Repos/frostr/pwa/android/CRITICAL_ALWAYS_CHECK_FRESH_LOGS.md`
- `/home/cscott/Repos/frostr/pwa/android/NIP55_BACKGROUND_SIGNING_ANALYSIS.md`

### Build Scripts
- `/home/cscott/Repos/frostr/pwa/script/build.ts` - PWA build configuration (esbuild)
- `/home/cscott/Repos/frostr/pwa/android/app/build.gradle` - Android build config

### Source Directories
- `/home/cscott/Repos/frostr/pwa/src/` - PWA TypeScript/React source
- `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/` - Android Kotlin source
- `/home/cscott/Repos/frostr/pwa/public/` - PWA static assets (HTML, manifest, icons)

---

## Common Gotchas

1. **Gradle caching**: If PWA changes don't appear in APK, run `./gradlew clean`
2. **Package name**: Never add `.debug` suffix - breaks NIP-55 resolution
3. **Stale logs**: Always use `adb logcat -t` for fresh logs, not background bash output
4. **Data persistence**: Use `adb install -r` to preserve user data during reinstall
5. **Service Worker**: Clear browser cache if SW isn't updating: Settings → Apps → Igloo → Storage → Clear Cache
6. **Type imports**: After reorganizing types, rebuild PWA to catch missing imports

---

## Git Workflow

### Before Committing

1. Run TypeScript type check: `npx tsc --noEmit`
2. Test build process: `npm run build && cd android && ./gradlew assembleDebug`
3. Test on device with fresh install
4. Verify NIP-55 signing works with Amethyst

### Commit Message Format

Follow conventional commits:
```
type(scope): description

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

**Types**: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`

---

## Useful ADB Commands

### ⚠️ IMPORTANT: ADB Server Management

**NEVER restart the ADB server (`adb kill-server` / `adb start-server`) without explicit permission from the user.** Restarting ADB disconnects all devices and kills all background logcat sessions, which disrupts active debugging workflows.

If ADB commands are hanging or timing out:
1. First check device status: `adb devices`
2. Try clearing just the logcat buffer: `adb logcat -c`
3. Only as a last resort, and ONLY with user permission, restart ADB server

### Common Commands

```bash
# Check installed package
adb shell pm list packages | grep igloo

# Check package details
adb shell dumpsys package com.frostr.igloo | head -50

# Check device connection status
adb devices

# Force stop app
adb shell am force-stop com.frostr.igloo

# Launch app
adb shell am start -n com.frostr.igloo/.MainActivity

# Trigger NIP-55 intent
adb shell am start -a android.intent.action.VIEW \
  -d "nostrsigner:?type=get_public_key&package=com.example.test"

# Check app data size
adb shell du -sh /data/data/com.frostr.igloo

# View SharedPreferences (requires root or debuggable build)
adb shell run-as com.frostr.igloo cat shared_prefs/permissions.xml
```

---

## Performance Monitoring

### APK Size
```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

### Build Time
```bash
time ./gradlew assembleDebug
```

### Memory Usage
```bash
adb shell dumpsys meminfo com.frostr.igloo
```

---

## Troubleshooting Reference

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| "Waiting for PWA to load" timeout | JavaScript error in PWA | Check chromium logs, rebuild PWA |
| Amethyst timeout | PWA load timeout or signing delay | Check MainActivity responsiveness |
| "Missing required parameters" | Malformed NIP-55 request | Check InvisibleNIP55Handler parsing |
| APK unchanged after PWA build | Gradle cache | Run `./gradlew clean` |
| NIP-55 not working | Wrong package name | Verify `com.frostr.igloo` exactly |
| Stale log data | Background bash output | Use `adb logcat -t 500` for fresh logs |

---

**Last Updated**: 2025-10-05
