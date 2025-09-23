# Android Wrapper Directory (android/)

This directory contains the Android application shell that wraps the Igloo PWA, providing native Android integration, secure storage, and NIP-55 intent handling. The wrapper enables the PWA to operate as a full-featured Android app with deep system integration.

## Architecture Overview

### Hybrid Application Design
The Android wrapper implements a lightweight native shell around the React PWA with four key components:

1. **WebView Container**: Chrome-based WebView for rendering the PWA
2. **Local Web Server**: Serves PWA assets from Android assets directory
3. **Secure Storage Bridge**: Android Keystore integration for cryptographic keys
4. **JavaScript Bridge**: Bidirectional communication between PWA and Android

### Development vs Production Modes
- **Development Mode**: WebView loads PWA from development server (`http://10.0.2.2:3000`)
- **Production Mode**: Local web server serves bundled PWA assets from `app/src/main/assets/www/`

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
    │   ├── java/com/frostr/igloo/
    │   │   ├── MainActivity.java     # Main Activity with WebView management
    │   │   ├── SecureStorage.java    # Android Keystore secure storage
    │   │   ├── JavaScriptBridge.java # PWA ↔ Android communication
    │   │   ├── LocalWebServer.java   # HTTP server for PWA assets
    │   │   └── NIP55Bridge.java      # NIP-55 intent result handling
    │   ├── res/                  # Android resources
    │   │   ├── values/strings.xml    # String resources
    │   │   ├── xml/shortcuts.xml     # App shortcuts configuration
    │   │   ├── xml/backup_rules.xml  # Backup and restore rules
    │   │   ├── xml/data_extraction_rules.xml # Data extraction rules
    │   │   └── mipmap-*/         # App launcher icons
    │   └── assets/www/           # PWA build output (copied from ../../dist/)
    └── build/                    # Generated build artifacts
```

## Application Configuration

### Build Configuration (`app/build.gradle`)
```gradle
android {
    namespace 'com.frostr.igloo'
    compileSdk 34

    defaultConfig {
        applicationId "com.frostr.igloo"
        minSdk 24          // Android 7.0 (Nougat)
        targetSdk 34       // Android 14
        versionCode 1
        versionName "1.0"
    }
}
```

### Key Dependencies
- `androidx.appcompat:appcompat:1.6.1` - Android support library
- `androidx.constraintlayout:constraintlayout:2.1.4` - Layout management

### Gradle Build System
- **Android Gradle Plugin**: 8.7.0
- **Java Compatibility**: VERSION_1_8 (Java 8)
- **Repository Sources**: Google Maven, Maven Central

## Core Components

### MainActivity (`MainActivity.java`)

The main Activity class that manages the entire Android application lifecycle.

#### Key Features
- **WebView Management**: Configures and manages Chrome WebView with full JavaScript support
- **Development Mode**: Automatic switching between development server and production assets
- **Intent Handling**: Processes NIP-55 URL schemes (`nostrsigner:`, `web+nostrsigner:`)
- **Bridge Setup**: Initializes JavaScript bridges for PWA ↔ Android communication
- **Lifecycle Management**: Handles pause/resume with connection recovery
- **Session Persistence**: Android-specific session management

#### WebView Configuration
```java
webSettings.setJavaScriptEnabled(true);
webSettings.setDomStorageEnabled(true);
webSettings.setDatabaseEnabled(true);
webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
```

#### Intent Processing
- Extracts NIP-55 data from Android intents
- Passes intent data to PWA via URL parameters
- Handles both direct scheme links and browser redirects

### SecureStorage (`SecureStorage.java`)

Provides cryptographically secure storage using Android Keystore.

#### Features
- **Android Keystore Integration**: Hardware-backed key storage where available
- **AES-GCM Encryption**: 256-bit encryption with authenticated encryption
- **Automatic Key Generation**: Creates encryption keys on first use
- **SharedPreferences Backend**: Encrypted data stored in app preferences
- **Key Management**: Secure key lifecycle management

#### Security Implementation
- **Encryption**: `AES/GCM/NoPadding` with 96-bit IV and 128-bit authentication tag
- **Key Protection**: Keys stored in Android Keystore (hardware security module when available)
- **No User Authentication Required**: Keys accessible to app without user PIN/biometric
- **Automatic IV Generation**: Unique IV for each encryption operation

#### API Methods
```java
boolean storeSecret(String key, String value)    // Store encrypted value
String retrieveSecret(String key)                // Retrieve and decrypt value
boolean hasSecret(String key)                    // Check if key exists
boolean deleteSecret(String key)                 // Remove stored value
void clearAll()                                  // Clear all stored values
```

### JavaScriptBridge (`JavaScriptBridge.java`)

Bidirectional communication bridge between PWA JavaScript and Android native code.

#### JavaScript Interface Methods
All methods are annotated with `@JavascriptInterface` for WebView access:

```javascript
// Available in PWA as window.AndroidSecureStorage
window.AndroidSecureStorage.storeSecret(key, value)
window.AndroidSecureStorage.getSecret(key)
window.AndroidSecureStorage.hasSecret(key)
window.AndroidSecureStorage.deleteSecret(key)
window.AndroidSecureStorage.clearAllSecrets()
window.AndroidSecureStorage.getDeviceInfo()
window.AndroidSecureStorage.log(message)
```

#### Integration
- **Secure Storage Access**: Direct interface to SecureStorage operations
- **Logging Bridge**: PWA logs routed to Android system logs
- **Device Information**: Android environment detection
- **Thread Safety**: All operations safe for WebView JavaScript thread

### LocalWebServer (`LocalWebServer.java`)

HTTP server for serving PWA assets in production mode.

#### Features
- **Localhost Only**: Binds to `127.0.0.1` for security
- **Port 8090**: Default serving port
- **Asset Serving**: Serves files from `app/src/main/assets/www/`
- **MIME Type Detection**: Automatic content-type headers
- **CORS Headers**: Cross-origin support for PWA features
- **404 Handling**: Proper error responses for missing files

#### Security Considerations
- **Loopback Interface Only**: Not accessible from network
- **Asset Directory Restriction**: Only serves files from designated assets
- **No Directory Traversal**: Path validation prevents access outside assets

#### Supported File Types
- HTML, CSS, JavaScript, JSON
- PNG, JPEG, ICO images
- Source maps for debugging

### NIP55Bridge (`NIP55Bridge.java`)

Handles NIP-55 signing request results and intent responses.

#### Features
- **Result Handling**: Processes PWA signing operation results
- **Intent Data Packaging**: Formats results for calling applications
- **Request ID Tracking**: Maintains correlation between requests and responses
- **Activity Result Management**: Proper Android activity result handling

#### JavaScript Interface
```javascript
// Called from PWA to approve signing requests
window.nip55Bridge.approveRequest(result, id, event)
window.nip55Bridge.denyRequest(reason, id)
```

#### Result Intent Structure
```java
resultIntent.putExtra("result", signatureOrResult);
resultIntent.putExtra("package", "com.frostr.igloo");
resultIntent.putExtra("id", requestId);
resultIntent.putExtra("event", signedEvent);  // For sign_event requests
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
```

### Intent Filters

#### Launcher Intent
```xml
<intent-filter android:priority="1000">
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
</intent-filter>
```

#### NIP-55 URL Scheme Handling
```xml
<!-- Handle nostrsigner: URLs -->
<intent-filter android:priority="1000">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="nostrsigner" />
</intent-filter>

<!-- Handle web+nostrsigner: URLs -->
<intent-filter android:priority="1000">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="web+nostrsigner" />
</intent-filter>
```

#### Activity Properties
- **Launch Mode**: `singleInstance` - Only one instance of activity
- **Always Retain Task State**: Preserves PWA state during system memory pressure
- **Exclude From Recents**: `false` - Appears in recent apps list

### App Shortcuts
Configures long-press app shortcuts:
- **Update App**: Manual update checking functionality

## Development Workflow

### Building the Android App

#### Prerequisites
- Android SDK 34 (Android 14)
- Java 8 or higher
- Gradle 8.7+

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
```

#### Build Outputs
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`

### Development vs Production Setup

#### Development Mode
1. Set `DEVELOPMENT_MODE = true` in MainActivity
2. Start PWA dev server: `npm run dev` (from project root)
3. WebView loads from `http://10.0.2.2:3000` (Android emulator host mapping)
4. Hot reload and dev tools available

#### Production Mode
1. Set `DEVELOPMENT_MODE = false` in MainActivity
2. Build PWA: `npm run build` (from project root)
3. Copy `dist/` contents to `app/src/main/assets/www/`
4. LocalWebServer serves PWA from assets

### Asset Management

#### Automatic Asset Copying
The build process should copy PWA build output to Android assets:
```bash
# Copy PWA build to Android assets
cp -r ../../dist/* app/src/main/assets/www/
```

#### Asset Structure
```
app/src/main/assets/www/
├── index.html
├── app.js
├── app.js.map
├── sw.js
├── sw.js.map
├── manifest.json
├── favicon.ico
├── icons/logo.png
└── styles/
    ├── global.css
    ├── layout.css
    ├── console.css
    ├── node.css
    ├── settings.css
    ├── prompt.css
    ├── scanner.css
    └── sessions.css
```

### Debugging

#### Android Logs
```bash
# View all app logs
adb logcat -s IglooWrapper:* PWA-*:*

# View JavaScript bridge logs
adb logcat -s JavaScriptBridge:*

# View secure storage logs
adb logcat -s SecureStorage:*

# Clear logs
adb logcat -c
```

#### WebView Debugging
1. Enable WebView debugging in MainActivity:
   ```java
   WebView.setWebContentsDebuggingEnabled(true);
   ```
2. Open Chrome DevTools: `chrome://inspect/#devices`
3. Select the Igloo WebView for debugging

#### NIP-55 Intent Testing
Use the provided test script from project root:
```bash
./test-nip55-intents.sh
```

## Security Considerations

### Android Keystore
- **Hardware Security Module**: Uses TEE or Secure Element when available
- **Key Isolation**: Keys cannot be extracted from Keystore
- **Authenticated Encryption**: AES-GCM provides both confidentiality and integrity
- **Key Lifecycle**: Keys tied to app installation (cleared on uninstall)

### Network Security
- **Localhost Binding**: LocalWebServer only accessible from app
- **HTTPS Development**: Development server uses HTTP (localhost exception)
- **Clear Text Traffic**: Limited to development mode only

### WebView Security
- **Content Security**: Only loads PWA assets or development server
- **JavaScript Sandboxing**: WebView JavaScript cannot access Android filesystem
- **Bridge Interface**: Only exposed methods accessible from JavaScript

### Intent Security
- **Scheme Validation**: Only processes valid NIP-55 URL schemes
- **Package Verification**: Results include originating package name
- **Intent Extras Validation**: Input validation on all intent data

## Integration with PWA

### Bridge Interface Usage
The PWA accesses Android functionality through global objects:

```typescript
// TypeScript definitions (from src/types.ts)
interface AndroidSecureStorage {
  storeSecret(key: string, value: string): boolean
  getSecret(key: string): string | null
  hasSecret(key: string): boolean
  deleteSecret(key: string): boolean
  clearAllSecrets(): void
  getDeviceInfo(): string
  log(message: string): void
}

declare global {
  interface Window {
    AndroidSecureStorage?: AndroidSecureStorage
    nip55Bridge?: {
      approveRequest(result: string, id: string, event: string): void
      denyRequest(reason: string, id: string): void
    }
  }
}
```

### NIP-55 Flow
1. External app sends NIP-55 intent to Android
2. MainActivity extracts intent data and passes to PWA
3. PWA processes request and shows user prompt
4. User approves/denies in PWA interface
5. PWA calls `nip55Bridge.approveRequest()` or `nip55Bridge.denyRequest()`
6. NIP55Bridge packages result and returns to calling app

### Session Persistence
- PWA state persisted in WebView
- Secure data stored via AndroidSecureStorage bridge
- Activity lifecycle properly handled for background/foreground transitions

## Troubleshooting

### Common Issues

#### WebView Not Loading PWA
- Check DEVELOPMENT_MODE flag in MainActivity
- Verify development server is running on port 3000
- Check Android emulator network connectivity
- Review WebView console logs in Chrome DevTools

#### Secure Storage Failures
- Verify Android Keystore availability
- Check device security settings (lock screen required for some features)
- Review SecureStorage logs for specific error details

#### NIP-55 Intents Not Working
- Verify intent filters in AndroidManifest.xml
- Check NIP-55 URL format in calling application
- Review MainActivity intent handling logs
- Test with provided intent test script

#### Build Failures
- Ensure Android SDK 34 is installed
- Verify Java 8 compatibility
- Check Gradle wrapper permissions (`chmod +x gradlew`)
- Clear build cache: `./gradlew clean`

### Logging and Diagnostics

All components use Android Log with specific tags:
- `IglooWrapper`: MainActivity and general app lifecycle
- `SecureStorage`: Cryptographic storage operations
- `JavaScriptBridge`: PWA ↔ Android communication
- `LocalWebServer`: Asset serving and HTTP operations
- `NIP55Bridge`: NIP-55 intent processing

### Performance Optimization

#### WebView Performance
- Hardware acceleration enabled
- Large heap available for complex PWA operations
- Efficient JavaScript bridge with minimal data serialization

#### Storage Performance
- Secure operations cached where appropriate
- Minimal encryption overhead with AES-GCM
- SharedPreferences backend optimized for small data

#### Network Performance
- Local asset serving eliminates network latency
- Development mode supports hot reload for faster iteration
- Efficient asset compression in production builds

## Extension and Customization

### Adding New Bridge Methods
1. Add method to appropriate bridge class with `@JavascriptInterface`
2. Update TypeScript definitions in PWA `src/types.ts`
3. Test bridge method from PWA JavaScript console

### Modifying Security Settings
- Key generation parameters in SecureStorage constructor
- WebView security settings in MainActivity WebView configuration
- Intent filter priorities and schemes in AndroidManifest.xml

### Build Customization
- Gradle build configuration in `app/build.gradle`
- ProGuard rules for release builds
- Custom asset processing and copying automation

This Android wrapper provides a robust foundation for running the Igloo PWA as a native Android application with full NIP-55 support and secure storage capabilities.