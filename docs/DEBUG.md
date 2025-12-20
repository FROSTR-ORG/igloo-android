# Debug & Development Guide

This guide covers the complete development environment setup, workflow, and debugging procedures for the Igloo PWA + Android application.

---

## Project Context

### What is Igloo?
**Igloo** is a FROST-based signing device for the Nostr protocol. It uses FROSTR (a FROST implementation for Nostr) to enable threshold signing, where multiple key shares are required to produce a valid signature. This provides enhanced security compared to single-key signing.

### What We're Building
- A **NIP-55 signer** - an Android application that handles signing requests from other Nostr apps
- Uses **FROSTR/Bifrost** for distributed threshold signing
- Wrapped as a **PWA inside Android WebView** for cross-platform crypto support

### Testing Environment
We test Igloo's signing capabilities by communicating with other Nostr applications:

| App | Purpose | Location |
|-----|---------|----------|
| **Amethyst** | Nostr client that sends signing requests | `repos/amethyst/` |
| **Amber** | Reference NIP-55 signer (for comparison) | `repos/Amber/` |
| **Coracle** | Another Nostr client | `repos/coracle/` |

### Key Protocols
- **NIP-55** - Android signer protocol using intents and content providers
- **FROST** - Flexible Round-Optimized Schnorr Threshold signatures
- **Nostr** - Notes and Other Stuff Transmitted by Relays

---

## Reference Documentation

### Local Documentation (`docs/`)
| File | Description |
|------|-------------|
| `docs/NIP-55.md` | NIP-55 protocol specification for Android signers |
| `docs/CONVENTIONS.md` | Code conventions and patterns |
| `docs/DEVELOPMENT.md` | Development guidelines |

### Reference Source Code (`repos/`)
| Directory | Description |
|-----------|-------------|
| `repos/Amber/` | Reference NIP-55 signer implementation |
| `repos/amethyst/` | Nostr client that we test signing with |
| `repos/coracle/` | Alternative Nostr client |

### Useful Paths for Debugging
When debugging NIP-55 communication issues, these source files are helpful:

**Amber (reference signer):**
- Intent handling patterns
- Content provider implementation
- Permission management

**Amethyst (test client):**
- How signing requests are sent
- Expected response formats
- Retry behavior (30s timeout, new request IDs)

---

## Prerequisites

- **Node.js** (v18+) and npm
- **Android SDK** (SDK 35 / Android 15)
- **ADB** installed and in PATH
- **ngrok** configured with custom domain
- **Physical Android device** connected via USB with USB debugging enabled

## Quick Start Commands

| Command | Description |
|---------|-------------|
| `npm run dev` | Start PWA dev server (port 3000) |
| `npm run build` | Build PWA only |
| `npm run build:android` | Build PWA + copy to Android + build APK |
| `npm run keygen` | Generate test keys (creates keyset.json) |
| `npm run bench` | Start bench environment (relay + ngrok + bifrost) |
| `npm run qrgen <string>` | Generate QR code for a string |
| `adb install -r <apk>` | Install APK (preserves app data) |

---

## Development Scripts

### `npm run build`
Builds the PWA to `dist/` directory.
- Entry: `src/index.tsx` → `dist/app.js`
- Service Worker: `src/sw.ts` → `dist/sw.js`
- CSS extracted to `dist/styles/`

### `npm run build:android`
Full build pipeline:
1. Builds PWA to `dist/`
2. Copies assets to `android/app/src/main/assets/`
3. Runs `./gradlew assembleDebug`
4. Output: `android/app/build/outputs/apk/debug/app-debug.apk`

### `npm run dev`
Starts development server with hot reload.
- PWA served at `http://localhost:3000`
- Watch mode enabled for TypeScript and CSS changes

### `npm run keygen [shares] [threshold]`
Generates FROSTR key shares.
```bash
npm run keygen           # Default: 3 shares, 2 threshold
npm run keygen 5 3       # 5 shares, 3 threshold
```
Output: `keyset.json` (gitignored)

### `npm run bench`
Starts the bench testing environment:
- **Local relay**: `ws://localhost:8080`
- **ngrok tunnel**: `wss://relay.ngrok.dev`
- **Bifrost node**: Using first share from `keyset.json`

Environment variables:
- `RELAY_PORT` (default: 8080)
- `NGROK_DOMAIN` (default: relay.ngrok.dev)

### `npm run qrgen <string>`
Prints a QR code in the terminal.
```bash
npm run qrgen "wss://relay.ngrok.dev"
```

---

## Build Workflow

### PWA Only
```bash
npm run build
```

### Full Android Build
```bash
npm run build:android
```

### Manual Step-by-Step
```bash
# 1. Build PWA
npm run build

# 2. Build Android APK
cd android
./gradlew assembleDebug

# Output: android/app/build/outputs/apk/debug/app-debug.apk
```

---

## ADB Device Management

### Verify Device Connection
```bash
adb devices
```

### Install APK
```bash
# Install (preserves app data) - RECOMMENDED
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Full uninstall (clears all data)
adb uninstall com.frostr.igloo
```

### Launch App
```bash
adb shell am start -n com.frostr.igloo/.MainActivity
```

---

## Log Monitoring & Debugging

### Essential Commands
```bash
# Clear all logs (do this before testing)
adb logcat -c

# Get last N lines
adb logcat -t 200

# Filter by tag
adb logcat -s "TAG:*"

# Continuous monitoring with filter
adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*"
```

### App Log Tags

| Tag | Component | What to look for |
|-----|-----------|------------------|
| `SecureIglooWrapper` | MainActivity | WebView lifecycle, PWA loading progress |
| `ModernCameraBridge` | Camera | QR scanning, camera initialization |
| `SecureStorageBridge` | Storage | Encrypted storage operations |
| `WebSocketBridge` | Network | Relay connections, WebSocket events |
| `InvisibleNIP55Handler` | NIP-55 | Signing requests from external apps |
| `AsyncBridge` | IPC | PWA ↔ Android communication |

### Recommended Monitoring Command
```bash
adb logcat -c && adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*" "AsyncBridge:*"
```

### Common Log Patterns

| Pattern | Meaning |
|---------|---------|
| `[PWA LOAD] Progress: X%` | PWA loading (100% = ready) |
| `[PWA LOAD] Progress: 80%` (stuck) | Background WebView limitation |
| `NIP-55 request received` | External app requesting signature |
| `Permission check:` | Permission system evaluation |
| `[bifrost]` | FROSTR node activity |

### Log Management
```bash
# Purge all logs (recommended before each test session)
adb logcat -c

# Get recent logs without continuous monitoring
adb logcat -t 500
```

---

## Testing Workflow: Igloo + Amethyst

This is the primary testing workflow for verifying NIP-55 signing between Igloo and Amethyst.

### Prerequisites
- Android device connected via ADB
- Igloo APK installed and configured (group, share, relay pointing to bench)
- Amethyst APK installed
- Bench environment running (`npm run bench`)

### Setup: Clear Amethyst Data
Before testing, wipe Amethyst to start fresh:
```bash
adb shell pm clear com.vitorpamplona.amethyst
```

### Step 1: Launch Amethyst
```bash
adb shell am start -n com.vitorpamplona.amethyst/.ui.MainActivity
```
Or manually tap the Amethyst app icon.

### Step 2: Sign In with Amber
1. On Amethyst's login screen, select **"Sign in with Amber"**
2. **Expected behavior:**
   - Amethyst sends a NIP-55 `get_public_key` intent
   - Android routes to Igloo (registered as `nostrsigner:` handler)
   - Focus switches to Igloo
   - Igloo displays a permission prompt

### Step 3: Accept Permissions
1. In Igloo's permission prompt:
   - Check **"Remember this permission"**
   - Tap **"Accept"**
2. **Expected behavior:**
   - Permission saved to encrypted storage
   - Igloo processes the `get_public_key` request
   - Public key returned to Amethyst via intent result
   - Focus switches back to Amethyst
   - Amethyst completes login with the public key

### Step 4: Publish a Note
1. In Amethyst, compose and publish a note
2. **Expected behavior:**
   - Amethyst sends `sign_event` request to Igloo
   - **Signing occurs in background** (no focus switch)
   - Permission was already granted, so auto-approved
   - Signed event returned to Amethyst
   - Note publishes to relays

### Step 5: Verify Publication
1. Check your profile in Amethyst
2. Verify the note appears in your feed
3. **Success criteria:**
   - Note is visible
   - Shows correct author (your npub)
   - Timestamp is correct

### Debugging During Testing

**Monitor logs during the entire flow:**
```bash
adb logcat -c && adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*" "AsyncBridge:*"
```

**Key log patterns to watch for:**

| Step | Expected Log Pattern |
|------|---------------------|
| Step 2 | `NIP-55 request received`, `type: get_public_key` |
| Step 3 | `Permission check:`, `Permission saved`, result returned |
| Step 4 | `NIP-55 request received`, `type: sign_event`, `auto-approved` |

**If something fails:**
1. Check which step failed in the logs
2. Look for error messages or exceptions
3. Verify bench is running (`npm run bench`)
4. Verify Igloo is configured with correct relay URL
5. Check permission storage state

### Common Issues During Testing

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| Amethyst doesn't find signer | Igloo not registered as handler | Reinstall Igloo APK |
| Permission prompt doesn't appear | Intent not reaching Igloo | Check `InvisibleNIP55Handler` logs |
| Signing hangs | Bifrost node not connected | Verify bench is running |
| Background signing fails | Permission not saved | Re-grant permission with "Remember" |
| Note doesn't publish | Relay connection issue | Check WebSocket logs |

---

## Common Workflows

### A. Fresh Development Session
```bash
# 1. Connect device and verify
adb devices

# 2. Start bench environment (Terminal 1)
npm run keygen    # if keyset.json doesn't exist
npm run bench

# 3. Build and install (Terminal 2)
npm run build:android
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# 4. Monitor logs (Terminal 3)
adb logcat -c && adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*"
```

### B. Testing NIP-55 Signing
```bash
# 1. Ensure bench is running
npm run bench

# 2. Install and launch app
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.frostr.igloo/.MainActivity

# 3. Configure app with keyset (scan QR codes)

# 4. Test with Amethyst or other NIP-55 client
```

### C. Rebuilding After Code Changes

**PWA changes:**
```bash
npm run build:android
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

**Android-only changes:**
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Clean rebuild:**
```bash
cd android
./gradlew clean assembleDebug
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `RELAY_PORT` | 8080 | Local relay WebSocket port |
| `NGROK_DOMAIN` | relay.ngrok.dev | ngrok custom domain |
| `DEBUG` | false | Enable debug logging in relay |
| `VERBOSE` | false | Enable verbose logging in relay |

---

## Troubleshooting

### Stale Logs
**Problem**: Logs show old behavior that doesn't match current code
**Cause**: Looking at cached/old log output
**Solution**: Always clear and get fresh logs:
```bash
adb logcat -c && adb logcat -t 200
```

### WebView Caching
**Problem**: PWA changes not reflected in app
**Cause**: WebView caching old JavaScript
**Solution**: Reinstall with `-r` flag (preserves data):
```bash
adb install -r <apk>
```
**Nuclear option**: Full uninstall and reinstall:
```bash
adb uninstall com.frostr.igloo
adb install <apk>
```

### PWA Not Loading (stuck at 80%)
**Problem**: WebView progress stuck at 80%
**Cause**: Background signing limitation - WebView requires window attachment
**Solution**: Ensure MainActivity is visible for signing operations
**Note**: This is a known Android limitation documented in CLAUDE.md

### Port Conflicts
**Problem**: Relay or dev server won't start
**Cause**: Port already in use
**Solution**:
```bash
# Check what's using the port
lsof -i :8080
lsof -i :3000

# Kill the process
kill <PID>
```

### Device Not Found
**Problem**: `adb devices` shows nothing
**Solution**:
1. Check USB cable and connection
2. Enable USB debugging on device (Settings → Developer Options)
3. Accept RSA key prompt on device
4. Restart ADB:
```bash
adb kill-server && adb start-server
```

### Gradle Build Fails
**Problem**: `./gradlew assembleDebug` fails
**Solutions**:
- Ensure Android SDK 35 is installed
- Check `local.properties` has correct SDK path
- Try clean build: `./gradlew clean assembleDebug`
- Check Java version (requires Java 8+)

---

## File Locations

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

---

## Related Documentation

### Project Documentation
- **CLAUDE.md** - Architecture overview and development notes
- **android/README.md** - Android wrapper documentation
- **android/PIPELINE.md** - NIP-55 pipeline architecture
- **android/RELEASE.md** - Release build guide

### Protocol & Reference Documentation
- **docs/NIP-55.md** - NIP-55 protocol specification
- **docs/CONVENTIONS.md** - Code conventions
- **docs/DEVELOPMENT.md** - Development guidelines

### Reference Implementations
- **repos/Amber/** - Reference NIP-55 signer (compare our implementation)
- **repos/amethyst/** - Nostr client source (understand signing requests)
- **repos/coracle/** - Alternative Nostr client
