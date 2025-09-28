# NIP-55 Content Resolver Integration Report for Igloo App

## Executive Summary

This report summarizes the logic and reasoning behind the proposed integration of NIP-55 Content Resolver functionality into the Igloo App's existing architecture. The Igloo App, a Progressive Web Application (PWA) wrapped in an Android shell, serves as a NIP-55 signing device using the FROSTR protocol for distributed cryptographic operations. The integration maintains the app's core design principles—process isolation, single-instance MainActivity, and WebView-centric PWA—while enabling automated background signing for pre-approved permissions. By placing the ContentProvider in the `:native_handler` process and reusing the intent-forwarding pipeline to MainActivity (in `:main`), we ensure security, handle websocket coordination delays (1-3s+), and achieve synchronous responses without architecture changes. This approach balances NIP-55 compliance with the app's protective dual-process model, avoiding direct exposure of the PWA process to external queries.

## Architecture Overview

### Current Design Principles
The Igloo App employs a dual-process architecture to protect the main application instance from external intents:

- **:native_handler Process**: Ephemeral, handles initial validation and isolation. Contains `InvisibleNIP55Handler` for intent sanitization (removing dangerous flags) and forwarding clean intents to `:main`.
- **:main Process**: Persistent, hosts `MainActivity` (single-instance via `singleInstancePerTask` and `alwaysRetainTaskState`), the PWA WebView, AsyncBridge for native-JS communication, and FROSTR/Bifrost for websocket-based distributed crypto.
- **Intent Handler Pipeline**: A 5-stage protocol (reception, validation, isolation, PWA communication, result propagation) using PendingIntent + BroadcastReceiver for async replies.
- **Permissions**: Stored in shared secure storage (`EncryptedSharedPreferences`), accessible cross-process, with modes like "AUTOMATIC" set via interactive "remember my choice" (no expiry).
- **Cryptographic Operations**: Involve websocket coordination with other signing devices, introducing 1-3s+ delays (potentially longer with offline peers), necessitating robust handling in background flows.

This setup insulates the PWA from malicious intents, ensures single-instance persistence for websocket state, and supports secure, distributed signing.

### Key Constraints
- **Process Preservation**: MainActivity must reuse existing instances via `onNewIntent` to maintain websocket connections.
- **Isolation**: External calls must not directly access `:main` to minimize attack surface.
- **Delays**: Websocket ops require time for peer sync, especially if `:main` is asleep.
- **Sync vs. Async**: Content Resolver requires synchronous `Cursor` responses, while the pipeline is async.

## NIP-55 Content Resolver Requirements

NIP-55 mandates Content Resolvers for automated background operations when users grant persistent permissions ("remember my choice"). Queries use URIs like `content://com.frostr.igloo.signing/SIGN_EVENT`, with args in `projection`/`selectionArgs`, returning a `Cursor` with "result" (and optional "event" for signing). If no permission, return a "rejected" column or `null`. This enables seamless ecosystem integration (e.g., with Amethyst), but must align with the app's security model.

## Integration Logic and Reasoning

### Why Integrate Content Resolver?
- **Compliance and Usability**: NIP-55 requires it for background automation, enabling features like seamless logins or event signing without prompts. Without it, clients receive `null`, breaking compatibility.
- **User Benefit**: Builds on "remember my choice" by allowing automatic ops for trusted apps, improving UX while retaining revocation controls.
- **Ecosystem Fit**: Positions Igloo as a full-featured signer, supporting Nostr clients' expectations.

### Why Place ContentProvider in :native_handler?
- **Maintain Isolation**: Keeps external queries in the ephemeral process, shielding `:main` (PWA/WebView) from direct access. This mirrors `InvisibleNIP55Handler`'s role but bypasses it, as queries lack intent flags—no sanitization needed.
- **Reuse Pipeline**: Forwards queries as intents to `MainActivity` using existing logic (validation, clean flags, PendingIntent reply), leveraging the pipeline's wake-up mechanism for asleep processes.
- **Security Alignment**: Provider replicates validation (reuse `isValidRequest`), checks permissions early (fast DB query), and handles results async-to-sync via latch—avoiding `:main` exposure.
- **Alternative Rejected**: Placing in `:main` would violate isolation, increase crash risk from malformed queries, and complicate WebView readiness without intents.

### Handling Synchronous Responses and Delays
- **Sync/Async Mismatch**: Pipeline is async (broadcast replies), but queries need sync `Cursor`. Use `CountDownLatch` in `query()` to block safely (binder thread, no ANR risk) until reply.
- **Delay Tolerance**: 30s timeout covers 1-3s+ websocket sync (plus wake-up). Timeout returns `null` (per NIP-55: unavailable), allowing clients to fallback to interactive intents. If peers offline, PWA returns error → rejected cursor.
- **Asleep Process**: Intent forwarding wakes `MainActivity` invisibly (your manifest ensures reuse, no restart); existing readiness wait handles websocket spin-up.

### Permission Logic
- **Pre-Approved Only**: Queries check `AUTOMATIC` mode in secure storage before forwarding—early rejection if denied, minimizing unnecessary wakes.
- **No Expiry**: Simplifies DB (no `expires` check), aligning with your design.
- **Revocation**: Assumed handled in existing PWA settings (update mode to "DENIED").

### Risks and Mitigations
- **Attack Surface**: Queries add IPC entry; mitigated by validation/sanitization in provider, rate limiting per package.
- **Performance**: Delays could timeout; mitigated by 30s latch, logging for analysis, optional caching.
- **Compliance**: Ensures NIP-55 cursor formats (e.g., "result", "event", "rejected").
- **Complexity**: Reuses pipeline, minimizing new code.

This approach preserves the app's protective architecture while achieving full NIP-55 support, ensuring secure, efficient background signing.

### Implementation Plan

**Duration**: ~3.5-4.5 weeks, assuming familiarity with your codebase (Kotlin, React 19, FROSTR/Bifrost, Android SDK 35, Gradle 8.1.4).  
**Environment**: Dual-process setup (`:native_handler` for isolation, `:main` for PWA/WebView), `EncryptedSharedPreferences` for permissions, FROSTR websocket ops (1-3s+ delays).  
**Goals**:
- Add `IglooContentProvider` in `:native_handler` for NIP-55 background ops (`GET_PUBLIC_KEY`, `SIGN_EVENT`, etc.).
- Forward queries via intent pipeline (`ContentProvider` → `MainActivity` → AsyncBridge → PWA), bypassing `InvisibleNIP55Handler` (no flag sanitization needed).
- Handle 1-3s+ websocket delays with 30s `CountDownLatch` timeout.
- Use existing secure storage for `AUTOMATIC` permissions (no expiry).
- Maintain process isolation, single-instance `MainActivity`.

---

### Phase 1: Foundation (0.5-1 Week)
**Focus**: Set up `ContentProvider` skeleton, verify secure storage DB access for permissions.

1. **Update AndroidManifest.xml**:
   - Add `IglooContentProvider` in `:native_handler` for NIP-55 URIs.
   ```xml
   <provider
       android:name=".IglooContentProvider"
       android:authorities="com.frostr.igloo.signing"
       android:process=":native_handler"
       android:exported="true"
       android:multiprocess="false" />
   ```

2. **Verify Secure Storage DB**:
   - Confirm `EncryptedSharedPreferences` stores permissions as `{package, type, mode, value}` (mode: "PROMPT", "AUTOMATIC", "DENIED", no expiry).
   - Update or implement `PermissionDbHelper.kt` for access.
   ```kotlin
   // PermissionDbHelper.kt
   import androidx.security.crypto.EncryptedSharedPreferences
   import androidx.security.crypto.MasterKeys

   class PermissionDbHelper(context: Context) {
       companion object {
           private const val PREFS_NAME = "nip55_permissions"
           fun getInstance(context: Context): PermissionDbHelper = PermissionDbHelper(context.applicationContext)
       }

       private val sharedPrefs = EncryptedSharedPreferences.create(
           PREFS_NAME,
           MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
           context,
           EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
           EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
       )

       fun hasAutomaticPermission(callingPackage: String, type: String): Boolean {
           val key = "$callingPackage:$type"
           return sharedPrefs.getString(key, null)?.split("|")?.get(0) == "AUTOMATIC"
       }

       fun insertPermission(packageName: String, type: String, mode: String, value: String) {
           val key = "$packageName:$type"
           sharedPrefs.edit().putString(key, "$mode|$value").apply()
       }
   }
   ```
   - Assume `insertPermission` called from `MainActivity` (via AsyncBridge) when interactive flow (`InvisibleNIP55Handler`) sets "Always allow" (`AUTOMATIC`).

3. **Testing**:
   - Unit test `PermissionDbHelper`: Insert/query from `:native_handler` and `:main` contexts.
   - Manual: Trigger intent (`adb shell am start -a android.intent.action.VIEW -d "nostrsigner:"`), confirm `AUTOMATIC` stored via PWA toggle.
   - Verify encryption: Test invalid key access fails.

---

### Phase 2: Pipeline Hookup (1.5-2 Weeks)
**Focus**: Implement `IglooContentProvider`, convert queries to intents, forward via pipeline, block with 30s timeout.

1. **Implement IglooContentProvider.kt (in :native_handler)**:
   - Parse URI/args, validate (reuse `InvisibleNIP55Handler` logic), check perms, forward intent, block for reply.
   ```kotlin
   // IglooContentProvider.kt
   import android.content.ContentProvider
   import android.database.Cursor
   import android.database.MatrixCursor
   import android.net.Uri
   import android.os.Bundle
   import android.util.Log
   import java.util.UUID
   import java.util.concurrent.CountDownLatch
   import java.util.concurrent.TimeUnit

   class IglooContentProvider : ContentProvider() {
       private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
           addURI("com.frostr.igloo.signing", "GET_PUBLIC_KEY", 1)
           addURI("com.frostr.igloo.signing", "SIGN_EVENT", 2)
           addURI("com.frostr.igloo.signing", "NIP04_ENCRYPT", 3)
           addURI("com.frostr.igloo.signing", "NIP04_DECRYPT", 4)
           addURI("com.frostr.igloo.signing", "NIP44_ENCRYPT", 5)
           addURI("com.frostr.igloo.signing", "NIP44_DECRYPT", 6)
           addURI("com.frostr.igloo.signing", "DECRYPT_ZAP_EVENT", 7)
       }

       private val resultLatch = CountDownLatch(1)
       private var resultData: Bundle? = null
       private var error: String? = null

       override fun onCreate() = true

       override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
           val match = uriMatcher.match(uri)
           if (match == UriMatcher.NO_MATCH) {
               Log.w("NIP55Provider", "Invalid URI: $uri")
               return null
           }

           val operationType = when (match) {
               1 -> "get_public_key"
               2 -> "sign_event"
               3 -> "nip04_encrypt"
               4 -> "nip04_decrypt"
               5 -> "nip44_encrypt"
               6 -> "nip44_decrypt"
               7 -> "decrypt_zap_event"
               else -> return null
           }

           val callingPackage = callingPackage ?: {
               Log.w("NIP55Provider", "No calling package")
               return null
           }()

           // Reuse InvisibleNIP55Handler validation
           if (!isValidRequest(operationType, projection, selectionArgs)) {
               Log.w("NIP55Provider", "Invalid request: $operationType")
               return null
           }

           // Check perms (fast)
           val dbHelper = PermissionDbHelper(context!!)
           if (!dbHelper.hasAutomaticPermission(callingPackage, operationType)) {
               Log.i("NIP55Provider", "No AUTOMATIC perm for $callingPackage:$operationType")
               return createRejectedCursor()
           }

           // Convert to NIP-55 intent extras
           val requestExtras = Bundle().apply {
               putString("type", operationType)
               when (operationType) {
                   "sign_event" -> {
                       putString("event", selectionArgs?.getOrNull(0) ?: "")
                       putString("current_user", selectionArgs?.getOrNull(2) ?: "")
                       putString("id", UUID.randomUUID().toString())
                   }
                   "nip04_encrypt", "nip44_encrypt", "nip04_decrypt", "nip44_decrypt" -> {
                       putString("content", selectionArgs?.getOrNull(0) ?: "")
                       putString("pubkey", selectionArgs?.getOrNull(1) ?: "")
                       putString("current_user", selectionArgs?.getOrNull(2) ?: "")
                       putString("id", UUID.randomUUID().toString())
                   }
                   "decrypt_zap_event" -> {
                       putString("event", selectionArgs?.getOrNull(0) ?: "")
                       putString("current_user", selectionArgs?.getOrNull(2) ?: "")
                       putString("id", UUID.randomUUID().toString())
                   }
                   "get_public_key" -> {}
               }
           }

           forwardToMainProcess(requestExtras)

           // Block for websocket (1-3s+, up to 30s)
           if (!resultLatch.await(30, TimeUnit.SECONDS)) {
               Log.w("NIP55Provider", "Timeout on $operationType for $callingPackage")
               return null // Per NIP-55: unavailable
           }

           if (error != null) return createRejectedCursor(error)

           return createResultCursor(resultData, operationType)
       }

       private fun forwardToMainProcess(extras: Bundle) {
           val uniqueAction = "com.frostr.igloo.NIP55_REPLY_" + UUID.randomUUID().toString()
           val replyIntent = Intent(uniqueAction).setPackage(context?.packageName)
           val replyPendingIntent = PendingIntent.getBroadcast(context, 0, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

           val receiver = object : BroadcastReceiver() {
               override fun onReceive(ctx: Context?, intent: Intent?) {
                   resultData = intent?.extras
                   if (resultData == null) error = "Processing failed"
                   ctx?.unregisterReceiver(this)
                   resultLatch.countDown()
               }
           }
           context?.registerReceiver(receiver, IntentFilter(uniqueAction))

           val mainIntent = Intent(context, MainActivity::class.java).apply {
               flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
               putExtras(extras)
               putExtra("reply_pending_intent", replyPendingIntent)
           }
           context?.startActivity(mainIntent) // Wakes :main if asleep
       }

       private fun createResultCursor(data: Bundle?, type: String): Cursor {
           val columns = if (type == "sign_event") arrayOf("result", "event") else arrayOf("result")
           val cursor = MatrixCursor(columns)
           if (data != null) {
               val row = when (type) {
                   "sign_event" -> arrayOf(data.getString("signature"), data.getString("event"))
                   "decrypt_zap_event", "nip04_decrypt", "nip44_decrypt" -> arrayOf(data.getString("result"))
                   else -> arrayOf(data.getString("result"))
               }
               cursor.addRow(row)
           }
           return cursor
       }

       private fun createRejectedCursor(msg: String? = "Permission denied"): Cursor {
           val cursor = MatrixCursor(arrayOf("rejected"))
           cursor.addRow(arrayOf(msg))
           return cursor
       }

       override fun insert(uri: Uri, values: ContentValues?) = null
       override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
       override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
       override fun getType(uri: Uri) = null
   }
   ```

2. **Verify MainActivity.kt (in :main)**:
   - Ensure `onNewIntent` processes intents, delegates to AsyncBridge, replies via `PendingIntent` (likely unchanged).
   ```kotlin
   // In MainActivity.kt
   override fun onNewIntent(intent: Intent?) {
       super.onNewIntent(intent)
       setIntent(intent)
       if (intent?.hasExtra("type") == true) {
           val startTime = System.currentTimeMillis()
           waitForPwaReady { // Your readiness check (1-3s websocket spin-up)
               asyncBridge.processNip55Request(intent) { resultBundle, success ->
                   val replyIntent = Intent().apply { putExtras(resultBundle) } // e.g., putString("result", sig)
                   val pending = intent.getParcelableExtra<PendingIntent>("reply_pending_intent")
                   try {
                       pending?.send(this, if (success) RESULT_OK else RESULT_CANCELED, replyIntent)
                   } catch (e: PendingIntent.CanceledException) {
                       Log.w("NIP55", "PendingIntent canceled: $e")
                   }
                   Log.d("NIP55", "Coord time: ${System.currentTimeMillis() - startTime}ms")
               }
           }
       }
   }
   ```

3. **AsyncBridge/PWA Verification**:
   - Confirm `asyncBridge.processNip55Request` passes extras to `window.nostr.nip55` unchanged.
   - Verify PWA’s `nip55-bridge.tsx` queries secure storage (via AsyncBridge) and skips prompts for `AUTOMATIC` perms, directly invoking FROSTR websocket coordination (assumed implemented).
   ```typescript
   // src/components/nip55-bridge.tsx (no change needed if correct)
   window.nostr.nip55 = async (request: NIP55Request) => {
       const perm = await checkPermission(request.packageName, request.type); // Queries EncryptedSharedPreferences
       if (perm.mode === "AUTOMATIC") {
           return await executeSigningOperation(request); // FROSTR websocket
       }
       return await promptUserAndExecute(request);
   };
   ```

4. **Testing**:
   - Unit test `IglooContentProvider`: Mock DB, validate URI/args, test 30s timeout.
   - Instrumented: `adb shell content query --uri content://com.frostr.igloo.signing/SIGN_EVENT --projection '{"content":"test","created_at":1727472000,"kind":1,"pubkey":"...","tags":[]}' '' 'pubkey'`.
   - Asleep :main: `adb shell am force-stop com.frostr.igloo`, query, verify wake-to-reply (~1-3s).

---

### Phase 3: Security & Testing (1 Week)
**Focus**: Harden provider, test delays/offline peers, ensure NIP-55 compliance.

1. **Security**:
   - **Validation**: Reuse `InvisibleNIP55Handler`’s `isValidRequest` (check URI, JSON.parse args, required fields).
   - **Rate Limiting**: Track per `callingPackage` in secure storage (`sharedPrefs.edit().putInt("rate:$callingPackage", count).apply()`; max 5/min).
   - **Logging**: Log queries (`Log.i("NIP55Provider", "Query: $operationType from $callingPackage, time: ${System.currentTimeMillis()}")`).
   - **Sanitization**: Validate JSON in `selectionArgs` (e.g., `try { JSONObject(selectionArgs[0]) } catch (e: JSONException) { return null }`).

2. **Testing**:
   - **Delays**: Mock websocket delays (3-20s in `executeSigningOperation`); verify cursor or timeout.
   - **Asleep Process**: Force-stop :main, query via adb, check wake-up.
   - **Offline Peers**: Mock FROSTR failure (error → rejected cursor).
   - **Concurrency**: Multiple queries; confirm `singleTop` serializes.
   - **Compliance**: Test with NIP-55 client snippets (e.g., Amethyst-like `contentResolver.query`).

3. **Tools**:
   - Espresso for instrumented tests.
   - Logcat: Filter `NIP55`, `NIP55Provider`.
   - Mockk for DB/AsyncBridge mocks.

---

### Phase 4: Rollout & Monitoring (0.5-1 Week)
**Focus**: Deploy incrementally, monitor delays, gather feedback.

1. **Rollout**:
   - Beta: Enable `GET_PUBLIC_KEY` first (no args, fastest).
   - Add `SIGN_EVENT`, then encrypt/decrypt ops.
   - Confirm PWA settings show active `AUTOMATIC` permissions.

2. **Monitoring**:
   - Crashlytics: Log `NIP55_Coord_Time_ms`, `NIP55_Timeout_Count`.
   - Track rejections (e.g., "no perm", "offline peers").
   - Feedback: If timeouts frequent, PWA dialog ("Devices offline—retry interactively?").

3. **Optimizations**:
   - If >10% timeouts, cache FROSTR results in secure storage (keyed by input hash).
   - If ANR reported (unlikely on binder thread), reduce timeout to 20s, notify via PWA.

---

### Edge Cases & Mitigations
- **Long Delays (>30s)**: FROSTR fails fast on offline peers (error → rejected cursor). Log for analysis.
- **Main Asleep**: Intent wakes single instance (`singleInstancePerTask`). Readiness wait handles websocket.
- **Concurrency**: `singleTop` serializes; UUID isolates broadcasts.
- **Security**: Validate `callingPackage` (non-spoofable); sanitize inputs; rate limit.
- **DB Access**: `EncryptedSharedPreferences` reliable cross-process (app UID).

---

### Assumptions
- `InvisibleNIP55Handler` has reusable `isValidRequest` for validation.
- PWA’s `window.nostr.nip55` skips prompts for `AUTOMATIC` perms (queries secure storage).
- Secure storage (`EncryptedSharedPreferences`) accessible from both processes.
- Websocket delays average 1-3s, can spike (30s timeout covers most).

Start with Phase 1 (manifest, DB check), prototype `GET_PUBLIC_KEY`. Log timeouts or storage issues for quick fixes. This integrates directly into `:native_handler`, bypassing `InvisibleNIP55Handler`, and reuses your pipeline’s intent forwarding for a seamless, secure hookup.