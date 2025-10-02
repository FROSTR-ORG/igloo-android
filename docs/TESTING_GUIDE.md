# Testing Guide - Hybrid Service Architecture

## Overview

This guide provides step-by-step instructions for testing the newly implemented hybrid service architecture with WebSocket infrastructure and on-demand PWA loading.

## Prerequisites

1. **Android Device/Emulator**: API 26+ (Android 8.0+)
2. **ADB Access**: Ensure ADB is configured and device is connected
3. **Amethyst App**: Install Amethyst (or another Nostr client) for NIP-55 testing
4. **User Login**: Have a user account logged into the Igloo PWA

## Pre-Testing Setup

### 1. Clear Old Data (Optional, but recommended for clean test)
```bash
adb shell pm clear com.frostr.igloo
```

### 2. Install Latest APK
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Configure Logging
```bash
# Clear existing logs
adb logcat -c

# Start filtered logging
adb logcat -s \
  "IglooBackgroundService:*" \
  "WebSocketManager:*" \
  "RelayConnectionManager:*" \
  "BatteryPowerManager:*" \
  "NetworkManager:*" \
  "SubscriptionManager:*" \
  "NostrEventQueue:*" \
  "NostrEventHandler:*" \
  "InvisibleNIP55Handler:*" \
  "AsyncBridge:*"
```

## Test Plan

### Phase 1: Service Initialization ✓

**Objective**: Verify IglooBackgroundService starts correctly without PWA

**Steps**:
1. Launch the Igloo app
2. Check logcat for initialization sequence

**Expected Results**:
```
✓ StorageBridge initialized
✓ BatteryPowerManager initialized
✓ NetworkManager initialized
✓ RelayConnectionManager initialized
✓ SubscriptionManager initialized
✓ WebSocket infrastructure started
✓ IglooBackgroundService ready (PWA not loaded, WebSockets active)
```

**Success Criteria**:
- [ ] Service starts without errors
- [ ] Foreground notification appears ("Igloo Signer - Monitoring relays")
- [ ] No PWA loaded yet (pwaState = IDLE)
- [ ] StorageBridge can read from SecureStorage

**Troubleshooting**:
- If service doesn't start: Check AndroidManifest permissions (FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK)
- If storage fails: Ensure user is logged into PWA and has data in SecureStorage

---

### Phase 2: WebSocket Connectivity ✓

**Objective**: Verify WebSocket connections establish and subscriptions are sent

**Steps**:
1. Wait 5-10 seconds after app launch
2. Check logcat for WebSocket activity

**Expected Results**:
```
✓ Loaded config for pubkey: [user_pubkey_prefix]...
  Relays: 3, DMs: true, Mentions: true, Zaps: true
✓ Generated 3 subscriptions
Connecting to 3 relays...
[relay.damus.io] ✓ Connected
✓ Sent subscription dms_[pubkey] to wss://relay.damus.io
✓ Sent subscription mentions_[pubkey] to wss://relay.damus.io
✓ Sent subscription zaps_[pubkey] to wss://relay.damus.io
✓ All relays connected (3/3)
```

**Success Criteria**:
- [ ] User pubkey loaded from SecureStorage
- [ ] Relay URLs loaded from SecureStorage (or defaults used)
- [ ] Connections established to all relays
- [ ] Subscription REQ messages sent
- [ ] No "No user pubkey found" errors

**Troubleshooting**:
- If "No user pubkey found": User needs to log into PWA first (open main activity, log in, then test)
- If relays don't connect: Check network connectivity, verify relay URLs
- If subscriptions not sent: Check subscription generation logic

---

### Phase 3: Event Reception ✓

**Objective**: Verify events are received, queued, and batched correctly

**Steps**:
1. Send yourself a DM or mention from another Nostr client
2. Watch logcat for event processing

**Expected Results**:
```
Received EOSE from wss://relay.damus.io
✓ Parsed event: kind=4, id=abc12345...
Event received: HIGH priority from wss://relay.damus.io
✓ Enqueued HIGH priority event abc12345... (queue: 1)
Processing batch: 1 events (HIGH: 1, NORMAL: 0, LOW: 0)
Batch decision: WAKE_PWA (reason: High priority events: 1 (DMs/zaps/mentions))
✓ Stored 1 events in SecureStorage (total: 1)
PWA wake requested: High priority events: 1 (DMs/zaps/mentions) (1 events)
```

**Success Criteria**:
- [ ] Events parsed from relay messages
- [ ] Events enqueued with correct priority (DMs = HIGH)
- [ ] Deduplication works (duplicate events rejected)
- [ ] Batch processing triggers for HIGH priority immediately
- [ ] Events stored in SecureStorage

**Troubleshooting**:
- If no events received: Check relay subscriptions are active
- If parsing fails: Verify Nostr event JSON format
- If priority wrong: Check determineEventPriority() logic

---

### Phase 4: PWA On-Demand Loading ✓

**Objective**: Verify PWA loads only when needed and unloads after idle

**Steps**:
1. Trigger a high-priority event (send yourself a DM)
2. Wait for PWA to load
3. Wait 5 minutes to see PWA unload

**Expected Results**:
```
PWA wake requested: High priority events: 1 (DMs/zaps/mentions)
Loading PWA on-demand...
✓ PWA loaded successfully
PWA active - events available in SecureStorage
[After 5 minutes]
PWA idle timeout - unloading
Unloading PWA...
✓ PWA unloaded, memory reclaimed
```

**Success Criteria**:
- [ ] PWA loads within 30 seconds
- [ ] PWA state changes: IDLE → LOADING → ACTIVE
- [ ] AsyncBridge initializes correctly
- [ ] Polyfill bridges registered (StorageBridge, CameraBridge)
- [ ] PWA unloads after 5 minutes idle
- [ ] Memory reclaimed (System.gc() called)

**Troubleshooting**:
- If PWA fails to load: Check WebView configuration, verify localhost:3000 is accessible
- If load timeout: Increase timeout or check for PWA errors in WebView console
- If won't unload: Check idleHandler is working

---

### Phase 5: Battery Optimization ✓

**Objective**: Verify battery optimizations are working

**Steps**:
1. Check ping interval changes with app state
2. Monitor battery usage over 1 hour

**Expected Results**:
```
App state changed: FOREGROUND → BACKGROUND
Ping interval changed: 30s → 120s
Updating ping interval: 30s → 120s (recreating client)
✓ Updated ping interval for all connections

[Low battery simulation]
Battery changed: 20% (was 85%), charging: false (was false)
Ping interval changed: 120s → 300s
```

**Success Criteria**:
- [ ] Foreground ping interval: 30s
- [ ] Background ping interval: 120s
- [ ] Low battery (≤30%): 300s
- [ ] Critical battery (≤15%): 600s
- [ ] Doze mode detected and handled
- [ ] Reconnection attempts limited on low battery

**Battery Drain Targets**:
- Idle (no activity): ≤1.5% per hour
- Light usage (occasional DMs): ≤2.0% per hour
- Moderate usage (active chatting): ≤3.5% per hour

**Troubleshooting**:
- If ping interval doesn't change: Check BatteryPowerManager state tracking
- If battery drain too high: Check wake lock usage, verify PWA unloading

---

### Phase 6: NIP-55 Processing ✓

**Objective**: Verify external NIP-55 requests work end-to-end

**Steps**:
1. Open Amethyst (or another Nostr client)
2. Try to sign an event using Igloo
3. Check logcat for NIP-55 flow

**Expected Results**:
```
[InvisibleNIP55Handler]
NIP-55 request received from external app
Parsed NIP-55 request: sign_event from com.vitorpamplona.amethyst
Permission status: allowed
✓ Connected to IglooBackgroundService
Processing NIP-55 request via service: sign_event

[IglooBackgroundService]
Processing NIP-55 request: sign_event (nip55_123456)
PWA not loaded, loading for NIP-55 request
Loading PWA on-demand...
✓ PWA loaded successfully
Calling AsyncBridge.callNip55Async: sign_event
✓ NIP-55 request completed: ok=true

[InvisibleNIP55Handler]
✓ Request processed successfully
Returning RESULT_OK to calling app
✓ Returned focus to: com.vitorpamplona.amethyst
```

**Success Criteria**:
- [ ] External intent received and parsed
- [ ] Permission checked correctly
- [ ] Service binding succeeds
- [ ] PWA loads if needed
- [ ] AsyncBridge calls PWA successfully
- [ ] Result returned to calling app
- [ ] Focus returns to calling app

**Test All NIP-55 Operations**:
- [ ] get_public_key
- [ ] sign_event
- [ ] nip04_encrypt
- [ ] nip04_decrypt
- [ ] nip44_encrypt
- [ ] nip44_decrypt
- [ ] decrypt_zap_event

**Troubleshooting**:
- If binding fails: Check service is running, verify LocalBinder
- If PWA doesn't load: Check IglooBackgroundService.loadPWA()
- If AsyncBridge fails: Verify PWA's window.nostr.nip55 interface
- If no result: Check timeout settings (30s default)

---

### Phase 7: Permission Flow ✓

**Objective**: Verify permission prompt, allow, and deny flows

**Steps**:
1. Clear permissions: `adb shell pm clear com.frostr.igloo`
2. Try NIP-55 request from new app (permission prompt)
3. Grant permission
4. Try NIP-55 request again (auto-approved)
5. Try from another app and deny permission
6. Try NIP-55 request again (auto-denied)

**Expected Results**:
```
[First request - no permission]
Permission status: prompt_required
Permission prompt required - will show user prompt
[PWA shows user prompt, user grants]
Permission saved to SecureStorage

[Second request - allowed]
Permission status: allowed
Permission allowed - processing in background
[Processes without PWA coming to foreground]

[From new app - deny]
Permission status: prompt_required
[User denies]
Permission saved as denied

[Third request - denied]
Permission status: denied
Permission denied - returning error
Returning RESULT_CANCELED: Permission denied
```

**Success Criteria**:
- [ ] First request shows user prompt
- [ ] Permission saved after user decision
- [ ] Auto-approved requests process in background
- [ ] Auto-denied requests return immediately
- [ ] Permission storage persists across app restarts

**Troubleshooting**:
- If prompt doesn't show: Check PWA's NIP-55 prompt UI
- If permission not saved: Verify StorageBridge writing to "nip55_permissions"
- If auto-approval doesn't work: Check permission matching logic

---

### Phase 8: Connection Health Monitoring ✓

**Objective**: Verify connection health tracking and recovery

**Steps**:
1. Enable airplane mode (disconnect network)
2. Wait 30 seconds
3. Disable airplane mode (reconnect)
4. Check logcat for reconnection attempts

**Expected Results**:
```
Network lost
Network state changed: available=false
[relay.damus.io] ✗ Connection failed
Network unavailable, skipping reconnect

Network available
Network state changed: available=true
Reconnecting to wss://relay.damus.io after network restoration
[relay.damus.io] Connecting... (attempt 1)
[relay.damus.io] ✓ Connected
```

**Success Criteria**:
- [ ] Network loss detected immediately
- [ ] Reconnection skipped when network unavailable
- [ ] Network restoration detected
- [ ] Automatic reconnection on network restoration
- [ ] Failed relays reconnect (others stay connected)
- [ ] Exponential backoff on repeated failures

**Troubleshooting**:
- If network state not detected: Check NetworkManager callbacks
- If doesn't reconnect: Verify RelayConnectionManager.reconnect()
- If hammering failed connection: Check exponential backoff logic

---

### Phase 9: Stress Testing

**Objective**: Verify system handles high load

**Test 9a: Multiple Simultaneous Events**
1. Have multiple users send you DMs/mentions at once
2. Monitor queue handling

**Expected**:
- [ ] All events enqueued
- [ ] Deduplication prevents duplicates
- [ ] Priority queue orders correctly
- [ ] Batching works (10s/60s windows)
- [ ] No events dropped

**Test 9b: Rapid NIP-55 Requests**
1. Trigger multiple NIP-55 requests quickly (sign multiple events)
2. Monitor request processing

**Expected**:
- [ ] All requests processed in order
- [ ] No timeouts
- [ ] PWA stays loaded during burst
- [ ] Results returned correctly

**Test 9c: Long-Running Service**
1. Leave app running for 24+ hours
2. Monitor memory usage and stability

**Expected**:
- [ ] No memory leaks
- [ ] Service doesn't crash
- [ ] PWA loads/unloads multiple times
- [ ] WebSocket connections stable
- [ ] Battery drain within targets

---

## Diagnostics Commands

### Check Service Status
```bash
adb shell dumpsys activity services com.frostr.igloo.IglooBackgroundService
```

### Check Connection Health
```bash
# Via logcat, look for:
# "Connection health:" logs from service
```

### Check Event Queue Stats
```bash
# Via logcat, look for:
# "Queue statistics:" logs
```

### Check Battery Usage
```bash
adb shell dumpsys batterystats --charged com.frostr.igloo
```

### Force Service Stop
```bash
adb shell am force-stop com.frostr.igloo
```

### Simulate Low Battery
```bash
adb shell dumpsys battery set level 15
adb shell dumpsys battery set ac 0
adb shell dumpsys battery set usb 0
```

### Reset Battery Simulation
```bash
adb shell dumpsys battery reset
```

---

## Known Issues / Limitations

### Current Implementation

1. **JSON Parsing**: Basic Gson parsing implemented, may need refinement for complex Nostr events
2. **User Prompts**: Permission status passed to PWA, but PWA must handle showing prompts
3. **Notification Generation**: TODO placeholders for native notifications (Phase 3 feature)
4. **Feed Subscription**: Disabled by default (battery intensive), can be enabled in settings
5. **Error Handling**: Basic error handling, may need more robust recovery

### Expected Failures (Not Yet Implemented)

1. **First Run Without Login**: Service will log "No user pubkey found" until user logs into PWA
2. **PWA NIP-55 Interface**: If PWA doesn't expose `window.nostr.nip55`, AsyncBridge will fail
3. **Native Notifications**: Generate notifications for DMs/zaps (placeholder TODOs exist)
4. **Boot Receiver**: Service doesn't auto-start on device boot (requires RECEIVE_BOOT_COMPLETED permission)

---

## Success Checklist

### Minimum Viable Test (Quick Validation)
- [ ] Service starts without errors
- [ ] WebSocket connects to at least one relay
- [ ] Can receive a DM/mention
- [ ] Event triggers PWA wake
- [ ] PWA unloads after 5 minutes
- [ ] NIP-55 sign_event works from Amethyst

### Full Alpha Validation
- [ ] All Phase 1-6 tests pass
- [ ] Battery drain within targets
- [ ] All 7 NIP-55 operations work
- [ ] Permission flow works (prompt/allow/deny)
- [ ] Network recovery works
- [ ] 24-hour stability test passes

---

## Reporting Issues

When reporting issues, include:

1. **Logcat output** (filtered to relevant tags)
2. **Device info** (Android version, device model)
3. **User state** (logged in? pubkey present?)
4. **Steps to reproduce**
5. **Expected vs actual behavior**

### Example Issue Report

```
Title: WebSocket won't connect to relay.damus.io

Device: Pixel 7, Android 14
User: Logged in, pubkey: abc123...

Steps:
1. Launch app
2. Wait 30 seconds
3. Check logcat

Expected:
✓ Connected to wss://relay.damus.io

Actual:
✗ Connection failed to wss://relay.damus.io: timeout

Logs:
[Include relevant logcat]
```

---

## Next Steps After Testing

1. **Fix Critical Issues**: Address any blockers found in Phase 1-6
2. **Optimize Battery**: Fine-tune if battery drain exceeds targets
3. **Implement Native Notifications**: Complete TODO placeholders
4. **Add Boot Receiver**: Auto-start service on device boot
5. **Production Hardening**: Error handling, edge cases, logging
6. **Performance Profiling**: Memory leaks, CPU usage, network efficiency

---

*Testing guide created for alpha release - 2025-10-01*
