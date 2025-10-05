# CRITICAL GRADLE CONFIGURATION NOTES

## ⚠️ NEVER ADD DEBUG SUFFIXES TO PACKAGE NAME ⚠️

**DO NOT** add `applicationIdSuffix` or `versionNameSuffix` to the debug build type.

### Why This Is Critical

Adding suffix to the applicationId creates **two separate apps** with:
- Completely separate package names
- Separate data directories
- Separate storage (localStorage, SharedPreferences)
- Separate permissions and settings

This causes the following problems:
1. **Testing wrong app**: User tests old `com.frostr.igloo.debug` while developer deploys to new `com.frostr.igloo`
2. **Cached code persists**: Old JavaScript cached in debug app never gets updated
3. **Wasted debugging time**: Hours spent investigating "caching issues" that don't exist
4. **Data isolation**: Permissions and settings don't carry over between apps

### Date of Regression: 2025-10-04
Cost: ~2 hours of debugging time chasing phantom caching issues

### Correct Configuration

```gradle
buildTypes {
    debug {
        minifyEnabled false
        debuggable true
        // ✅ NO applicationIdSuffix
        // ✅ NO versionNameSuffix
    }
    release {
        minifyEnabled true
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        debuggable false
    }
}
```

### If You Need Multiple Versions

Use **productFlavors** instead, and make it EXPLICIT in the app name:

```gradle
flavorDimensions "version"
productFlavors {
    dev {
        dimension "version"
        applicationIdSuffix ".dev"
        versionNameSuffix "-dev"
        resValue "string", "app_name", "Igloo DEV"  // Makes it obvious
    }
    production {
        dimension "version"
        resValue "string", "app_name", "Igloo"
    }
}
```

## Lesson Learned

**Package name changes are NOT for debugging - they create separate apps.**

If testing shows old cached code:
1. ✅ Check `adb shell pm list packages` for duplicate packages
2. ✅ Check build.gradle for applicationIdSuffix
3. ✅ Uninstall ALL variants before debugging "cache issues"
4. ❌ DO NOT add cache clearing code as first resort
5. ❌ DO NOT assume WebView caching is broken

The correct package name is: `com.frostr.igloo` (no suffix)
