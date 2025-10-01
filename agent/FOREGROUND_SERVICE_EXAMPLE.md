# Implementing a Temporary Foreground Service for Background NIP-55 Signing in Igloo App

## Executive Summary

This report outlines the implementation of a temporary foreground service to enable pre-approved NIP-55 signing requests (e.g., intents or Content Resolver queries with "AUTOMATIC" permissions) to process in the background without bringing the `MainActivity` (hosting the PWA/WebView) to the foreground. The service elevates the `:main` process priority to handle 1-3s+ websocket coordination delays for FROSTR operations, even if the app is asleep or killed. It starts from `:native_handler` (via `InvisibleNIP55Handler` for intents or `IglooContentProvider` for queries), forwards the request to `MainActivity` with a transparent theme to avoid visual focus, and stops itself after the reply, removing the notification. This approach complies with Android's background restrictions (API 26+), minimizes UX impact (brief, low-priority notification), and integrates seamlessly with your dual-process architecture (`:native_handler` for isolation, `:main` for PWA), secure storage (`EncryptedSharedPreferences` for permissions), and intent pipeline (PendingIntent replies). No architecture changes are required, and the service runs only for the request duration (~3-10s).

The report includes logic, reasoning, an implementation plan, and a self-contained demo example (minimal app with key components) to demonstrate the foreground service without assumptions about your codebase.

## Logic and Reasoning

### Problem Context
- **Foreground Focus Issue**: Even with flags like `FLAG_ACTIVITY_NO_USER_ACTION` or `FLAG_ACTIVITY_NO_ANIMATION`, starting `MainActivity` for pre-approved requests can bring the app to the foreground due to Android's activity launch behavior, especially if the process is asleep. This disrupts user experience and contradicts background signing goals.
- **Android Restrictions**: Background activity starts are limited (API 10+), and processes can be killed during delays (e.g., websocket peer sync). Foreground services are the standard way to ensure liveness for user-initiated ops without focus.
- **Your Constraints**: Dual-process isolation (`:native_handler` entry, `:main` for PWA), single-instance `MainActivity` (`singleInstancePerTask`), no arch changes, secure storage for permissions.

### Why a Temporary Foreground Service?
- **Process Liveness**: Starts in `:main`, elevating priority (OOM score ~100) to prevent killing during delays, allowing `MainActivity` to process via AsyncBridge/PWA without focus.
- **No Focus on MainActivity**: Combine with transparent theme (hides UI) and no user-facing elements; start activity from service without forcing foreground.
- **Temporary**: Runs per-request (start via `startForegroundService` from `:native_handler`, stop after reply), dismissing notification quickly.
- **Pipeline Integration**: Triggered after permission check in `:native_handler`; forwards intent to `MainActivity` (reuses your clean flags and PendingIntent).
- **UX/Battery**: Low-priority notification ("Processing secure request...") is non-intrusive; short duration minimizes impact. Complies with API 26+ foreground rules.
- **Alternatives Considered**:
  - Transparent theme + `moveTaskToBack(true)`: Simpler but may cause ~100ms flashes on some devices.
  - No service: Risks process kill during delays, leading to timeouts.
  - Persistent service: Overkill (always-on notification); violates temporary need.

### Key Implementation Elements
- **Permission Check**: In `:native_handler`, query secure storage; if "AUTOMATIC", start service instead of direct intent.
- **Service Flow**: Starts foreground (notification), forwards intent to `MainActivity`, stops on reply receipt (via broadcast receiver in `:native_handler`).
- **MainActivity**: Uses transparent theme if from service; processes request (PWA skips prompts for "AUTOMATIC"); replies via PendingIntent.
- **Timeout**: 30s latch in `IglooContentProvider` (for queries) or handler (for intents) covers delays, returns `null`/error if exceeded.

### Risks and Mitigations
- **Notification Visibility**: Brief (~3-10s); use low priority to avoid alerts.
- **Battery/Compliance**: `foregroundServiceType="shortService"` (API 29+) optimizes for brief ops; request `POST_NOTIFICATIONS` (API 33+).
- **ANR**: Binder thread for queries, async pipeline for intents—safe for blocks.
- **Offline Peers**: PWA returns error → rejected result.

## Implementation Plan

**Duration**: ~1-2 weeks to integrate (add ~0.5 week to existing Content Resolver plan).  
**Steps**:
1. **Manifest Updates**: Add service, transparent theme (as in demo).
2. **Service Implementation**: Create `Nip55ForegroundService` in `:main` (as in demo).
3. **Handler/Provider Updates**: Check perms in `:native_handler`; start service for "AUTOMATIC" (forward intent inside service).
4. **MainActivity Updates**: Set transparent theme if from service; process and reply.
5. **Testing**: Simulate asleep process, delays, verify no focus.

## Demo Example

This self-contained demo is a minimal Android app showing the foreground service for background signing. It includes `InvisibleNIP55Handler` (intent entry), `IglooContentProvider` (query entry), secure storage, service, and `MainActivity` with a dummy WebView/PWA for simulation. Build with Android Studio (target SDK 35).

### build.gradle (app)
```gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    compileSdk 35
    defaultConfig {
        applicationId "com.example.nip55demo"
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName "1.0"
    }
    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'
}
```

### AndroidManifest.xml (as above in previous response)
(Use the one provided in the demo example.)

### PermissionDbHelper.kt (as above)

### Nip55ForegroundService.kt (as above)

### InvisibleNIP55Handler.kt (as above)

### IglooContentProvider.kt (as above)

### MainActivity.kt (as above)

### assets/index.html (PWA dummy, as above)

### res/layout/activity_main.xml (as above)

### Testing the Demo
- **Build/Run**: Install on emulator/device (API 35).
- **Set Permission**: Use adb or app to set "AUTOMATIC" (demo uses dummy).
- **Trigger Intent**: `adb shell am start -a android.intent.action.VIEW -d "nostrsigner:?type=sign_event" --es event '{}' --es current_user 'user1'`
- **Trigger Query**: `adb shell content query --uri content://com.example.nip55demo.signing/SIGN_EVENT --projection '{}' '' 'user1'`
- **Observe**: Notification appears (~2s delay simulation), no app focus, logs show processing, notification disappears.
- **Asleep Test**: `adb shell am force-stop com.example.nip55demo`, retry; confirm wake-up without focus.

This demo implements the foreground service as a drop-in for your pipeline, ensuring `MainActivity` stays background. Adapt to your codebase by replacing dummy JS with FROSTR and validation with yours. If focus persists, add `android:windowIsFloating="true"` to transparent theme or test on specific devices.