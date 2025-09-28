### Resolution: Use a Temporary Foreground Service to Maintain Process Liveness

The core issue is Android's process priority hierarchy: The `:native_handler` process hosting `InvisibleNIP55Handler` drops to a low-importance state (cached or background) immediately after forwarding the intent to `MainActivity`, as the activity becomes "idle" (no UI focus). This triggers aggressive memory management—freezing via `ActivityManager` (as seen in your logs)—followed by destruction within ~1 second. Broadcasts sent to a destroyed process's unregistered `BroadcastReceiver` are dropped, breaking the reply chain. This is exacerbated by the PWA's 3+ second processing time, creating a timing window where the handler is gone before the result is ready.

Your multi-process design (ephemeral `:native_handler` for NIP-55 isolation) is sound for security and modularity, but ephemeral activities alone are vulnerable to this. The solution is to **temporarily elevate the process priority** using a **foreground service** during the wait period. Foreground services have the highest resistance to killing (they require a visible notification and are treated as user-important), keeping the process alive reliably without violating Android's guidelines. This aligns with best practices for short-lived, interactive background work like NIP-55 (crypto ops with user approval).

#### Why This Works (and Alternatives Considered)
- **Process Priority Boost**: A foreground service places the process in the "foreground" tier, making it ~10x less likely to be killed than a background activity process (per Android's OOM adjustment scores). It survives the 3-second window easily.
- **Minimal Impact**: The service runs only during handling (~5-10s total), with a non-intrusive notification (e.g., "Approving Nostr request").
- **No Architecture Overhaul**: Retains your PendingIntent + Broadcast pattern, multi-process isolation, and `onNewIntent` flow.
- **Battery/UX Trade-offs**: Acceptable for infrequent NIP-55 calls (e.g., logins/signs); users expect brief notifications for security-sensitive ops.
- **Failed Alternatives from Analysis**:
  - **Wake Locks**: Too battery-draining; deprecated for most cases.
  - **Single Process**: Defeats isolation; requires manifest changes and risks PWA crashes affecting NIP-55.
  - **Bound Service in `:native_handler`**: Good for IPC, but services can't directly return results to external activities (no `setResult`). Would need AIDL/Messenger refactoring—overkill here.
  - **Persistent Receiver**: Broadcasts aren't guaranteed delivery cross-process if unregistered; doesn't solve destruction.
  - **PWA Optimization**: Reduces window but doesn't eliminate risk (variable user delays).

#### Implementation Steps
1. **Add Foreground Service Declaration**:
   Update your `AndroidManifest.xml` in the `:native_handler` process section. Add a simple service that doesn't do much beyond keeping the process alive.

   ```xml
   <service
       android:name=".Nip55KeepAliveService"
       android:process=":native_handler"
       android:foregroundServiceType="shortService"  <!-- API 29+: For brief ops; omit if targeting lower -->
       android:exported="false" />
   ```

   - `foregroundServiceType="shortService"` (Android 14+) optimizes for quick tasks like this; fallback to default if needed.
   - No intent-filter needed—started internally.

2. **Implement the Keep-Alive Service**:
   Create `Nip55KeepAliveService.kt` in your project (runs in `:native_handler`).

   ```kotlin
   import android.app.Notification
   import android.app.NotificationChannel
   import android.app.NotificationManager
   import android.app.Service
   import android.content.Intent
   import android.os.IBinder
   import androidx.core.app.NotificationCompat

   class Nip55KeepAliveService : Service() {
       private val CHANNEL_ID = "nip55_handler"
       private val NOTIFICATION_ID = 55

       override fun onCreate() {
           super.onCreate()
           createNotificationChannel()
       }

       override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
           val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
               .setContentTitle("Igloo Nostr Handler")
               .setContentText("Processing secure request...")
               .setSmallIcon(R.drawable.ic_nostr)  // Your app icon
               .setPriority(NotificationCompat.PRIORITY_LOW)  // Non-intrusive
               .setOngoing(true)  // Prevents swipe-dismiss during wait
               .build()

           startForeground(NOTIFICATION_ID, notification)
           // Service now keeps process alive; no heavy work here
           return START_STICKY  // Restart if killed (rare with foreground)
       }

       override fun onBind(intent: Intent?): IBinder? = null  // Not bound

       override fun onDestroy() {
           stopForeground(STOP_FOREGROUND_REMOVE)  // Clean up notification
           super.onDestroy()
       }

       private fun createNotificationChannel() {
           if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
               val channel = NotificationChannel(
                   CHANNEL_ID,
                   "NIP-55 Handler",
                   NotificationManager.IMPORTANCE_LOW
               ).apply { description = "Keeps handler alive for secure ops" }
               getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
           }
       }
   }
   ```

   - Notification is required for foreground (API 26+); customize text/icon.
   - `START_STICKY`: Ensures restart if Android kills it mid-way (unlikely).

3. **Integrate into `InvisibleNIP55Handler`**:
   Start the service immediately in `onCreate` after parsing the NIP-55 intent. Stop it after receiving the broadcast reply.

   ```kotlin
   // In InvisibleNIP55Handler.kt
   import android.content.ComponentName
   import android.content.ServiceConnection
   import android.os.IBinder  // Not used, but for binding if needed later

   class InvisibleNIP55Handler : Activity() {
       // ... existing code for PendingIntent setup ...

       private var keepAliveService: Nip55KeepAliveService? = null
       private var isBound = false

       // Optional: Bind to service for lifecycle sync (not required for start)
       private val connection = object : ServiceConnection {
           override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
               // Service ready; process stays alive
           }
           override fun onServiceDisconnected(name: ComponentName?) {}
       }

       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           // Parse NIP-55 intent (as before)

           // Start foreground service to keep process alive
           val serviceIntent = Intent(this, Nip55KeepAliveService::class.java)
           startService(serviceIntent)
           // Optional bind for tighter control (unbinds on unbindService)
           bindService(serviceIntent, connection, BIND_AUTO_CREATE)
           isBound = true

           // Create/register BroadcastReceiver and PendingIntent (as before)
           // Forward to MainActivity

           // ... rest of setup ...
       }

       // In BroadcastReceiver.onReceive (your reply handler):
       override fun onReceive(context: Context?, intent: Intent?) {
           // ... handle result, setResult for external app ...
           setResult(RESULT_OK, returnIntent)  // Or error
           cleanupAndFinish()
       }

       private fun cleanupAndFinish() {
           unregisterReceiver(replyReceiver)
           // Stop service
           val serviceIntent = Intent(this, Nip55KeepAliveService::class.java)
           if (isBound) {
               unbindService(connection)
               isBound = false
           }
           stopService(serviceIntent)
           finish()  // Terminates activity and empties process
       }

       override fun onDestroy() {
           // Fallback cleanup if broadcast missed
           cleanupAndFinish()
           super.onDestroy()
       }
   }
   ```

   - Starting + optional binding ensures the service outlives the activity if needed.
   - `cleanupAndFinish()` centralizes shutdown: Stops service, unregisters receiver, sets result, and finishes—cleanly terminating `:native_handler`.

4. **Handle Permissions and Edge Cases**:
   - **Notification Permission**: For API 33+, request `POST_NOTIFICATIONS` at app startup (or dynamically). Test with `NotificationManagerCompat.from(context).areNotificationsEnabled()`.
   - **Timeout**: Add a 10s Handler.postDelayed in `onCreate` to call `cleanupAndFinish()` with `RESULT_CANCELED` if no reply (prevents hangs).
   - **Multiple Requests**: Use `launchMode="singleTop"` (already set) + unique PendingIntent actions to serialize.
   - **Testing**:
     - Simulate destruction: Enable "Don't keep activities" in Developer Options.
     - Logs: Add `Log.d("NIP55", "Process ID: ${android.os.Process.myPid()}")` in key spots; monitor with `adb shell dumpsys activity processes | grep native_handler`.
     - Device: Test on low-memory scenarios (`adb shell am kill <pid>`) to verify resilience.
   - **Battery Opt**: In `AndroidManifest.xml`, add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`. Exempt from battery optimization via `PowerManager.isIgnoringBatteryOptimizations()` check.

#### Expected Outcome
- **Timeline Fix**: Handler process stays alive for 10s+; broadcast delivers reliably.
- **Logs**: No more "freezing" or early `onDestroy`; see "Setting RESULT_OK" followed by external app receipt.
- **UX**: Brief notification during approval (dismisses on completion); Amethyst gets pubkey/signature promptly.
- **Compliance**: Follows Android's foreground service rules for "ongoing" ops (user-initiated NIP-55).

This resolves the ephemeral process fragility while preserving your architecture. If foreground notifications are undesirable (e.g., for stealth), consider a full refactor to a bound service in `:main` process using AIDL for IPC—but that's higher effort. Test on your Android 15 device; if issues persist, share updated logs for tweaks.