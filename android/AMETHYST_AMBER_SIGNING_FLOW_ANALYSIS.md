# Amethyst + Amber Signing Flow Analysis

**Date**: 2025-10-03
**Test Environment**: Android Emulator, Amethyst (Nostr client), Amber (NIP-55 signer)
**Purpose**: Understand how Amber successfully implements NIP-55 ContentProvider to guide Igloo implementation

---

## Executive Summary

Amber uses a **ContentProvider-based architecture** for NIP-55 signing, which differs fundamentally from Igloo's Intent-based approach. This analysis documents the complete signing flow between Amethyst and Amber to enable Igloo to implement ContentProvider support.

**Key Finding**: Amethyst queries Amber's ContentProvider using **operation-specific authorities** (e.g., `content://com.greenart7c3.nostrsigner.SIGN_EVENT`) rather than a single authority with different paths. This pattern must be matched exactly for compatibility.

---

## Signing Flow Timeline

### Test Scenario: User Signs Event in Amethyst

**19:33:43** - Amethyst launches
```
ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER]
                     cmp=com.vitorpamplona.amethyst/.ui.MainActivity}
```

**19:33:49.023** - Amethyst queries Amber ContentProvider for NIP-44 encryption
```
Amber: Querying content://com.greenart7c3.nostrsigner.NIP44_ENCRYPT has context true
```

**19:33:49.029** - Amber MainActivity launched via Intent
```
ActivityTaskManager: START u0 {act=android.intent.action.VIEW dat=nostrsigner:
                     pkg=com.greenart7c3.nostrsigner
                     cmp=com.greenart7c3.nostrsigner/.MainActivity}
                     LAUNCH_MULTIPLE result code=0
```

**19:33:49.035** - Amber creates new MainActivity instance
```
Amber: onCreate MainActivity
Amber: Setting main activity ref to com.greenart7c3.nostrsigner.MainActivity@1d33b4b
```

**19:33:49.078** - Amber loads account
```
Amber: getAccount: npub1vjr49m34fg73vwucey8kmd0zaua983798ta4dseypymhcv3sne6s9vq7wl
```

**19:33:49.082** - Amber UI displayed (51ms)
```
ActivityTaskManager: Displayed com.greenart7c3.nostrsigner/.MainActivity for user 0: +51ms
```

**19:33:49.333** - Amethyst queries for event signing
```
Amber: Querying content://com.greenart7c3.nostrsigner.SIGN_EVENT has context true
```

**19:33:49.339** - Second MainActivity Intent (result code=3)
```
ActivityTaskManager: START u0 {act=android.intent.action.VIEW dat=nostrsigner:
                     LAUNCH_MULTIPLE result code=3}
```

**19:33:49.356** - Reuses existing MainActivity
```
Amber: Setting main activity ref to com.greenart7c3.nostrsigner.MainActivity@1d33b4b
(Same instance as before)
```

**19:33:56.646-671** - Multiple rapid SIGN_EVENT queries (7 seconds later)
```
Amber: Querying content://com.greenart7c3.nostrsigner.SIGN_EVENT (3 queries in 25ms)
```

**19:33:56.695** - New MainActivity Intent
```
ActivityTaskManager: START u0 LAUNCH_MULTIPLE result code=0
```

**19:33:56.808** - New MainActivity instance created
```
Amber: onCreate MainActivity
Amber: Setting main activity ref to com.greenart7c3.nostrsigner.MainActivity@2f07d7d
(Different instance)
```

---

## Architecture Analysis

### ContentProvider URI Pattern

Amber uses **operation-specific authorities**, not path-based routing:

```
✓ Correct:  content://com.greenart7c3.nostrsigner.SIGN_EVENT
✗ Incorrect: content://com.greenart7c3.nostrsigner/SIGN_EVENT
```

**Pattern**: `content://<package>.<OPERATION_NAME>`

**Operations observed**:
- `NIP44_ENCRYPT`
- `SIGN_EVENT`

**Expected operations per NIP-55**:
- `GET_PUBLIC_KEY`
- `SIGN_EVENT`
- `NIP04_ENCRYPT`
- `NIP04_DECRYPT`
- `NIP44_ENCRYPT`
- `NIP44_DECRYPT`
- `DECRYPT_ZAP_EVENT`

### AndroidManifest ContentProvider Declaration

Amber must declare multiple authorities in AndroidManifest.xml:

```xml
<provider
    android:name=".ContentProvider"
    android:authorities="com.greenart7c3.nostrsigner.GET_PUBLIC_KEY;
                        com.greenart7c3.nostrsigner.SIGN_EVENT;
                        com.greenart7c3.nostrsigner.NIP04_ENCRYPT;
                        com.greenart7c3.nostrsigner.NIP04_DECRYPT;
                        com.greenart7c3.nostrsigner.NIP44_ENCRYPT;
                        com.greenart7c3.nostrsigner.NIP44_DECRYPT;
                        com.greenart7c3.nostrsigner.DECRYPT_ZAP_EVENT"
    android:exported="true"
    android:grantUriPermissions="false"
    android:multiprocess="false" />
```

### Dual Communication Pattern

Amber uses **both ContentProvider AND Intent-based communication**:

1. **ContentProvider query** - Amethyst queries ContentProvider
2. **Intent launch** - Amber launches MainActivity to show UI/prompt user
3. **ContentProvider response** - Amber returns result via cursor to waiting query

This suggests:
- ContentProvider query **blocks** waiting for response
- MainActivity is launched to handle user interaction
- Result is sent back through ContentProvider cursor

### Multiple MainActivity Instances Issue

**Observation**: Despite `LAUNCH_MULTIPLE`, Android sometimes reuses MainActivity:
- First instance: `MainActivity@1d33b4b` (created, then reused)
- Second instance: `MainActivity@2f07d7d` (created ~7 seconds later)

**Analysis**: The reuse vs new instance behavior appears inconsistent, likely due to:
- Android task management heuristics
- Time delay between requests (immediate reuse, 7s delay = new instance)
- Activity lifecycle state when new Intent arrives

**Implication**: This is normal Android behavior, not a bug. Both Amber and Igloo experience this.

---

## Request/Response Flow

### Request Flow
1. Amethyst calls `contentResolver.query(Uri.parse("content://com.greenart7c3.nostrsigner.SIGN_EVENT"), ...)`
2. Query **blocks** waiting for cursor response
3. Amber ContentProvider receives query
4. Amber launches MainActivity via Intent to show UI
5. User interacts with Amber UI (approves/rejects)
6. Amber constructs cursor with result
7. ContentProvider returns cursor to Amethyst
8. Amethyst's blocked query receives result

### Query Parameters (NIP-55 Spec)

ContentResolver query signature:
```kotlin
query(uri: Uri, projection: Array<String>?, selection: String?,
      selectionArgs: Array<String>?, sortOrder: String?): Cursor?
```

**Parameters passed in `selectionArgs`**:
- `sign_event`: `[eventJson, "", currentUserPubkey]`
- `nip04_encrypt`: `[plaintext, recipientPubkey, currentUserPubkey]`
- `nip44_encrypt`: `[plaintext, recipientPubkey, currentUserPubkey]`
- `get_public_key`: `[currentUserPubkey]` (optional)

### Cursor Response Format

**Success cursor columns**:
```kotlin
// For sign_event
arrayOf("result", "event")  // result = signature, event = full signed event JSON

// For other operations
arrayOf("result")  // result = encrypted/decrypted text or pubkey
```

**Rejection cursor**:
```kotlin
arrayOf("rejected")  // rejected = error message
```

**Null return**: Signer unavailable/timeout

---

## Timing Observations

| Event | Timestamp | Duration | Notes |
|-------|-----------|----------|-------|
| Amethyst launch | 19:33:43 | - | User opens app |
| NIP44_ENCRYPT query | 19:33:49.023 | +6s | First ContentProvider call |
| Amber MainActivity onCreate | 19:33:49.035 | +12ms | UI creation |
| Amber UI displayed | 19:33:49.082 | +47ms | Fast rendering (51ms total) |
| SIGN_EVENT query | 19:33:49.333 | +251ms | Second operation |
| Multiple SIGN_EVENT queries | 19:33:56.646 | +7.3s | Burst of 3 queries in 25ms |
| New MainActivity instance | 19:33:56.808 | +162ms | Due to time gap |

**Key Metrics**:
- **UI launch time**: ~50ms (extremely fast)
- **Query interval**: Immediate, then 7 seconds
- **Burst queries**: 3 queries in 25ms (concurrent signing requests)
- **Instance reuse threshold**: <1 second = reuse, >7 seconds = new instance (approximate)

---

## Comparison: Amber vs Igloo

| Feature | Amber | Igloo (before fix) |
|---------|-------|-------------------|
| **Communication** | ContentProvider | Intent-based only |
| **Authority pattern** | `package.OPERATION` | `package.signing/OPERATION` |
| **Multiple authorities** | Yes (7 authorities) | No (1 authority) |
| **Blocking queries** | Yes | N/A |
| **MainActivity launch** | Via Intent from ContentProvider | Direct Intent |
| **Result delivery** | Cursor (ContentProvider) | Intent result |
| **Amethyst compatibility** | ✓ Works | ✗ Not called |

---

## Critical Implementation Requirements for Igloo

### 1. AndroidManifest.xml Changes

**Before**:
```xml
<provider
    android:name=".IglooContentProvider"
    android:authorities="${applicationId}.signing"
    ...
/>
```

**After**:
```xml
<provider
    android:name=".IglooContentProvider"
    android:authorities="${applicationId}.GET_PUBLIC_KEY;
                        ${applicationId}.SIGN_EVENT;
                        ${applicationId}.NIP04_ENCRYPT;
                        ${applicationId}.NIP04_DECRYPT;
                        ${applicationId}.NIP44_ENCRYPT;
                        ${applicationId}.NIP44_DECRYPT;
                        ${applicationId}.DECRYPT_ZAP_EVENT"
    android:exported="true"
    ...
/>
```

### 2. UriMatcher Changes

**Before**:
```kotlin
uriMatcher.addURI("com.frostr.igloo.signing", "SIGN_EVENT", 2)
```

**After**:
```kotlin
uriMatcher.addURI("com.frostr.igloo.SIGN_EVENT", null, 2)
```

No path component - the operation is in the authority itself.

### 3. Query Blocking Behavior

ContentProvider `query()` must **block** until result is ready:
```kotlin
override fun query(...): Cursor? {
    val latch = CountDownLatch(1)

    // Launch MainActivity for user interaction
    launchMainActivity(...)

    // Block waiting for result (with timeout)
    latch.await(30, TimeUnit.SECONDS)

    // Return cursor with result
    return createResultCursor(resultData)
}
```

### 4. Result Cursor Format

Match Amber's cursor structure:
```kotlin
// Success
val cursor = MatrixCursor(arrayOf("result", "event"))
cursor.addRow(arrayOf(signature, signedEventJson))

// Rejection
val cursor = MatrixCursor(arrayOf("rejected"))
cursor.addRow(arrayOf("Permission denied"))

// Unavailable
return null
```

---

## Security Considerations

### Permission Model

Amber's flow suggests:
1. **First request**: ContentProvider query triggers MainActivity with prompt
2. **User approval**: Stored in permissions database
3. **Subsequent requests**: ContentProvider checks permissions, may skip UI
4. **Rejection**: Returns "rejected" cursor immediately

### Rate Limiting

Observed burst of 3 queries in 25ms suggests:
- Amethyst may retry failed requests
- ContentProvider should handle concurrent queries gracefully
- Rate limiting per calling package recommended

### Process Isolation

- ContentProvider runs in **separate process** from MainActivity
- Requires IPC for communication between ContentProvider and MainActivity
- Igloo already has BroadcastReceiver-based IPC infrastructure

---

## Known Issues & Workarounds

### Multiple MainActivity Instances

**Issue**: Android creates new MainActivity instances inconsistently

**Status**: This is normal Android behavior, not a bug

**Mitigation**:
- Use `singleTask` launch mode where appropriate
- Handle instance reuse in `onNewIntent()`
- Ensure state is maintained across instances

### Query Timeout

**Issue**: If MainActivity doesn't respond, query blocks forever

**Mitigation**:
- Implement 30-second timeout on `CountDownLatch.await()`
- Return `null` on timeout (per NIP-55 spec)
- Clean up receivers/resources

### Concurrent Queries

**Issue**: Multiple simultaneous signing requests from same app

**Mitigation**:
- Use `ConcurrentHashMap` for query tracking
- Assign unique query IDs
- Implement per-query BroadcastReceivers

---

## Testing Recommendations

### Functional Tests
1. ✓ ContentProvider URI pattern matches Amber
2. ✓ Query blocks until result available
3. ✓ Cursor format matches NIP-55 spec
4. ☐ Permission checking works correctly
5. ☐ Rejection returns "rejected" cursor
6. ☐ Timeout returns null after 30s

### Integration Tests with Amethyst
1. ☐ get_public_key returns correct pubkey
2. ☐ sign_event returns properly signed event
3. ☐ nip44_encrypt/decrypt work correctly
4. ☐ Multiple concurrent requests handled
5. ☐ Permission prompts appear correctly
6. ☐ Automatic approval works for saved permissions

### Performance Tests
1. ☐ UI launches in <100ms
2. ☐ Query completes in <5s for approved requests
3. ☐ Handles 10+ concurrent queries
4. ☐ No memory leaks from blocked queries

---

## References

- **NIP-55 Specification**: https://github.com/nostr-protocol/nips/blob/master/55.md
- **Amber Source**: https://github.com/greenart7c3/Amber
- **Android ContentProvider Docs**: https://developer.android.com/guide/topics/providers/content-providers

---

## Appendix: Complete Log Sequence

```
19:33:38.952  ActivityTaskManager: START Amethyst MainActivity
19:33:43.082  ActivityTaskManager: Amethyst displayed

19:33:49.023  Amber: Querying content://com.greenart7c3.nostrsigner.NIP44_ENCRYPT
19:33:49.029  ActivityTaskManager: START Amber MainActivity (LAUNCH_MULTIPLE, result=0)
19:33:49.035  Amber: onCreate MainActivity@1d33b4b
19:33:49.078  Amber: getAccount: npub1vjr49m...
19:33:49.082  ActivityTaskManager: Displayed Amber MainActivity: +51ms

19:33:49.333  Amber: Querying content://com.greenart7c3.nostrsigner.SIGN_EVENT
19:33:49.339  ActivityTaskManager: START Amber MainActivity (LAUNCH_MULTIPLE, result=3)
19:33:49.356  Amber: Setting ref to MainActivity@1d33b4b (REUSED)

19:33:56.646  Amber: Querying SIGN_EVENT
19:33:56.655  Amber: Querying SIGN_EVENT
19:33:56.671  Amber: Querying SIGN_EVENT (3 queries in 25ms)
19:33:56.695  ActivityTaskManager: START Amber MainActivity (LAUNCH_MULTIPLE, result=0)
19:33:56.808  Amber: onCreate MainActivity@2f07d7d (NEW INSTANCE)
```

---

**Report Status**: Complete
**Next Steps**: Test Igloo ContentProvider implementation with Amethyst
