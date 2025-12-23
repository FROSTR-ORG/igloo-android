# Development Guide

This guide covers the complete development workflow for building, testing, and debugging the Igloo PWA Android application.

---

## Table of Contents

1. [Project Context](#project-context)
2. [Prerequisites](#prerequisites)
3. [Build Process](#build-process)
4. [Installation & Deployment](#installation--deployment)
5. [Log Monitoring](#log-monitoring)
6. [Testing Workflow](#testing-workflow)
7. [Architecture Overview](#architecture-overview)
8. [Development Workflows](#development-workflows)
9. [Troubleshooting](#troubleshooting)
10. [Reference](#reference)

---

## Project Context

### What is Igloo?

**Igloo** is a FROST-based signing device for the Nostr protocol. It uses FROSTR (a FROST implementation for Nostr) to enable threshold signing, where multiple key shares are required to produce a valid signature. This provides enhanced security compared to single-key signing.

### What We're Building

- A **NIP-55 signer** - an Android application that handles signing requests from other Nostr apps
- Uses **FROSTR/Bifrost** for distributed threshold signing
- Wrapped as a **PWA inside Android WebView** for cross-platform crypto support

### Key Protocols

| Protocol | Description |
|----------|-------------|
| **NIP-55** | Android signer protocol using intents and content providers |
| **FROST** | Flexible Round-Optimized Schnorr Threshold signatures |
| **Nostr** | Notes and Other Stuff Transmitted by Relays |

### Testing Environment

We test Igloo's signing capabilities by communicating with other Nostr applications:

| App | Purpose |
|-----|---------|
| **Amethyst** | Nostr client that sends signing requests |
| **Amber** | Reference NIP-55 signer (for comparison) |
| **Coracle** | Alternative Nostr client |

---

## Prerequisites

- **Node.js** (v18+) and npm
- **Android SDK** (SDK 35 / Android 15)
- **ADB** installed and in PATH
- **Physical Android device** connected via USB with USB debugging enabled
- **ngrok** (optional) - for remote relay testing

---

## Build Process

### CRITICAL: Always Use the Build Script

**NEVER** run `./gradlew assembleDebug` directly. This will use **stale PWA assets** and your changes won't appear in the APK.

**Always use the build.ts script** which:
1. Builds the PWA to `dist/`
2. Copies assets to `android/app/src/main/assets/`
3. Runs the Gradle build

### Quick Build Commands

```bash
# Debug APK (recommended for development)
npm run build:debug

# Debug APK + install to device
npm run build -- --debug --install

# Release APK
npm run build:release

# Release APK + install to device
npm run build -- --release --install

# PWA only (no Android build)
npm run build
```

### Build Script Options

The build script (`script/build.ts`) supports these flags:

| Flag | Description |
|------|-------------|
| `--debug` | Copy PWA to Android assets and build debug APK |
| `--release` | Copy PWA to Android assets and build release APK |
| `--install` | Also install APK to connected device after building |
| `--watch` | Watch mode for PWA development (no Android build) |

### Output Locations

| Build Type | Path |
|------------|------|
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `android/app/build/outputs/apk/release/app-release.apk` |
| PWA dist | `dist/` |

### PWA-Only Build

```bash
npm run build
```

**Output**: Creates `dist/` directory with:
- `app.js` - Main application bundle
- `app.js.map` - Source map
- `index.html` - Entry point
- `manifest.json` - PWA manifest
- `styles/` - CSS files
- `icons/` - App icons

**TypeScript Type Check** (optional but recommended):
```bash
npx tsc --noEmit
```

---

## Installation & Deployment

### Install to Device

```bash
# Standard install (preserves app data) - RECOMMENDED
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Fresh install (clears all data)
adb uninstall com.frostr.igloo
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

**WARNING**: Do NOT use `adb shell pm clear com.frostr.igloo` during normal development. This wipes:
- User's private keys
- Permission grants
- All app settings
- LocalStorage data

Only clear app data when explicitly testing first-run scenarios.

### Package Name Configuration

| Build Type | Package Name |
|------------|--------------|
| Debug | `com.frostr.igloo.debug` |
| Release | `com.frostr.igloo` |

Both work correctly because:
- The manifest uses `${applicationId}` placeholders
- Internal intents use `packageName` variable (not hardcoded strings)
- Debug and release can be installed side-by-side

**Verify installed package**:
```bash
adb shell pm list packages | grep igloo
```

---

## Log Monitoring

### App Log Tags

| Tag | Component | What to Look For |
|-----|-----------|------------------|
| `IglooHealthManager` | Health routing | Health state transitions, request queuing |
| `SecureIglooWrapper` | MainActivity | WebView lifecycle, PWA loading progress |
| `InvisibleNIP55Handler` | NIP-55 | Signing requests from external apps |
| `NIP55ContentProvider` | Background | ContentProvider queries, health checks |
| `NIP55HandlerService` | Service | Handler protection service lifecycle |
| `AsyncBridge` | IPC | PWA ↔ Android communication |
| `ModernCameraBridge` | Camera | QR scanning, camera initialization |
| `SecureStorageBridge` | Storage | Encrypted storage operations |
| `WebSocketBridge` | Network | Relay connections, WebSocket events |

### Essential Commands

```bash
# Clear all logs (do this before testing)
adb logcat -c

# Get last N lines
adb logcat -t 200

# Filter by tag
adb logcat -s "TAG:*"

# Recommended monitoring command
adb logcat -c && adb logcat -s "IglooHealthManager:*" "SecureIglooWrapper:*" "InvisibleNIP55Handler:*" "AsyncBridge:*"
```

### Log Monitoring Methods

#### Method 1: Fresh Logs (RECOMMENDED)

Use `adb logcat -t <count>` to get fresh logs from recent events:

```bash
adb logcat -t 500 -s \
  "SecureIglooWrapper:*" \
  "InvisibleNIP55Handler:*" \
  "IglooHealthManager:*" \
  "AndroidRuntime:E" \
  "*:F"
```

**When to use**: After any test run, crash, or user action.

#### Method 2: Post-Crash Capture

Immediately after a crash:

```bash
adb logcat -d -t 200 > /tmp/crash.log
cat /tmp/crash.log | grep -A 20 "FATAL EXCEPTION"
```

#### Method 3: Continuous Monitoring

During active testing:

```bash
adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*" "MainActivity:*" "AndroidRuntime:E" "*:F" &
```

**WARNING**: Background bash logs become STALE quickly. If started >15 minutes ago, use Method 1 instead.

### Verify Log Freshness

Always check timestamps when debugging:

```bash
adb logcat -t 100 -v time -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*"
```

If timestamps are >5 minutes old when debugging a recent crash, you're looking at STALE data.

### Common Log Patterns

| Pattern | Meaning |
|---------|---------|
| `[PWA LOAD] Progress: X%` | PWA loading (100% = ready) |
| `[PWA LOAD] Progress: 80%` (stuck) | Background WebView limitation |
| `NIP-55 request received` | External app requesting signature |
| `Permission check:` | Permission system evaluation |
| `[bifrost]` | FROSTR node activity |

---

## Testing Workflow

### Testing with Amethyst

This is the primary workflow for verifying NIP-55 signing.

#### Prerequisites

- Android device connected via ADB
- Igloo APK installed and configured
- Amethyst APK installed
- Bench environment running (`npm run bench`)

#### Setup

```bash
# Clear Amethyst data for fresh start
adb shell pm clear com.vitorpamplona.amethyst

# Launch Amethyst
adb shell am start -n com.vitorpamplona.amethyst/.ui.MainActivity
```

#### Test Steps

1. **Sign In**: Select "Sign in with Amber" → Choose Igloo
   - Expected: Igloo displays permission prompt

2. **Accept Permissions**: Check "Remember this permission" → Accept
   - Expected: Public key returned, focus returns to Amethyst

3. **Publish a Note**: Compose and publish
   - Expected: Signing occurs in background (no focus switch)

4. **Verify**: Check your profile for the published note

#### Expected Log Patterns

| Step | Log Pattern |
|------|-------------|
| Sign In | `NIP-55 request received`, `type: get_public_key` |
| Accept | `Permission check:`, `Permission saved` |
| Publish | `NIP-55 request received`, `type: sign_event`, `auto-approved` |

### Verify NIP-55 Registration

```bash
# Check if Igloo is registered as NIP-55 handler
adb shell dumpsys package com.frostr.igloo | grep -A 20 "Activity filter"

# Expected: android.intent.action.VIEW, scheme: "nostrsigner"

# Manually trigger NIP-55 intent
adb shell am start -a android.intent.action.VIEW \
  -d "nostrsigner:?type=get_public_key&package=com.example.test"
```

---

## Architecture Overview

### NIP-55 Flow (Health-Based Routing)

The NIP-55 pipeline uses a health-based routing system. All requests flow through `IglooHealthManager`:

1. **External app** sends NIP-55 request via Intent or ContentProvider
2. **InvisibleNIP55Handler** or **NIP55ContentProvider** receives request
3. **IglooHealthManager** checks if WebView is healthy (ready within last 5 seconds)
4. **If healthy**: Request processed immediately via AsyncBridge
5. **If unhealthy**: Request queued, MainActivity launched to restore WebView
6. **PWA** performs signing via BifrostSignDevice
7. **Result delivered** via callback to original handler

### Key Components

| Component | Purpose |
|-----------|---------|
| `InvisibleNIP55Handler.kt` | Intent entry point, routes to IglooHealthManager |
| `MainActivity.kt` | WebView host, calls `markHealthy()` when PWA ready |
| `NIP55ContentProvider.kt` | Background signing entry, checks health |
| `IglooHealthManager.kt` | Central health state, request queuing, caching |
| `NIP55HandlerService.kt` | Transient foreground service protecting handler |
| `AsyncBridge.kt` | Modern async JavaScript IPC |

See `docs/ARCHITECTURE.md` for detailed diagrams and flow descriptions.

---

## Development Workflows

### Standard Development Cycle

After making code changes:

```bash
# 1. Build and install
npm run build -- --debug --install

# 2. Clear logs and prepare for testing
adb logcat -c

# 3. Monitor logs before user tests
adb logcat -s "IglooHealthManager:*" "SecureIglooWrapper:*" "InvisibleNIP55Handler:*" "AndroidRuntime:E" "*:F"
```

**Rule**: After building the APK, ALWAYS proceed to clear logs and start monitoring.

### Fresh Development Session

```bash
# 1. Connect device and verify
adb devices

# 2. Start bench environment (Terminal 1)
npm run keygen    # if keyset.json doesn't exist
npm run bench

# 3. Build and install (Terminal 2)
npm run build -- --debug --install

# 4. Monitor logs (Terminal 3)
adb logcat -c && adb logcat -s "IglooHealthManager:*" "SecureIglooWrapper:*" "InvisibleNIP55Handler:*"
```

### Quick Iteration

Even for Kotlin-only changes, always use the build script:

```bash
npm run build -- --debug --install
adb logcat -c
```

### Git Workflow

Before committing:

1. Run TypeScript type check: `npx tsc --noEmit`
2. Test build process: `npm run build:debug`
3. Test on device with fresh install
4. Verify NIP-55 signing works with Amethyst

**Commit message format**:
```
type(scope): description

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

**Types**: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`

---

## Troubleshooting

### Quick Reference

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| "Waiting for PWA to load" timeout | JavaScript error in PWA | Check chromium logs, rebuild PWA |
| Amethyst timeout | PWA load timeout or signing delay | Check MainActivity responsiveness |
| "Missing required parameters" | Malformed NIP-55 request | Check InvisibleNIP55Handler parsing |
| APK unchanged after PWA build | Gradle cache | Run `./gradlew clean` |
| NIP-55 not working | Wrong package name | Verify `com.frostr.igloo` exactly |
| Stale log data | Background bash output | Use `adb logcat -t 500` |
| Amethyst doesn't find signer | Igloo not registered | Reinstall Igloo APK |
| Permission prompt doesn't appear | Intent not reaching Igloo | Check InvisibleNIP55Handler logs |
| Signing hangs | Bifrost node not connected | Verify bench is running |
| Background signing fails | Permission not saved | Re-grant with "Remember" |

### PWA Won't Load (Timeout after 30 seconds)

**Symptoms**:
```
SecureIglooWrapper: Waiting for PWA to load... (26/30)
```

**Debug Steps**:
1. Check for JavaScript errors: `adb logcat -s "chromium:*"`
2. Verify PWA was rebuilt: `./gradlew clean assembleDebug`
3. Check service worker: Look for SW registration errors

### Stale Logs

**Problem**: Logs show old behavior that doesn't match current code

**Solution**: Always clear and get fresh logs:
```bash
adb logcat -c && adb logcat -t 200
```

### WebView Caching

**Problem**: PWA changes not reflected in app

**Solution**: Reinstall with `-r` flag:
```bash
adb install -r <apk>
```

**Nuclear option**: Full uninstall and reinstall:
```bash
adb uninstall com.frostr.igloo
adb install <apk>
```

### Port Conflicts

```bash
# Check what's using the port
lsof -i :8080
lsof -i :3000

# Kill the process
kill <PID>
```

### Device Not Found

1. Check USB cable and connection
2. Enable USB debugging (Settings → Developer Options)
3. Accept RSA key prompt on device
4. Restart ADB (last resort):
```bash
adb kill-server && adb start-server
```

### Gradle Build Fails

- Ensure Android SDK 35 is installed
- Check `local.properties` has correct SDK path
- Try clean build: `./gradlew clean assembleDebug`
- Check Java version (requires Java 8+)

---

## Reference

### Quick Start Commands

| Command | Description |
|---------|-------------|
| `npm run dev` | Start PWA dev server (port 3000) |
| `npm run build` | Build PWA only |
| `npm run build:debug` | Build PWA + copy to Android + build debug APK |
| `npm run build:release` | Build PWA + copy to Android + build release APK |
| `npm run keygen` | Generate test keys (creates keyset.json) |
| `npm run bench` | Start bench environment (relay + ngrok + bifrost) |
| `npm run qrgen <string>` | Generate QR code for a string |

### Useful ADB Commands

```bash
# Check installed package
adb shell pm list packages | grep igloo

# Check package details
adb shell dumpsys package com.frostr.igloo | head -50

# Force stop app
adb shell am force-stop com.frostr.igloo

# Launch app
adb shell am start -n com.frostr.igloo/.MainActivity

# Check app data size
adb shell du -sh /data/data/com.frostr.igloo

# View SharedPreferences (requires debuggable build)
adb shell run-as com.frostr.igloo cat shared_prefs/permissions.xml
```

**IMPORTANT**: Never restart ADB server (`adb kill-server`) without user permission - it disconnects all devices and kills logcat sessions.

### Performance Monitoring

```bash
# APK size
ls -lh android/app/build/outputs/apk/debug/app-debug.apk

# Build time
time ./gradlew assembleDebug

# Memory usage
adb shell dumpsys meminfo com.frostr.igloo
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_PORT` | 8080 | Local relay WebSocket port |
| `NGROK_DOMAIN` | relay.ngrok.dev | ngrok custom domain |
| `DEBUG` | false | Enable debug logging in relay |
| `VERBOSE` | false | Enable verbose logging in relay |

### File Locations

| Description | Path |
|-------------|------|
| PWA source | `src/` |
| PWA output | `dist/` |
| Android source | `android/app/src/main/kotlin/com/frostr/igloo/` |
| Android assets | `android/app/src/main/assets/` |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `android/app/build/outputs/apk/release/app-release.apk` |
| Test keyset | `keyset.json` |
| Build script | `script/build.ts` |
| Bench script | `script/bench.ts` |

### Related Documentation

- `CLAUDE.md` - Project overview for Claude Code
- `docs/ARCHITECTURE.md` - Complete NIP-55 pipeline architecture
- `docs/CONVENTIONS.md` - Code style conventions
- `docs/protocols/NIP-55.md` - NIP-55 protocol specification

---

**Last Updated**: 2025-12-22
