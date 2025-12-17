# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Igloo PWA** is a NIP-46 and NIP-55 signing device for mobile and web, powered by FROSTR. It's a Nostr signing application that handles cryptographic operations in a secure, distributed environment using FROSTR's bifrost protocol.

**Core Capabilities:**
- NIP-55 signing via `nostrsigner:` URI scheme for other apps
- FROSTR bifrost integration for distributed key management
- NIP-04 (AES-CBC) and NIP-44 (ChaCha20-Poly1305) encryption/decryption
- Permission management for granular signing control
- Both manual (prompt-based) and automated (background) signing modes

The PWA is wrapped in an Android application shell that provides:
- WebView container with polyfill bridges
- Secure Storage via Android Keystore (AES256-GCM encrypted)
- WebSocket bridge for mobile persistence (OkHttp 4.12.0)
- NIP-55 intent handling for system integration
- QR scanning via CameraX 1.4.0

## Development Commands

### Build and Development
- `npm run dev` - Start development server with watch mode and hot reload (port 3000)
- `npm run build` - Build production version to `dist/` directory
- `npm run test` - Run test suite using tape

### Utility Scripts
- `npm run keygen` - Generate cryptographic keys
- `npm run relay` - Run relay server
- `npm run release` - Create release build

The dev server runs on port 3000 and is accessible on all network interfaces for mobile testing.

### Android/Mobile Development
- `adb logcat -s "SecureIglooWrapper:*" "ModernCameraBridge:*" "SecureStorageBridge:*" "WebSocketBridge:*"` - Monitor Android app logs for debugging
- `adb logcat -c` - Clear Android logs before monitoring
- The Android companion app provides secure storage, polyfill bridges, and system integration
- Use Chrome DevTools on desktop to debug the PWA when running in Android WebView
- **IMPORTANT**: When updating/reinstalling the app, use `adb install -r` to preserve app data. DO NOT run `adb shell pm clear` unless explicitly testing first-run scenarios or resetting permissions intentionally.

## Architecture Overview

### Build System
- **Build Tool**: Custom esbuild configuration in `script/build.ts`
- **Entry Points**:
  - Main app: `src/index.tsx` → `dist/app.js`
  - Service Worker: `src/sw.ts` → `dist/sw.js`
- **CSS**: Separate CSS files are copied from `src/styles/` to `dist/styles/`
- **Path Aliases**: `@/*` maps to `src/*`

### React Provider Hierarchy
The app uses React Context for state management with this provider structure:
```
StrictMode
  └── SettingsProvider (app config & persistence, storage key: igloo-pwa)
      └── PermissionsProvider (NIP-55 permissions, storage key: nip55_permissions_v2)
          └── ConsoleProvider (debug logging, 100 log limit)
              └── NodeProvider (FROSTR bifrost node lifecycle)
                  └── App
```

### Key Source Files
| File | Purpose |
|------|---------|
| `src/index.tsx` | Main entry point, provider hierarchy setup |
| `src/components/app.tsx` | Root component with tabs (Dashboard, Settings, Permissions) |
| `src/components/nip55-bridge.tsx` | Sets up `window.nostr.nip55` interface for signing |
| `src/context/settings.tsx` | App configuration & persistence via Store class |
| `src/context/node.tsx` | FROSTR bifrost node lifecycle, delegates to useBifrost |
| `src/context/permissions.tsx` | NIP-55 permission system with event kind filtering |
| `src/hooks/useBifrost.ts` | Bifrost node management (init, connect, ping, reset) |
| `src/class/signer.ts` | BifrostSignDevice - wraps bifrost for signing operations |
| `src/class/store.ts` | Reactive store controller with subscription callbacks |
| `src/lib/cipher.ts` | NIP-04 (AES-CBC) and NIP-44 (ChaCha20-Poly1305) encryption |
| `src/lib/signer.ts` | Auto-signing execution and signing operation helpers |
| `src/lib/permissions.ts` | Permission storage and checking logic |

### Android Bridge Files
| File | Purpose |
|------|---------|
| `android/.../MainActivity.kt` | WebView container, bridge initialization |
| `android/.../AsyncBridge.kt` | Modern async/await IPC via WebMessageListener |
| `android/.../InvisibleNIP55Handler.kt` | Processes `nostrsigner:` URI intents |
| `android/.../NIP55ContentProvider.kt` | Content provider for background signing |

### Key Directories
- `android/` - Android application shell (Kotlin), APK files, gradle build
- `src/components/` - React components (layout/, dash/, settings/, permissions/, util/)
- `src/context/` - React context providers (settings, permissions, console, node)
- `src/hooks/` - Custom hooks (useBifrost, useNIP55Handler)
- `src/types/` - TypeScript type definitions (index, bridge, signer, permissions, settings)
- `src/lib/` - Utility libraries (cipher, enclave, encoder, nip19, util, permissions, signer)
- `src/class/` - Class definitions (signer, store)
- `src/styles/` - CSS files for UI components
- `dist/` - Build output directory
- `public/` - Static web resources (index.html, manifest.json, images)

### NIP-55 Request Types
The app handles these signing request types (defined in `src/types/signer.ts`):
- `get_public_key` - Retrieve wallet public key (supports hex and npub formats)
- `sign_event` - Sign Nostr events with the bifrost node
- `nip04_encrypt` / `nip04_decrypt` - Legacy AES-CBC encryption
- `nip44_encrypt` / `nip44_decrypt` - Modern ChaCha20-Poly1305 encryption
- `decrypt_zap_event` - Zap event decryption (not yet implemented)

### Mobile WebSocket Persistence
The app implements several strategies to maintain WebSocket connections on mobile:
- Page Visibility API for background/foreground detection
- Exponential backoff reconnection (up to 10 attempts)
- Keep-alive pings every 30 seconds
- Service Worker background sync
- Connection state monitoring

### Dependencies
- **Core**: React 19, TypeScript
- **Crypto**: @noble/ciphers, @noble/hashes, nostr-tools
- **FROSTR/Nostr**: @cmdcode/nostr-p2p, @frostr/bifrost
- **Utils**: @vbyte/buff, @vbyte/micro-lib
- **Mobile**: qr-scanner for QR code functionality
- **Build**: esbuild, tsx, serve, concurrently

### Testing
- Uses `tape` testing framework
- Test entry point: `test/tape.ts`
- Currently minimal test coverage

## Development Notes

### General
- The application uses ES modules and modern TypeScript (strict mode)
- Service Worker is critical for PWA functionality and mobile persistence
- CSS is handled as separate files in `src/styles/`, not CSS-in-JS
- The app supports URL parameters for configuration (group, pubkey, share, relay URLs)

### State Management Patterns
- All state flows through React Context providers (no Redux/Zustand)
- `Store` class (`src/class/store.ts`) provides reactive storage with subscriptions
- Settings persist to localStorage with key `igloo-pwa`
- Permissions persist separately with key `nip55_permissions_v2`

### Node Status States
The bifrost node (`src/context/node.tsx`) has these states:
- `init` - Initial state, not yet configured
- `disabled` - Node explicitly disabled
- `locked` - Node configured but needs password to unlock
- `online` - Node connected and ready for signing
- `offline` - Node disconnected, will attempt reconnection

### NIP-55 Signing Flow
1. Android receives `nostrsigner:` intent → `InvisibleNIP55Handler.kt`
2. Request passed to PWA via `AsyncBridge`
3. `src/lib/signer.ts` checks permissions and executes signing
4. `BifrostSignDevice` (`src/class/signer.ts`) performs crypto operation
5. Result returned to calling app via intent callback

### Important Patterns
- The `window.nostr.nip55` interface is set up by `NIP55Bridge` component
- Auto-signing can occur when node is locked if session password is available
- Public key can be retrieved from settings even when node is locked
- Encryption operations use ECDH key exchange via bifrost node

## Android Development

### Critical Warnings

#### Package Name Must Be `com.frostr.igloo` (No Debug Suffix)
- **NEVER** add `applicationIdSuffix ".debug"` to debug builds
- NIP-55 clients (Amethyst) look for signer at exact package `com.frostr.igloo`
- Adding suffix creates separate app with separate storage/permissions
- Verify with: `adb shell dumpsys package com.frostr.igloo | grep "Package"`

#### Always Get Fresh Logs
- **NEVER** rely on old background bash output for debugging
- Use `adb logcat -t 200` to get recent logs
- Clear and monitor fresh: `adb logcat -c && adb logcat -s "InvisibleNIP55Handler:*"`
- If timestamp is >5 minutes old, it's stale

### NIP-55 Pipeline Architecture
```
External App → InvisibleNIP55Handler → MainActivity → PWA WebView → UnifiedSigningBridge
     ↓                ↓                    ↓              ↓                  ↓
NIP-55 Intent    Intent Parsing     Intent Forwarding  JavaScript    Bridge Processing
```

**Process Isolation:**
- `:native_handler` process: InvisibleNIP55Handler (lightweight validation)
- `:main` process: MainActivity + PWA + All bridges (secure environment)

**Key Files:**
- `InvisibleNIP55Handler.kt` - Entry point, validates `nostrsigner:` intents
- `MainActivity.kt` - WebView host, injects requests into PWA
- `UnifiedSigningBridge.kt` - JavaScript interface for crypto operations
- `AsyncBridge.kt` - Modern async IPC via WebMessageListener

### Background Signing Limitation
**Known Issue**: True headless background signing for pre-approved operations does not work.

**What Works:**
- NIP-55 login (`get_public_key`)
- Permission prompts (any operation requiring approval)
- MainActivity-based signing (when user is present)

**What Doesn't Work:**
- Background signing without UI - WebView stuck at 80% progress in Service context
- WebView requires window attachment for full rendering

**Current Behavior**: All signing operations route through MainActivity with brief UI visibility.

### Amethyst Retry Behavior
- Amethyst generates **new request ID** for each retry (30s timeout)
- Event-based deduplication required (by Nostr event ID, not request ID)
- Without deduplication, 48+ duplicate requests can queue up

### Release Build Process

**Prerequisites:**
1. Build PWA: `npm run build`
2. Verify keystore: `ls android/igloo-release.keystore`

**Build Commands:**
```bash
cd android
./gradlew clean assembleRelease    # Build signed APK
./gradlew installRelease           # Build and install
```

**Output:** `app/build/outputs/apk/release/app-release.apk`

**Verify signature:** `apksigner verify --verbose app/build/outputs/apk/release/app-release.apk`

**Version Management** (in `app/build.gradle`):
- `versionCode` - Must increment for every release
- `versionName` - User-facing (e.g., "1.0-beta1", "1.0-rc1", "1.0")

### Android Build Configuration
- **compileSdk**: 35 (Android 15)
- **minSdk**: 24 (Android 7.0 Nougat)
- **Android Gradle Plugin**: 8.1.4
- **Java Compatibility**: VERSION_1_8

### Polyfill System
PWA code works unchanged - polyfills transparently replace web APIs:
```typescript
navigator.mediaDevices.getUserMedia()  // → ModernCameraBridge (CameraX)
localStorage.setItem()                 // → SecureStorageBridge (AES256-GCM)
new WebSocket()                        // → WebSocketBridge (OkHttp)
```

**Polyfill files:** `android/app/src/main/assets/polyfills/`

## Detailed Documentation

For comprehensive understanding of the codebase architecture:

### Source Code Architecture
- **`src/README.md`** - Complete PWA source code documentation

### Android Wrapper Architecture
- **`android/README.md`** - Complete Android shell documentation
- **`android/PIPELINE.md`** - NIP-55 pipeline architecture details
- **`android/RELEASE.md`** - Release build guide
- **`android/NIP55_BACKGROUND_SIGNING_ANALYSIS.md`** - Background signing limitations
- **`android/CRITICAL_*.md`** - Hard-won lessons and warnings