# NIP-55 Pipeline Architecture

This document provides a comprehensive overview of the Igloo NIP-55 signing pipeline architecture.

## Table of Contents

1. [Overview](#overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Request Flows](#request-flows)
4. [Implemented Features](#implemented-features)
5. [Key Components](#key-components)
6. [File Reference](#file-reference)

---

## Overview

The Igloo NIP-55 pipeline handles cryptographic signing requests from external Nostr apps (like Amethyst) through a sophisticated multi-process, multi-layer architecture. The system supports two parallel request flows:

1. **Intent-based flow** - Explicit user interaction via `nostrsigner:` URIs
2. **ContentProvider flow** - Background signing for pre-approved operations

Both flows converge at the PWA layer where the Bifrost node performs actual cryptographic operations.

### Process Isolation

The architecture uses Android process isolation for security:

| Process | Components | Purpose |
|---------|------------|---------|
| `:native_handler` | InvisibleNIP55Handler | Lightweight intent validation, permission checking |
| `:main` | MainActivity, PWA, Bridges | WebView hosting, cryptographic signing |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              EXTERNAL NOSTR APPLICATIONS                            │
│                        (Amethyst, Coracle, Damus, etc.)                             │
└──────────────────────────────────┬──────────────────────────────────────────────────┘
                                   │
           ┌───────────────────────┴───────────────────────┐
           │                                               │
           ▼                                               ▼
┌─────────────────────────┐                 ┌─────────────────────────────┐
│   Intent-Based Flow     │                 │   ContentProvider Flow      │
│   nostrsigner: URI      │                 │   contentResolver.query()   │
└───────────┬─────────────┘                 └─────────────┬───────────────┘
            │                                             │
            ▼                                             ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              :native_handler process                                │
│  ┌───────────────────────────────────────────────────────────────────────────────┐  │
│  │                        InvisibleNIP55Handler.kt                               │  │
│  │  ┌──────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐   │  │
│  │  │ parseNIP55Request│→ │ Deduplication   │→ │ checkPermission()           │   │  │
│  │  │ (extract params) │  │ (NIP55Dedup-    │  │ • allowed → fast signing    │   │  │
│  │  │                  │  │  licator)       │  │ • denied → return error     │   │  │
│  │  └──────────────────┘  └─────────────────┘  │ • prompt_required → dialog  │   │  │
│  │                                             └───────────────┬─────────────┘   │  │
│  │  ┌──────────────────────────────────────────────────────────┼───────────────┐ │  │
│  │  │                       Request Queuing                    │               │ │  │
│  │  │  pendingRequests: MutableList<NIP55Request>              │               │ │  │
│  │  │  Batch return after 150ms delay                          │               │ │  │
│  │  └──────────────────────────────────────────────────────────┼───────────────┘ │  │
│  └─────────────────────────────────────────────────────────────┼─────────────────┘  │
└────────────────────────────────────────────────────────────────┼────────────────────┘
                                                                 │
                    ┌────────────────────────────────────────────┤
                    │                                            │
                    ▼                                            ▼
┌──────────────────────────────────┐              ┌──────────────────────────────────┐
│      NIP55RequestBridge.kt       │              │       Permission Dialog          │
│   (Cross-task request queue)     │              │   (NIP55PermissionDialog.kt)     │
│   • Queues when MainActivity     │              │   • Single or bulk permission    │
│     not available                │              │   • User approval/denial         │
│   • Singleton pattern            │              │   • Persists to secure storage   │
└────────────────┬─────────────────┘              └──────────────────────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                                   :main process                                      │
│  ┌────────────────────────────────────────────────────────────────────────────────┐  │
│  │                              MainActivity.kt                                   │  │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐  │  │
│  │  │                          Secure WebView                                  │  │  │
│  │  │  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────────┐  │  │  │
│  │  │  │  PWA (React)    │  │  Polyfill       │  │  Bridge Registration     │  │  │  │
│  │  │  │  dist/app.js    │  │  Bridges        │  │  • AsyncBridge           │  │  │  │
│  │  │  │                 │  │  • WebSocket    │  │  • WebSocketBridge       │  │  │  │
│  │  │  │                 │  │  • Storage      │  │  • StorageBridge         │  │  │  │
│  │  │  │                 │  │  • Camera       │  │  • NodeStateBridge       │  │  │  │
│  │  │  └─────────────────┘  └─────────────────┘  └──────────────────────────┘  │  │  │
│  │  └──────────────────────────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                      │
│  ┌────────────────────────────────────────────────────────────────────────────────┐  │
│  │                         NIP55ContentProvider.kt                                │  │
│  │  • Wake lock management (reference counted)                                    │  │
│  │  • Stale detection (30s threshold)                                             │  │
│  │  • Result caching (5s TTL)                                                     │  │
│  │  • Direct WebView JavaScript execution                                         │  │
│  └────────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┬───────────────────────┘
                                                               │
                                                               ▼
┌───────────────────────────────────────────────────────────────────────────────────────┐
│                              AsyncBridge.kt (IPC Layer)                               │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐  │
│  │  WebMessageListener-based async/await pattern                                   │  │
│  │  • callNip55Async() → suspendCancellableCoroutine                               │  │
│  │  • 30s timeout per operation                                                    │  │
│  │  • JavaScript code injection via evaluateJavascript()                           │  │
│  │  • Result via window.androidBridge.postMessage()                                │  │
│  └─────────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┬────────────────────────┘
                                                               │
                                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                 PWA JavaScript Layer                                │
│  ┌───────────────────────────────────────────────────────────────────────────────┐  │
│  │  window.nostr.nip55(request) → executeAutoSigning()                           │  │
│  │                                                                               │  │
│  │  ┌─────────────────────────────────────────────────────────────────────────┐  │  │
│  │  │                    SigningBatchQueue (src/lib/batch-signer.ts)          │  │  │
│  │  │  • Deduplication by event ID (not request ID)                           │  │  │
│  │  │  • Result caching (5s TTL)                                              │  │  │
│  │  │  • Max queue size: 10 unique events                                     │  │  │
│  │  │  • Batch delay: 50ms for request coalescing                             │  │  │
│  │  │  • Single batch in flight at a time                                     │  │  │
│  │  │  • Auto-reconnection after 2 consecutive failures                       │  │  │
│  │  └─────────────────────────────────────────────────────────────────────────┘  │  │
│  │                                      │                                        │  │
│  │                                      ▼                                        │  │
│  │  ┌─────────────────────────────────────────────────────────────────────────┐  │  │
│  │  │                    BifrostSignDevice (src/class/signer.ts)              │  │  │
│  │  │  • sign_event() → Bifrost node signing                                  │  │  │
│  │  │  • nip04_encrypt/decrypt() → ECDH + AES-CBC                             │  │  │
│  │  │  • nip44_encrypt/decrypt() → ECDH + ChaCha20-Poly1305                   │  │  │
│  │  │  • get_pubkey() → Group public key                                      │  │  │
│  │  └─────────────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┬──────────────────────┘
                                                               │
                                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              Bifrost Node (P2P Network)                             │
│  ┌───────────────────────────────────────────────────────────────────────────────┐  │
│  │  FROSTR distributed key management                                            │  │
│  │  • Peer-to-peer signing protocol                                              │  │
│  │  • Threshold signatures                                                       │  │
│  │  • WebSocket connections to Nostr relays                                      │  │
│  └───────────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Request Flows

### 1. Fast Signing Flow (Permission Pre-Granted)

This is the optimal path when the calling app already has permission to sign.

```
Amethyst                        Igloo
   │                              │
   │──nostrsigner: intent────────▶│ InvisibleNIP55Handler.onCreate()
   │                              │    ├─ parseNIP55Request()
   │                              │    ├─ NIP55Deduplicator.check() → not duplicate
   │                              │    ├─ checkPermission() → "allowed"
   │                              │    └─ launchMainActivityForFastSigning()
   │                              │         ├─ NIP55SigningService.start() [transient]
   │                              │         ├─ PendingNIP55ResultRegistry.registerCallback()
   │                              │         └─ NIP55RequestBridge.sendRequest()
   │                              │
   │                              │ MainActivity.onResume()
   │                              │    ├─ NIP55RequestBridge.registerListener()
   │                              │    └─ processNIP55Request()
   │                              │         ├─ AsyncBridge.callNip55Async()
   │                              │         │    └─ webView.evaluateJavascript()
   │                              │         │
   │                              │         │ [PWA Layer]
   │                              │         │ window.nostr.nip55(request)
   │                              │         │    ├─ SigningBatchQueue.add(eventId)
   │                              │         │    └─ BifrostSignDevice.sign_event()
   │                              │         │         └─ Bifrost P2P signing
   │                              │         │
   │                              │         └─ PendingNIP55ResultRegistry.deliverResult()
   │                              │
   │                              │ InvisibleNIP55Handler (callback invoked)
   │                              │    ├─ completedResults.add(result)
   │                              │    ├─ scheduleBatchReturn() [150ms delay]
   │                              │    └─ returnBatchResults()
   │                              │
   │◀──────────RESULT_OK──────────│ finish()
   │                              │
```

### 2. ContentProvider Flow (Background Signing)

Used by apps that prefer synchronous ContentProvider queries for background signing.

```
Amethyst                                    Igloo
   │                                          │
   │──contentResolver.query()────────────────▶│ NIP55ContentProvider.query()
   │  content://com.frostr.igloo.SIGN_EVENT   │    ├─ acquireWakeLock()
   │                                          │    ├─ NIP55Deduplicator.check()
   │                                          │    │    └─ Check resultCache (5s TTL)
   │                                          │    ├─ MainActivity.getWebViewInstance()
   │                                          │    │    └─ WebView available? ✓
   │                                          │    ├─ hasAutomaticPermission() → ✓
   │                                          │    └─ executeNIP55Operation()
   │                                          │         ├─ webView.evaluateJavascript()
   │                                          │         │    └─ window.nostr.nip55(request)
   │                                          │         └─ NIP55ResultBridge.waitForResult()
   │                                          │
   │◀────────────Cursor with result───────────│ releaseWakeLock()
   │                                          │
```

### 3. Permission Dialog Flow

Triggered when the calling app needs user approval for the operation.

```
Amethyst                        Igloo
   │                              │
   │──nostrsigner: intent────────▶│ InvisibleNIP55Handler.onCreate()
   │                              │    ├─ parseNIP55Request()
   │                              │    └─ checkPermission() → "prompt_required"
   │                              │
   │                              │ MainActivity (SHOW_PERMISSION_DIALOG)
   │                              │    └─ NIP55PermissionDialog.show()
   │                              │
   │                    ┌─────────┼─────────┐
   │                    │   User Decision   │
   │                    │   ┌───┐   ┌───┐   │
   │                    │   │ ✓ │   │ ✗ │   │
   │                    │   └───┘   └───┘   │
   │                    └─────────┼─────────┘
   │                              │
   │                              │ [If approved]
   │                              │    ├─ savePermission()
   │                              │    ├─ handleApprovedPermission()
   │                              │    └─ [Continue to signing flow]
   │                              │
   │◀───────────result────────────│
   │                              │
```

---

## Implemented Features

### 1. Multi-Layer Request Deduplication

Prevents duplicate signing of the same event across retry attempts.

| Layer | Location | Strategy | TTL |
|-------|----------|----------|-----|
| Android Intent | `NIP55Deduplicator.kt` | By operation content (event ID, ciphertext hash) | Session |
| ContentProvider | `NIP55ContentProvider.kt` | Result cache by dedup key | 5 seconds |
| PWA Batch Queue | `batch-signer.ts` | By Nostr event ID | 5 seconds |

**Key Design Decision**: Deduplication is by **event content** (event ID), not request ID. This handles Amethyst's behavior of generating new request IDs on each retry (30s timeout).

**Deduplication Key Generation**:
```
sign_event       → callingApp:sign_event:eventId
nip04_decrypt    → callingApp:nip04_decrypt:ciphertext.hashCode():pubkey
nip04_encrypt    → callingApp:nip04_encrypt:plaintext.hashCode():pubkey
get_public_key   → callingApp:get_public_key
```

### 2. Request Queuing System

Handles concurrent requests and cross-task communication.

| Component | Purpose | Capacity |
|-----------|---------|----------|
| `InvisibleNIP55Handler.pendingRequests` | Concurrent intent handling | Unlimited |
| `NIP55RequestBridge` | Cross-task request delivery | Queue until MainActivity ready |
| `SigningBatchQueue.pendingByEventId` | PWA-side batching | 10 unique events max |

### 3. Cross-Task Communication

Solves the problem of delivering results between Android tasks.

```
InvisibleNIP55Handler (Task A)     MainActivity (Task B)
        │                                   │
        ├─registerCallback(id)─────────────▶│ PendingNIP55ResultRegistry
        │                                   │
        ├─sendRequest()────────────────────▶│ NIP55RequestBridge
        │                                   │
        │◀────────deliverResult(id)─────────┤
        │                                   │
```

**Components**:
- `PendingNIP55ResultRegistry`: Thread-safe callback registry using `ConcurrentHashMap`
- `NIP55RequestBridge`: Singleton that queues requests when MainActivity unavailable

### 4. Batch Result Return

Optimizes for apps that send multiple rapid requests.

- Requests accumulate for **150ms** before returning
- Single intent can return multiple results via `results` JSON array
- Amber-compatible response format

### 5. Permission System

Granular control over which operations each app can perform.

| Feature | Description |
|---------|-------------|
| Kind-specific permissions | Allow/deny specific event kinds (e.g., kind 1 = posts) |
| Wildcard permissions | `kind=null` applies to all event kinds |
| Persistent storage | AES256-GCM encrypted via StorageBridge |
| Storage key | `nip55_permissions_v2` |

### 6. Auto-Unlock for Signing

Allows signing even after PWA navigation/reload.

- Session password stored in `sessionStorage` after unlock
- 3-second timeout for auto-unlock via `waitForNodeClient()`
- Enables seamless signing without re-prompting for password

### 7. Stale Detection

Prevents ContentProvider from hanging when app is throttled.

- Tracks `lastActiveTimestamp` (updated in `onResume`)
- **30-second threshold** for "stale" detection
- Stale + not persistent mode → return null → Intent fallback

### 8. Foreground Service

Keeps process alive during signing operations.

| Mode | Purpose | Wake Lock Timeout |
|------|---------|-------------------|
| Transient | Single signing operation | 60 seconds |
| Persistent | Node online, background enabled | Indefinite |

**Notification**: "Igloo ready" (persistent) or "Signing Nostr event" (transient)

---

## Key Components

### Android Layer

| Component | File | Purpose |
|-----------|------|---------|
| **InvisibleNIP55Handler** | `InvisibleNIP55Handler.kt` | Intent entry point, permission checking, result batching |
| **MainActivity** | `MainActivity.kt` | WebView host, bridge registration, request processing |
| **NIP55ContentProvider** | `NIP55ContentProvider.kt` | Background signing, wake locks, result caching |
| **AsyncBridge** | `AsyncBridge.kt` | Modern async IPC via WebMessageListener |
| **NIP55SigningService** | `NIP55SigningService.kt` | Foreground service for process keep-alive |
| **NIP55RequestBridge** | `NIP55RequestBridge.kt` | Cross-task request queuing singleton |
| **PendingNIP55ResultRegistry** | `PendingNIP55ResultRegistry.kt` | Cross-task result delivery registry |
| **NIP55Deduplicator** | `util/NIP55Deduplicator.kt` | Shared deduplication logic |

### PWA Layer

| Component | File | Purpose |
|-----------|------|---------|
| **executeAutoSigning** | `src/lib/signer.ts` | Auto-signing execution, bridge creation |
| **SigningBatchQueue** | `src/lib/batch-signer.ts` | PWA-side dedup and batching queue |
| **BifrostSignDevice** | `src/class/signer.ts` | Wraps bifrost node for signing operations |
| **NIP55Bridge** | `src/components/util/nip55.tsx` | Sets up window.nostr.nip55 interface |

---

## File Reference

### Core Pipeline Files

| File | Lines | Purpose |
|------|-------|---------|
| `InvisibleNIP55Handler.kt` | ~950 | Intent entry point, permission checking, result batching |
| `MainActivity.kt` | ~1200 | WebView host, bridge registration, request processing |
| `NIP55ContentProvider.kt` | ~720 | Background signing, wake locks, result caching |
| `AsyncBridge.kt` | ~320 | Modern async IPC via WebMessageListener |
| `NIP55SigningService.kt` | ~150 | Foreground service for process keep-alive |
| `NIP55RequestBridge.kt` | ~100 | Cross-task request queuing singleton |
| `PendingNIP55ResultRegistry.kt` | ~50 | Cross-task result delivery registry |
| `util/NIP55Deduplicator.kt` | ~80 | Shared deduplication logic |

### PWA Files

| File | Lines | Purpose |
|------|-------|---------|
| `src/lib/signer.ts` | ~180 | Auto-signing execution, bridge creation |
| `src/lib/batch-signer.ts` | ~240 | PWA-side dedup and batching queue |
| `src/class/signer.ts` | ~90 | BifrostSignDevice wrapper |
| `src/components/util/nip55.tsx` | ~70 | window.nostr.nip55 interface setup |

### Bridge Files

| File | Purpose |
|------|---------|
| `bridges/WebSocketBridge.kt` | WebSocket polyfill for mobile persistence |
| `bridges/StorageBridge.kt` | Encrypted localStorage replacement |
| `bridges/ModernCameraBridge.kt` | CameraX integration for QR scanning |
| `bridges/UnifiedSigningBridge.kt` | JavaScript interface for signing |
| `bridges/NodeStateBridge.kt` | Bifrost node state communication |

---

## Timeouts and Thresholds

| Component | Timeout | Purpose |
|-----------|---------|---------|
| AsyncBridge | 30s | Per-operation timeout |
| InvisibleNIP55Handler | 30s | Request timeout |
| InvisibleNIP55Handler | 60s | Permission prompt timeout |
| ContentProvider | 30s | Query timeout |
| Batch return delay | 150ms | Collect concurrent requests |
| Batch queue delay | 50ms | Coalesce PWA requests |
| Stale detection | 30s | ContentProvider freshness |
| Wake lock (transient) | 60s | Single operation limit |
| Result cache TTL | 5s | Deduplication window |

---

## Logging Tags

For debugging, filter logcat with these tags:

```bash
adb logcat -s "InvisibleNIP55Handler:*" "SecureIglooWrapper:*" "AsyncBridge:*" \
              "NIP55ContentProvider:*" "NIP55SigningService:*" "NIP55RequestBridge:*" \
              "NIP55ResultRegistry:*"
```

| Tag | Component |
|-----|-----------|
| `InvisibleNIP55Handler` | Intent parsing, permission checking |
| `SecureIglooWrapper` | MainActivity lifecycle, WebView |
| `AsyncBridge` | JavaScript IPC |
| `NIP55ContentProvider` | Background signing |
| `NIP55SigningService` | Foreground service |
| `NIP55RequestBridge` | Cross-task queuing |
| `NIP55ResultRegistry` | Result callbacks |
| `WebSocketBridge` | WebSocket connections |
| `StorageBridge` | Encrypted storage |

---

## See Also

- `PIPELINE.md` - Original pipeline documentation
- `RELEASE.md` - Release build guide
- `NIP55_BACKGROUND_SIGNING_ANALYSIS.md` - Background signing limitations
- `src/README.md` - PWA source code documentation
