## Report: Building a NIP-55 Compatible Signing Device for Nostr on Android

### Introduction
NIP-55 defines an Android-specific protocol for secure, two-way communication between a dedicated signer application (your signing device) and Nostr client apps. This allows clients to offload sensitive operations like event signing or encryption/decryption to your signer without exposing private keys, enhancing security through key isolation. Your signer app acts as an intermediary, handling requests via Android Intents (for user-interactive approval) or Content Resolvers (for background processing if pre-approved).

To ensure compatibility, your app must fully implement the NIP-55 specification, including intent filters, request parsing, operation execution, and result返回. This report outlines the key requirements, drawing from the official NIP-55 spec and existing implementations like Amber. Compatibility means Nostr clients (e.g., Amethyst, Primal) can discover and invoke your signer seamlessly, with users able to grant granular permissions (e.g., per event kind or operation type).

### Overview of NIP-55
NIP-55 enables Nostr clients to request operations from a signer app installed on the same device. Communication uses:
- **Intents**: For foreground requests requiring user confirmation (e.g., via `startActivityForResult`).
- **Content Resolvers**: For background requests if the user has pre-approved the client app.
- **URI Scheme**: `nostrsigner:` (e.g., `nostrsigner:${encodedEventJson}` for sign_event).
- **Discovery**: Clients query for apps handling the `nostrsigner` scheme using PackageManager.

Supported operations focus on key management and cryptography, ensuring the signer never shares private keys. Results are returned via intent extras, content provider queries, or clipboard (for web clients).

### Requirements for Your Signer App
To be NIP-55 compatible, your app must meet Android development standards (e.g., target SDK 33+) and implement the following:

#### 1. AndroidManifest.xml Setup
Declare intent filters to handle incoming requests and allow discovery by clients. This is crucial for compatibility—without it, clients can't invoke your signer.

- **Intent Filter for Requests**:
  ```xml
  <activity android:name=".YourSignerActivity"
            android:exported="true"
            android:launchMode="singleTop">  <!-- Supports FLAG_ACTIVITY_SINGLE_TOP for multiple requests -->
      <intent-filter>
          <action android:name="android.intent.action.VIEW" />
          <category android:name="android.intent.category.DEFAULT" />
          <data android:scheme="nostrsigner" />
      </intent-filter>
  </activity>
  ```

- **Content Provider for Background Handling** (Optional but Recommended for Seamless UX):
  ```xml
  <provider
      android:name=".YourContentProvider"
      android:authorities="${applicationId}.provider"
      android:exported="true"
      android:permission="android.permission.BIND_CONTENT_PROVIDER" />  <!-- Secure with permissions -->
  ```
  This enables automatic signing if users select "remember my choice."

- **Queries for Discovery** (Clients Add This; Your App Doesn't Need It, But Test Compatibility):
  Clients include:
  ```xml
  <queries>
      <intent>
          <action android:name="android.intent.action.VIEW" />
          <data android:scheme="nostrsigner" />
      </intent>
  </queries>
  ```
  Ensure your app is discoverable via `PackageManager.queryIntentActivities()`.

#### 2. Handling Incoming Intents
In your activity (e.g., `onCreate` or `onNewIntent`), parse the intent:
- Extract URI: `intent.data` (e.g., `nostrsigner:${jsonData}`).
- Extract Extras: Use `intent.getStringExtra("type")`, etc.
- Common Parameters:
  | Parameter | Type | Description | Required For |
  |-----------|------|-------------|--------------|
  | `type` | String | Operation type (e.g., `sign_event`) | All requests |
  | `permissions` | JSON Array | Array of permission objects (e.g., `[{"type":"sign_event","kind":1}]`) for granular approval | get_public_key (initial setup) |
  | `id` | String | Unique request ID (e.g., event ID) | sign_event, decrypt operations |
  | `current_user` | String | User's npub (hex pubkey) | Multi-account support |
  | `pubkey` | String | Target pubkey (for encrypt/decrypt) | nip04/44_encrypt/decrypt |
  | `plaintext` / `ciphertext` | String | Data to encrypt/decrypt | Encrypt/decrypt operations |
  | `callbackUrl` | String | URL for web client callbacks (e.g., post results) | Web compatibility |
  | `compressionType` | String (e.g., "gzip") | Compress result data | Optional, for large events |
  | `returnType` | String (e.g., "signature", "event") | What to return | Optional, defaults to full result |

- For web clients: If `callbackUrl` is provided, POST the result there; otherwise, copy to clipboard as a `nostrsigner:${result}` URI.

#### 3. Processing Supported Request Types
Your app must support all core operations. Use Nostr libraries (e.g., nostr-java) for crypto. Prompt users for approval unless pre-granted.

| Operation | Input Params | Logic | Output Extras |
|-----------|--------------|-------|---------------|
| `get_public_key` | permissions, package (client ID) | Generate/retrieve pubkey; store permissions if approved. | "result": pubkey (hex) |
| `sign_event` | id, current_user, event JSON in URI | Verify permissions (e.g., kind matches); sign with secp256k1. | "result": signature, "id": id, "event": signed JSON |
| `nip04_encrypt` | pubkey, plaintext | Encrypt per NIP-04. | "result": ciphertext |
| `nip04_decrypt` | pubkey, ciphertext | Decrypt per NIP-04. | "result": plaintext |
| `nip44_encrypt` | pubkey, plaintext | Encrypt per NIP-44. | "result": ciphertext |
| `nip44_decrypt` | pubkey, ciphertext | Decrypt per NIP-44. | "result": plaintext |
| `decrypt_zap_event` | Event JSON | Decrypt zap-specific data. | "result": decrypted event |

- Multi-Account: Support switching via `current_user`.
- Errors: Set `resultCode = RESULT_CANCELED` on rejection; include error messages in extras.

#### 4. Permissions Management
- Store per-client permissions (e.g., in Room DB) with granular rules (e.g., allow sign_event only for kind=1).
- UI: Show request details (e.g., raw event JSON) and options: Approve, Reject, Remember.
- Background: If "remember" selected, handle via ContentResolver queries (e.g., `content://authority/path?type=sign_event&...`).
- Legacy Fallback: Some clients may use old extra names (e.g., "signature" instead of "result"); support both for broader compatibility.

#### 5. Returning Results
- For Intents: Use `setResult(RESULT_OK, intentWithExtras)`; include "package" (your app's packageName).
- For Content Resolvers: Return Cursor with columns like "result", "id".
- For Web: Compress if requested (e.g., gzip), then POST or clipboard.

### Security Best Practices
- **Key Isolation**: Store private keys in Android Keystore or encrypted storage; never export them.
- **User Prompts**: Always show details before signing; support revocation of permissions.
- **App Permissions**: Request minimal (e.g., no internet if not needed for relays).
- **Auditing**: Log operations without storing sensitive data; support Tor for privacy (as in Amber).
- **Threats**: Prevent MITM by verifying client packages; handle malformed inputs gracefully.

### Testing and Compatibility with Clients
- **Test Setup**: Use adb to simulate intents (e.g., `adb shell am start -a android.intent.action.VIEW -d "nostrsigner:${json}" --es "type" "sign_event"`).
- **Compatibility Checks**:
  - Install clients like Amethyst or Primal; attempt login/signing—your app should appear as an option.
  - Verify with NostrAndroid library examples for client-side invocation.
  - Handle multiple signers: Android shows a chooser if several are installed.
- **Known Compatible Clients**: Amethyst (uses NostrSignerExternal), Primal, 0xchat, Voyage, Pokey—all integrate via NIP-55 for external signing.
- **Edge Cases**: Test rejections, large events, multi-account, web callbacks.
- **Resources**: Fork Amber for reference; use NostrAndroid for testing client integration.

By adhering to these steps, your signer will be fully NIP-55 compatible, interoperable with major Nostr clients, and secure for users. If issues arise, review client repos (e.g., Amethyst on GitHub) for invocation patterns.