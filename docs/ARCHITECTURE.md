# Igloo Architecture

This document provides a comprehensive overview of the Igloo application architecture.

---

## Table of Contents

1. [Overview](#overview)
2. [High-Level Architecture](#high-level-architecture)
3. [Android Layer](#android-layer)
4. [PWA Layer](#pwa-layer)
5. [FROSTR/Bifrost Integration](#frostrbifrost-integration)
6. [NIP-55 Signing Pipeline](#nip-55-signing-pipeline)
7. [Security Architecture](#security-architecture)
8. [Build System](#build-system)

---

## Overview

**Igloo** is a FROST-based signing device for the Nostr protocol. It enables threshold signing where multiple key shares are required to produce a valid signature, providing enhanced security compared to single-key signing.

### Key Capabilities

| Feature | Description |
|---------|-------------|
| **NIP-55 Signing** | Handles `nostrsigner:` URI requests from Nostr apps |
| **FROSTR Integration** | Connects to FROSTR network via bifrost node |
| **Permission Management** | Granular control over which apps can sign which event kinds |
| **Encryption** | NIP-04 (AES-CBC) and NIP-44 (ChaCha20-Poly1305) support |
| **QR Code Setup** | Scan FROSTR share to configure quickly |

### Design Philosophy

Igloo is built as a **PWA wrapped in an Android shell**. This approach:
- Enables cryptographic operations in JavaScript (portable across platforms)
- Provides native Android capabilities via polyfill bridges
- Allows the PWA to run standalone in a web browser for testing

---

## High-Level Architecture

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                                ANDROID SHELL                                  │
│                                                                               │
│    ┌────────────────────────────────────────────────────────────────────┐     │
│    │                          MainActivity.kt                           │     │
│    │  ┌──────────────────────────────────────────────────────────────┐  │     │
│    │  │                        Secure WebView                        │  │     │
│    │  │                                                              │  │     │
│    │  │  ┌────────────────────────────────────────────────────────┐  │  │     │
│    │  │  │                       React PWA                        │  │  │     │
│    │  │  │                                                        │  │  │     │
│    │  │  │   ┌─────────────┐  ┌─────────────┐  ┌──────────────┐   │  │  │     │
│    │  │  │   │  Dashboard  │  │  Settings   │  │ Permissions  │   │  │  │     │
│    │  │  │   │    Tab      │  │    Tab      │  │     Tab      │   │  │  │     │
│    │  │  │   └─────────────┘  └─────────────┘  └──────────────┘   │  │  │     │
│    │  │  │                                                        │  │  │     │
│    │  │  │   ┌────────────────────────────────────────────────┐   │  │  │     │
│    │  │  │   │            Bifrost Node                        │   │  │  │     │
│    │  │  │   │   (FROSTR threshold signing via WebSocket)     │   │  │  │     │
│    │  │  │   └────────────────────────────────────────────────┘   │  │  │     │
│    │  │  │                                                        │  │  │     │
│    │  │  └────────────────────────────────────────────────────────┘  │  │     │
│    │  │                              │                               │  │     │
│    │  │                       Polyfill Bridges                       │  │     │
│    │  │                              │                               │  │     │
│    │  └──────────────────────────────┼───────────────────────────────┘  │     │
│    └─────────────────────────────────┼──────────────────────────────────┘     │
│                                      │                                        │
│  ┌───────────────────────────────────┼─────────────────────────────────────┐  │
│  │                             Native Bridges                              │  │
│  │                                                                         │  │
│  │  ┌───────────────┐  ┌─────────────────┐  ┌──────────────┐  ┌─────────┐  │  │
│  │  │ StorageBridge │  │ WebSocketBridge │  │ CameraBridge │  │  NIP-55 │  │  │
│  │  │   (AES-GCM)   │  │   (OkHttp)      │  │  (CameraX)   │  │ Handler │  │  │
│  │  └───────────────┘  └─────────────────┘  └──────────────┘  └─────────┘  │  │
│  │                                                                         │  │
│  └─────────────────────────────────────────────────────────────────────────┘  │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Technology | Responsibility |
|-------|------------|----------------|
| **Android Shell** | Kotlin | WebView hosting, native bridges, NIP-55 intents |
| **PWA** | React/TypeScript | UI, state management, cryptographic operations |
| **Bifrost Node** | JavaScript | FROSTR protocol, threshold signing |

---

## Android Layer

### Package Structure

```
android/app/src/main/kotlin/com/frostr/igloo/
├── MainActivity.kt              # WebView host, bridge registration
├── InvisibleNIP55Handler.kt     # NIP-55 intent entry point
├── NIP55ContentProvider.kt      # Background signing entry point
├── bridges/                     # Native polyfill bridges
│   ├── AsyncBridge.kt           # Async IPC for NIP-55
│   ├── StorageBridge.kt         # Encrypted localStorage
│   ├── WebSocketBridge.kt       # Persistent WebSocket
│   ├── ModernCameraBridge.kt    # QR scanning
│   └── NodeStateBridge.kt       # Bifrost state sync
├── health/
│   └── IglooHealthManager.kt    # WebView health state
├── services/
│   ├── NIP55HandlerService.kt   # Handler protection service
│   └── PermissionChecker.kt     # Permission validation
├── nip55/                       # NIP-55 models
├── util/                        # Utilities
├── webview/                     # WebView management
└── debug/                       # Tracing and metrics
```

### Polyfill Bridge System

The PWA runs in WebView where certain web APIs don't work reliably. Android polyfill bridges transparently replace these APIs:

| Bridge | Web API Replaced | Native Implementation |
|--------|------------------|----------------------|
| **StorageBridge** | `localStorage` | EncryptedSharedPreferences (AES256-GCM) |
| **WebSocketBridge** | `WebSocket` | OkHttp 4.12.0 (survives app backgrounding) |
| **ModernCameraBridge** | `navigator.mediaDevices` | CameraX 1.4.0 |

**How Polyfills Work:**

```
PWA Code (unchanged)                    Android Bridge
─────────────────────                   ──────────────────
localStorage.setItem('key', 'value')  → StorageBridge.setItem()
                                        → EncryptedSharedPreferences
                                        → AES256-GCM encryption
                                        → Disk storage
```

JavaScript polyfill files in `android/app/src/main/assets/polyfills/` intercept standard APIs and redirect to native bridges.

### MainActivity

The central WebView host:

```kotlin
class MainActivity : AppCompatActivity() {
    // WebView lifecycle
    fun onCreate() {
        setupWebView()
        registerBridges()
        loadPWA()
    }

    // Called when PWA signals ready
    fun onSecurePWAReady() {
        IglooHealthManager.markHealthy()
    }

    // Bridge registration
    fun registerBridges() {
        AsyncBridge(webView)
        StorageBridge(webView)
        WebSocketBridge(webView)
        ModernCameraBridge(webView)
        NodeStateBridge(webView)
    }
}
```

### Process Isolation

| Process | Components | Purpose |
|---------|------------|---------|
| `:native_handler` | InvisibleNIP55Handler | Lightweight intent validation |
| `:main` | MainActivity, PWA, Bridges | WebView hosting, cryptographic signing |

---

## PWA Layer

### Source Structure

```
src/
├── index.tsx                    # Entry point, provider hierarchy
├── components/
│   ├── app.tsx                  # Root component with tab navigation
│   ├── dash/                    # Dashboard tab components
│   │   ├── console.tsx          # Debug console
│   │   ├── node.tsx             # Node status display
│   │   └── peers.tsx            # Peer status
│   ├── settings/                # Settings tab components
│   │   ├── group.tsx            # Group configuration
│   │   ├── peers.tsx            # Peer management
│   │   ├── relays.tsx           # Relay configuration
│   │   └── share.tsx            # Share display/QR
│   ├── permissions/             # Permissions tab
│   └── layout/                  # Header, tabs
├── context/                     # React Context providers
│   ├── settings.tsx             # App configuration
│   ├── node.tsx                 # Bifrost node lifecycle
│   ├── permissions.tsx          # NIP-55 permissions
│   └── console.tsx              # Debug logging
├── hooks/
│   └── useBifrost.ts            # Bifrost node management
├── lib/                         # Core libraries
│   ├── signer.ts                # Auto-signing execution
│   ├── cipher.ts                # NIP-04/NIP-44 encryption
│   ├── permissions.ts           # Permission storage
│   └── util.ts                  # Utilities
├── class/
│   ├── signer.ts                # BifrostSignDevice wrapper
│   └── store.ts                 # Reactive store controller
└── types/                       # TypeScript definitions
```

### React Provider Hierarchy

State flows through React Context providers:

```
StrictMode
  └── SettingsProvider          # App config (storage key: igloo-pwa)
      └── PermissionsProvider   # NIP-55 permissions (storage key: nip55_permissions_v2)
          └── ConsoleProvider   # Debug logging (100 log limit)
              └── NodeProvider  # Bifrost node lifecycle
                  └── App       # Tab-based UI
```

### Key Components

| Component | File | Purpose |
|-----------|------|---------|
| **SettingsProvider** | `context/settings.tsx` | Persists group, share, relays to storage |
| **NodeProvider** | `context/node.tsx` | Manages bifrost node lifecycle |
| **PermissionsProvider** | `context/permissions.tsx` | NIP-55 permission state |
| **BifrostSignDevice** | `class/signer.ts` | Wraps bifrost for signing operations |
| **NIP55Bridge** | `components/util/nip55.tsx` | Sets up `window.nostr.nip55` |

### Node Status States

The bifrost node (`NodeProvider`) has these states:

| State | Description |
|-------|-------------|
| `init` | Initial state, not yet configured |
| `disabled` | Node explicitly disabled |
| `locked` | Configured but needs password to unlock |
| `online` | Connected and ready for signing |
| `offline` | Disconnected, will attempt reconnection |

---

## FROSTR/Bifrost Integration

### What is FROSTR?

FROSTR is a protocol for **threshold signing** on Nostr. Instead of a single private key, FROSTR:
1. Splits the key into multiple **shares** (e.g., 3 shares)
2. Requires a **threshold** of shares to sign (e.g., 2 of 3)
3. Coordinates signing over Nostr **relays**

Igloo acts as one signing node in a FROSTR group.

### Bifrost Node

The bifrost node handles FROSTR protocol operations:

```
┌────────────────────────────────────────────────────────────────┐
│                        Bifrost Node                            │
│                                                                │
│  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐  │
│  │  Group Config   │  │  Share (Secret) │  │ Peer Discovery │  │
│  │  - Group pubkey │  │  - Key share    │  │ - Relay URLs   │  │
│  │  - Threshold    │  │  - Share index  │  │ - Peer status  │  │
│  └─────────────────┘  └─────────────────┘  └────────────────┘  │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                   Signing Protocol                       │  │
│  │  1. Receive sign request                                 │  │
│  │  2. Broadcast to peers via relay                         │  │
│  │  3. Collect partial signatures                           │  │
│  │  4. Combine when threshold reached                       │  │
│  │  5. Return complete signature                            │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│                    WebSocket ↔ Nostr Relays                    │
└────────────────────────────────────────────────────────────────┘
```

### Dependencies

| Package | Purpose |
|---------|---------|
| `@frostr/bifrost` | FROSTR node implementation |
| `@cmdcode/nostr-p2p` | P2P communication over Nostr |
| `nostr-tools` | Nostr primitives |
| `@noble/ciphers` | ChaCha20-Poly1305 (NIP-44) |
| `@noble/hashes` | SHA256, HMAC |

---

## NIP-55 Signing Pipeline

### Overview

The NIP-55 pipeline handles signing requests from external Nostr apps. It uses **health-based routing** to manage WebView availability.

### Health-Based Routing

**Core insight:** WebView cannot survive in background. Android throttles it within seconds. The architecture embraces this:

```
Request Arrives
       │
       ▼
┌────────────────────┐
│ IglooHealthManager │
│    isHealthy?      │
└────────┬───────────┘
         │
    ┌────┴────┐
    │         │
 HEALTHY   UNHEALTHY
    │         │
    ▼         ▼
 Process    Queue request
immediately Launch MainActivity
    │       Wait for healthy
    │         │
    └────┬────┘
         │
         ▼
   AsyncBridge → PWA → Bifrost → Result
```

### Request Entry Points

| Entry Point | Component | Use Case |
|-------------|-----------|----------|
| **Intent** | `InvisibleNIP55Handler` | User-initiated signing |
| **ContentProvider** | `NIP55ContentProvider` | Background auto-signing |

### Key Components

| Component | Purpose |
|-----------|---------|
| `IglooHealthManager` | Central health state, request queuing, caching |
| `AsyncBridge` | Async JavaScript IPC via WebMessageListener |
| `NIP55HandlerService` | Transient foreground service (handler protection) |
| `PermissionChecker` | Permission validation |
| `NIP55Deduplicator` | Content-based deduplication |

### Supported Operations

| Operation | Description |
|-----------|-------------|
| `get_public_key` | Return group public key |
| `sign_event` | Sign a Nostr event |
| `nip04_encrypt` | AES-CBC encryption (legacy) |
| `nip04_decrypt` | AES-CBC decryption (legacy) |
| `nip44_encrypt` | ChaCha20-Poly1305 encryption |
| `nip44_decrypt` | ChaCha20-Poly1305 decryption |

### Timeouts and Limits

| Parameter | Value |
|-----------|-------|
| Health timeout | 5 seconds |
| AsyncBridge timeout | 30 seconds |
| Result cache TTL | 5 seconds |
| Rate limit | 20 requests/sec per app |
| Max queue size | 50 requests |

---

## Security Architecture

### Threat Model

Igloo protects against:
- **Key extraction** - Private key shares never leave secure storage
- **Unauthorized signing** - Permission system controls which apps can sign
- **Replay attacks** - Content-based deduplication prevents duplicate signing

### Storage Security

All sensitive data is encrypted at rest:

| Data | Storage | Encryption |
|------|---------|------------|
| Key share | EncryptedSharedPreferences | AES256-GCM via Android Keystore |
| Permissions | EncryptedSharedPreferences | AES256-GCM via Android Keystore |
| Settings | EncryptedSharedPreferences | AES256-GCM via Android Keystore |

### Permission System

Granular control over NIP-55 operations:

| Permission Type | Description |
|-----------------|-------------|
| Kind-specific | Allow specific event kinds (e.g., kind 1 = posts) |
| Wildcard | `kind=null` applies to all event kinds |
| Per-app | Each calling app has separate permissions |

### WebView Security

- **CSP enforced** - Content Security Policy limits script sources
- **JavaScript disabled for external content** - Only PWA scripts execute
- **No file:// access** - Prevents local file exfiltration

---

## Build System

### Build Pipeline

```
npm run build:debug
       │
       ▼
┌──────────────────┐
│  esbuild         │  src/index.tsx → dist/app.js
│  (PWA build)     │  src/sw.ts → dist/sw.js
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  Copy assets     │  dist/ → android/app/src/main/assets/
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  Gradle          │  ./gradlew assembleDebug
│  (Android build) │
└────────┬─────────┘
         │
         ▼
   app-debug.apk
```

### Key Files

| File | Purpose |
|------|---------|
| `script/build.ts` | Build orchestration |
| `android/app/build.gradle` | Android configuration |
| `package.json` | NPM scripts |

### Build Commands

```bash
npm run build          # PWA only
npm run build:debug    # PWA + Android debug APK
npm run build:release  # PWA + Android release APK
```

### Output Locations

| Build | Path |
|-------|------|
| PWA | `dist/` |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `android/app/build/outputs/apk/release/app-release.apk` |

---

## See Also

- `DEVELOPMENT.md` - Development workflow and debugging
- `CONVENTIONS.md` - Code style conventions
- `protocols/NIP-55.md` - NIP-55 protocol specification
