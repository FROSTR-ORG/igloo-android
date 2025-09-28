# Android Wrapper Directory (android/)

This directory contains the Android application shell that wraps the Igloo PWA, providing native Android integration, secure storage, polyfill bridges, and NIP-55 intent handling. The wrapper enables the PWA to operate as a full-featured Android app with deep system integration and modern web API support.

## Architecture Overview

### Hybrid Application Design with Secure Polyfills
The Android wrapper implements a secure native shell around the React PWA with modern bridge architecture:

1. **WebView Container**: Chrome-based WebView with secure polyfill injection
2. **Polyfill Bridge System**: Modern API implementations for camera, storage, and WebSocket
3. **Secure Storage Bridge**: Android Keystore integration with encryption
4. **NIP-55 Intent Handling**: Nostr protocol integration for signing requests

### Development vs Production Modes
- **Development Mode**: WebView loads PWA from development server (`http://localhost:3000`)
- **Production Mode**: WebView serves bundled PWA assets with custom protocol (`igloopwa://`)

## Directory Structure

```
android/
├── build.gradle                    # Root build configuration
├── settings.gradle                 # Project settings and modules
├── gradle.properties              # Gradle build properties
├── gradlew                        # Gradle wrapper script (Unix)
├── gradlew.bat                    # Gradle wrapper script (Windows)
├── gradle/wrapper/                # Gradle wrapper JAR and properties
└── app/                          # Main application module
    ├── build.gradle              # App module build configuration
    ├── src/main/
    │   ├── AndroidManifest.xml   # App manifest with permissions and intents
    │   ├── kotlin/com/frostr/igloo/
    │   │   ├── SecureMainActivity.kt     # Main Activity with secure WebView
    │   │   └── bridges/          # Modern bridge implementations
    │   │       ├── ModernCameraBridge.kt    # CameraX API bridge
    │   │       ├── SecureStorageBridge.kt   # Encrypted storage bridge
    │   │       ├── WebSocketBridge.kt       # OkHttp WebSocket bridge
    │   │       └── SecureWebViewClient.kt   # Polyfill injection client
    │   ├── assets/               # PWA assets and polyfills
    │   │   └── polyfills/        # JavaScript polyfill implementations
    │   │       ├── camera-polyfill.js      # MediaDevices API polyfill
    │   │       ├── storage-polyfill.js     # Web Storage API polyfill
    │   │       └── websocket-polyfill.js   # WebSocket API polyfill
    │   └── res/                  # Android resources
    │       ├── values/strings.xml    # String resources
    │       ├── xml/shortcuts.xml     # App shortcuts configuration
    │       ├── xml/backup_rules.xml  # Backup and restore rules
    │       └── mipmap-*/         # App launcher icons
    └── build/                    # Generated build artifacts
```

## Application Configuration

### Build Configuration (`app/build.gradle`)
```gradle
android {
    namespace 'com.frostr.igloo'
    compileSdk 35               # Android 15

    defaultConfig {
        applicationId "com.frostr.igloo"
        minSdk 24               # Android 7.0 (Nougat)
        targetSdk 35            # Android 15
        versionCode 1
        versionName "1.0"
    }
}
```

### Key Dependencies
- `androidx.appcompat:appcompat:1.6.1` - Android support library
- `androidx.constraintlayout:constraintlayout:2.1.4` - Layout management
- `com.squareup.okhttp3:okhttp:4.12.0` - Modern WebSocket client
- `androidx.security:security-crypto:1.1.0-alpha06` - Encrypted storage
- `androidx.camera:camera-*:1.4.0` - Modern CameraX API
- `com.google.mlkit:barcode-scanning:17.3.0` - QR code scanning
- `com.google.code.gson:gson:2.10.1` - JSON serialization

### Gradle Build System
- **Android Gradle Plugin**: 8.1.4
- **Java Compatibility**: VERSION_1_8 (Java 8)
- **Kotlin**: Latest stable version

## Core Components

### SecureMainActivity (`SecureMainActivity.kt`)

The main Activity class that manages the secure PWA container with modern polyfill architecture.

#### Key Features
- **Secure WebView Management**: Modern Chrome WebView with polyfill injection
- **Development Mode**: Automatic switching between development server and production assets
- **NIP-55 Intent Handling**: Processes Nostr signing URL schemes (`nostrsigner:`)
- **Bridge Setup**: Initializes secure JavaScript bridges for PWA ↔ Android communication
- **Polyfill Injection**: Transparent API replacement before PWA JavaScript execution
- **Session Persistence**: Android-specific session management with encrypted storage

#### Modern Bridge Architecture
```kotlin
// Bridge registration in SecureMainActivity
webSocketBridge = WebSocketBridge(webView)
webView.addJavascriptInterface(webSocketBridge, "WebSocketBridge")

storageBridge = SecureStorageBridge(this)
webView.addJavascriptInterface(storageBridge, "SecureStorageBridge")

cameraBridge = ModernCameraBridge(this, webView)
webView.addJavascriptInterface(cameraBridge, "CameraBridge")
```

### ModernCameraBridge (`ModernCameraBridge.kt`)

Modern camera implementation using CameraX API with full MediaDevices compatibility.

#### Features
- **CameraX 1.4.0 API**: Latest Android camera framework
- **Virtual Camera Support**: Emulator compatibility with fallback cameras
- **MediaDevices API**: 100% compatible with `navigator.mediaDevices.getUserMedia()`
- **Camera Enumeration**: Proper device discovery using ProcessCameraProvider
- **Lifecycle Management**: Integration with AppCompatActivity lifecycle
- **Error Handling**: Robust fallback to virtual cameras on initialization failure

#### API Methods
```kotlin
@JavascriptInterface
fun enumerateDevices(): String              // Lists available cameras
@JavascriptInterface
fun getUserMedia(constraintsJson: String): String  // Creates camera stream
@JavascriptInterface
fun stopUserMedia(streamId: String): String        // Stops camera stream
@JavascriptInterface
fun getCapabilities(deviceId: String): String      // Gets camera capabilities
```

### SecureStorageBridge (`SecureStorageBridge.kt`)

Encrypted storage bridge using Android Keystore with Web Storage API compatibility.

#### Features
- **Android Keystore Integration**: Hardware-backed encryption where available
- **AES256-GCM Encryption**: Authenticated encryption with 256-bit keys
- **Web Storage API**: Full compatibility with `localStorage` and `sessionStorage`
- **Quota Management**: 10MB storage limit with proper quota checking
- **Storage Events**: Cross-tab communication event simulation
- **Session Isolation**: Automatic session storage clearing on app restart

#### Security Implementation
- **Encryption**: `AES256_GCM` with `AES256_SIV` key encryption
- **Master Key**: Generated using Android Keystore with hardware protection
- **SharedPreferences Backend**: Encrypted data stored in app preferences
- **Storage Isolation**: Separate local and session storage implementations

#### API Methods
```kotlin
@JavascriptInterface
fun setItem(storageType: String, key: String, value: String): String
@JavascriptInterface
fun getItem(storageType: String, key: String): String?
@JavascriptInterface
fun removeItem(storageType: String, key: String): String
@JavascriptInterface
fun clear(storageType: String): String
@JavascriptInterface
fun length(storageType: String): Int
@JavascriptInterface
fun key(storageType: String, index: Int): String?
```

### WebSocketBridge (`WebSocketBridge.kt`)

Modern WebSocket implementation using OkHttp 4.12.0 with full WebSocket API compatibility.

#### Features
- **OkHttp 4.12.0**: Modern HTTP client with robust WebSocket support
- **Connection Management**: Automatic reconnection and lifecycle management
- **Message Queuing**: Queues messages during connection establishment
- **Protocol Support**: Full WebSocket subprotocol negotiation
- **Error Handling**: Comprehensive error handling with event notifications
- **Connection Pooling**: Efficient resource management for multiple connections

#### API Methods
```kotlin
@JavascriptInterface
fun createWebSocket(url: String, protocols: String = ""): String
@JavascriptInterface
fun sendMessage(connectionId: String, message: String): String
@JavascriptInterface
fun closeWebSocket(connectionId: String, code: Int = 1000, reason: String = ""): String
@JavascriptInterface
fun getConnectionState(connectionId: String): String
```

### SecureWebViewClient (`SecureWebViewClient.kt`)

Custom WebView client that handles polyfill injection and secure asset serving.

#### Features
- **Polyfill Injection**: Loads and injects polyfills before PWA JavaScript execution
- **Custom Protocol Support**: Handles `igloopwa://` protocol for secure asset serving
- **Security Policy**: Blocks external HTTP/HTTPS requests except localhost
- **Asset Management**: Serves PWA assets from Android assets directory
- **MIME Type Detection**: Proper content-type headers for all asset types
- **Development Support**: Allows localhost development server access

#### Polyfill Loading
```kotlin
private fun injectPolyfills(webView: WebView) {
    // Inject polyfills in correct order
    val webSocketPolyfill = loadPolyfillScript("websocket-polyfill.js")
    val storagePolyfill = loadPolyfillScript("storage-polyfill.js")
    val cameraPolyfill = loadPolyfillScript("camera-polyfill.js")

    // Execute polyfills before PWA JavaScript
    webView.evaluateJavascript(webSocketPolyfill) { /* ... */ }
    webView.evaluateJavascript(storagePolyfill) { /* ... */ }
    webView.evaluateJavascript(cameraPolyfill) { /* ... */ }
}
```

## Polyfill System

### JavaScript Polyfills

The polyfill system provides transparent API replacement for modern web APIs:

#### Camera Polyfill (`camera-polyfill.js`)
- **MediaDevices API**: Complete replacement for `navigator.mediaDevices`
- **MediaStream API**: Full MediaStream and MediaStreamTrack implementation
- **Device Enumeration**: Camera device discovery and management
- **Constraints Handling**: Video constraints parsing and validation
- **Event System**: Complete event handling for stream lifecycle

#### Storage Polyfill (`storage-polyfill.js`)
- **Web Storage API**: Complete `localStorage` and `sessionStorage` replacement
- **Storage Events**: Cross-tab communication event simulation
- **Quota Management**: Storage quota checking and enforcement
- **Type Conversion**: Automatic string conversion per Web Storage spec
- **Error Handling**: Proper DOMException throwing for quota exceeded

#### WebSocket Polyfill (`websocket-polyfill.js`)
- **WebSocket API**: Complete WebSocket interface replacement
- **Event System**: Full event handling (open, message, close, error)
- **Binary Data**: Support for ArrayBuffer and Blob message types
- **Protocol Negotiation**: WebSocket subprotocol support
- **Connection Management**: Automatic connection state management

### Polyfill Architecture

```javascript
// Polyfill injection replaces native APIs transparently
Object.defineProperty(navigator, 'mediaDevices', {
    get: function() { return secureMediaDevices; },
    enumerable: true,
    configurable: false
});

// PWA code remains unchanged
navigator.mediaDevices.getUserMedia({ video: true })
    .then(stream => {
        // Stream provided by Android CameraX bridge
    });
```

## Android Manifest Configuration

### Application Settings
- **Package**: `com.frostr.igloo`
- **Theme**: `Theme.AppCompat.Light.NoActionBar` (full-screen PWA)
- **Hardware Acceleration**: Enabled for WebView performance
- **Clear Text Traffic**: Allowed for development server connections
- **Large Heap**: Enabled for better performance

### Permissions
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
```

### Intent Filters

#### NIP-55 URL Scheme Handling
```xml
<!-- Handle nostrsigner: URLs -->
<intent-filter android:priority="1000">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="nostrsigner" />
</intent-filter>

```

## Development Workflow

### Building the Android App

#### Prerequisites
- Android SDK 35 (Android 15)
- Java 8 or higher
- Gradle 8.1+

#### Build Commands
```bash
# Clean build artifacts
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing configuration)
./gradlew assembleRelease

# Install debug APK to connected device
./gradlew installDebug

# Compile Kotlin sources
./gradlew compileDebugKotlin
```

#### Build Outputs
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`

### Development vs Production Setup

#### Development Mode
1. PWA dev server running on port 3000
2. WebView loads from `http://localhost:3000`
3. Hot reload and dev tools available
4. Polyfills injected for secure API access

#### Production Mode
1. PWA built to `dist/` directory
2. Assets served via custom `igloopwa://` protocol
3. Polyfills provide secure API implementations
4. Full security policies enforced

### Debugging

#### Android Logs
```bash
# View secure component logs
adb logcat -s "SecureIglooWrapper:*" "ModernCameraBridge:*" "SecureStorageBridge:*" "WebSocketBridge:*"

# View polyfill injection logs
adb logcat -s "SecureWebViewClient:*"

# Clear logs
adb logcat -c
```

#### WebView Debugging
1. WebView debugging automatically enabled in development
2. Open Chrome DevTools: `chrome://inspect/#devices`
3. Select the Igloo WebView for debugging
4. Console shows polyfill loading and API calls

## Security Considerations

### Modern Security Architecture
- **Polyfill Isolation**: Each API bridge operates independently
- **Encrypted Storage**: All persistent data encrypted with Android Keystore
- **Network Security**: LocalWebServer removed, custom protocol for assets
- **API Security**: Bridges only expose necessary methods with validation

### Android Keystore Integration
- **Hardware Security Module**: Uses TEE or Secure Element when available
- **Key Isolation**: Keys cannot be extracted from Keystore
- **Authenticated Encryption**: AES-GCM provides both confidentiality and integrity
- **Key Lifecycle**: Keys tied to app installation (cleared on uninstall)

### WebView Security
- **Polyfill Injection**: APIs replaced before PWA JavaScript execution
- **Content Security**: Only loads PWA assets via secure protocol
- **JavaScript Sandboxing**: WebView JavaScript cannot access Android filesystem
- **Bridge Interface**: Only exposed methods accessible from JavaScript

## Integration with PWA

### Transparent API Replacement
The PWA code remains completely unchanged while using secure Android implementations:

```typescript
// PWA code works exactly as written for web
navigator.mediaDevices.getUserMedia({ video: true })  // → ModernCameraBridge
localStorage.setItem('key', 'value')                  // → SecureStorageBridge
new WebSocket('wss://example.com')                    // → WebSocketBridge
```

### Bridge Interface Usage
All bridges are accessed transparently through standard web APIs. No special Android code required in PWA.

### NIP-55 Flow (Android → PWA Signing Device)
The Android layer acts as a **secure NIP-55 gateway** that forwards signing requests to the PWA's FROSTR implementation:

1. **External app** sends NIP-55 intent: `nostrsigner:$eventJson` with Intent extras
2. **Android** receives intent, checks app permissions via PermissionStore
3. **Android** builds NIP-55 request object and calls `window.nostr.nip55(request)` via JavaScript
4. **PWA** performs actual cryptographic signing using FROSTR with real private keys
5. **PWA** returns signed result to Android via promise resolution
6. **Android** formats response and returns to calling app via Android intent

**Key Architecture**: PWA is the signing device, Android handles protocol/permissions

#### PWA Interface Required
The PWA must expose this interface for Android to call:
```javascript
window.nostr.nip55 = async (request: NIP55Request) => {
    // Your FROSTR signing implementation
    return { type: request.type, result: signature };
}
```

#### NIP-55 Request Format
Android sends requests matching your PWA's `NIP55Request` type:
```javascript
// sign_event example
{ type: "sign_event", id: "...", host: "com.example.app", event: {...} }
// get_public_key example
{ type: "get_public_key", id: "...", host: "com.example.app" }
// nip04_encrypt example
{ type: "nip04_encrypt", id: "...", host: "com.example.app", plaintext: "...", pubkey: "..." }
```

## Performance Optimization

### Modern API Implementation
- **CameraX**: Latest Android camera framework with optimized performance
- **OkHttp**: Modern HTTP client with connection pooling and efficient WebSocket handling
- **Encrypted Storage**: Minimal encryption overhead with hardware acceleration
- **Polyfill Efficiency**: Direct bridge calls with minimal JavaScript overhead

### Memory Management
- **Lifecycle Awareness**: All bridges properly handle Android lifecycle events
- **Resource Cleanup**: Automatic cleanup of camera sessions, WebSocket connections
- **Garbage Collection**: Efficient object lifecycle management

## Troubleshooting

### Common Issues

#### Camera Not Working
- Verify ModernCameraBridge initialization in logs
- Check camera permissions granted
- Ensure emulator has virtual camera support (API 36+)
- Review CameraX provider initialization logs

#### Storage Failures
- Verify Android Keystore availability
- Check EncryptedSharedPreferences initialization
- Review SecureStorageBridge logs for encryption errors

#### WebSocket Connection Issues
- Check OkHttp WebSocket client initialization
- Verify network connectivity
- Review WebSocketBridge connection state logs

#### Polyfill Injection Problems
- Check SecureWebViewClient polyfill loading logs
- Verify asset files exist in `app/src/main/assets/polyfills/`
- Review JavaScript console for polyfill errors

### Logging and Diagnostics

All components use structured logging with specific tags:
- `SecureIglooWrapper`: Main activity and lifecycle events
- `ModernCameraBridge`: CameraX operations and device enumeration
- `SecureStorageBridge`: Encrypted storage operations
- `WebSocketBridge`: WebSocket connection management
- `SecureWebViewClient`: Polyfill injection and asset serving

This modern Android wrapper provides a secure, performant foundation for running the Igloo PWA with full web API compatibility through transparent polyfill bridges.