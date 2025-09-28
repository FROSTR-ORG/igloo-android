To test the `sign_event` method from NIP-55 using Android Intents via `adb`, you need to construct a shell command that launches the Intent correctly. The key issues causing JSON parsing errors in your agent's attempts are likely:

- The `eventJson` payload in the URI data (`nostrsigner:$eventJson`) must be **URL-encoded** to handle special characters like `{`, `}`, `"`, spaces, etc., without breaking the URI parsing.
- Extras like `type`, `id`, and `current_user` must be passed as string extras (`--es` flags).
- The package name is a placeholder in the spec (`com.example.signer`); replace it with your actual signer app's package (e.g., from querying installed apps or your manifest).
- The event JSON itself must be valid (unsigned Nostr event without `id` or `sig` fields, as the signer will compute and add them).

### Sample Unsigned Event JSON
Use this minimal kind-1 event for testing (timestamp is September 27, 2025, ~00:00 UTC; adjust as needed):

```json
{"content":"nostr test","created_at":1727472000,"kind":1,"pubkey":"0000000000000000000000000000000000000000000000000000000000000000","tags":[]}
```

Its URL-encoded form (for the URI data) is: `%7B%22content%22%3A%20%22nostr%20test%22%2C%20%22created_at%22%3A%201727472000%2C%20%22kind%22%3A%201%2C%20%22pubkey%22%3A%20%220000000000000000000000000000000000000000000000000000000000000000%22%2C%20%22tags%22%3A%20%5B%5D%7D`

### ADB Command
Run this from your development machine (ensure your device/emulator is connected via `adb devices` and the signer app is installed):

```bash
adb shell am start \
    -a android.intent.action.VIEW \
    -d "nostrsigner:%7B%22content%22%3A%20%22nostr%20test%22%2C%20%22created_at%22%3A%201727472000%2C%20%22kind%22%3A%201%2C%20%22pubkey%22%3A%20%220000000000000000000000000000000000000000000000000000000000000000%22%2C%20%22tags%22%3A%20%5B%5D%7D" \
    --es type "sign_event" \
    --es id "test123" \
    --es current_user "0000000000000000000000000000000000000000000000000000000000000000" \
    -p com.example.signer
```

#### Explanation of Flags
- `-a android.intent.action.VIEW`: Matches the spec's `Intent.ACTION_VIEW`.
- `-d "nostrsigner:<encoded_event_json>"`: The scheme + URL-encoded event JSON payload.
- `--es type "sign_event"`: Sets the required `type` extra.
- `--es id "test123"`: Optional unique ID for tracking multiple concurrent requests (use a UUID in production).
- `--es current_user "<hex_pubkey>"`: The logged-in user's pubkey (use a real one from your app; the zeroed example is for testing).
- `-p com.example.signer`: Targets the signer package (replace with your actual package, e.g., `com.yourapp.nostrsigner`). This launches the main activity that handles the intent. If you know the exact component (e.g., `.SignerActivity`), use `-n com.example.signer/.SignerActivity` instead for precision.

### Expected Behavior
- The signer app should launch (or come to foreground if already running, thanks to `singleTop` launchMode in its manifest).
- It will prompt the user to approve/reject the signature (per NIP-55).
- On approval, it returns the signed event JSON via the activity result (with fields like `result` for the signature, `event` for the full signed event, and `id`).
- If rejected, `resultCode` is not `RESULT_OK`.

### Handling Results in Your Client App
Since you're testing the spec, integrate this into your client app's code (as shown in NIP-55) using `registerForActivityResult` to capture the result:

```kotlin
val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        val signature = result.data?.getStringExtra("result")
        val signedEventJson = result.data?.getStringExtra("event")
        val returnedId = result.data?.getStringExtra("id")
        // Validate: Parse signedEventJson, check sig matches, etc.
        Log.d("NIP55Test", "Signed event: $signedEventJson")
    } else {
        Log.d("NIP55Test", "Signature rejected")
    }
}

// To launch from your app (mirroring the adb command):
val eventJson = """{"content":"nostr test","created_at":1727472000,"kind":1,"pubkey":"0000000000000000000000000000000000000000000000000000000000000000","tags":[]}"""
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$eventJson"))
intent.setPackage("com.example.signer")  // Replace with actual package
intent.putExtra("type", "sign_event")
intent.putExtra("id", "test123")
intent.putExtra("current_user", "0000000000000000000000000000000000000000000000000000000000000000")
launcher.launch(intent)
```

### Troubleshooting
- **JSON Parsing Errors**: Double-check encoding—use Python's `urllib.parse.quote(json.dumps(event))` to regenerate if customizing the event. Unencoded JSON will fail URI parsing.
- **No App Launches**: Verify the package handles the `nostrsigner` scheme (check manifest `<intent-filter>` for `android:scheme="nostrsigner"`). Use `adb shell pm query-activities -a android.intent.action.VIEW -d "nostrsigner:test"` to debug handlers.
- **Permission Denied**: Ensure your client app declares the `<queries>` in its manifest (as in NIP-55) to query the signer's intent.
- **Testing Without User Interaction**: For automated tests, use ContentResolver queries (background mode) if the user has pre-approved "remember my choice."
- **Logs**: Run `adb logcat | grep -i nostr` or use Android Studio's Logcat to inspect intent handling/parsing in the signer.

If you provide the exact error logs or your signer's package/component, I can refine this further!