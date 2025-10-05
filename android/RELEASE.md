# Igloo Android Release Build Guide

## Release Configuration

### Keystore Information
- **Location**: `android/igloo-release.keystore`
- **Alias**: `igloo`
- **Validity**: 10,000 days (~27 years)
- **Key Algorithm**: RSA 2048-bit
- **Certificate**: Self-signed SHA384withRSA

**⚠️ IMPORTANT**: The keystore file and passwords are **NOT** committed to git. Keep the keystore and credentials secure!

### Current Version
- **Version Code**: 1
- **Version Name**: 1.0-beta1
- **Package**: com.frostr.igloo

## Building Release APK

### Prerequisites
1. Ensure PWA is built for production:
   ```bash
   cd /home/cscott/Repos/frostr/pwa
   npm run build
   ```

2. Verify keystore exists:
   ```bash
   ls android/igloo-release.keystore
   ```

### Build Commands

#### Option 1: Build Signed Release APK
```bash
cd /home/cscott/Repos/frostr/pwa/android
./gradlew assembleRelease
```

**Output**: `app/build/outputs/apk/release/app-release.apk`

#### Option 2: Build and Install to Device
```bash
cd /home/cscott/Repos/frostr/pwa/android
./gradlew installRelease
```

#### Option 3: Clean Build
```bash
cd /home/cscott/Repos/frostr/pwa/android
./gradlew clean assembleRelease
```

### Verify Signed APK

Check APK signature:
```bash
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

Check APK certificate:
```bash
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

## Release Checklist

Before building a release:

- [ ] Update `versionCode` in `app/build.gradle` (increment for each release)
- [ ] Update `versionName` in `app/build.gradle` (e.g., "1.0-beta2", "1.0-rc1", "1.0")
- [ ] Build PWA production assets: `npm run build`
- [ ] Test NIP-55 signing flow with Amethyst
- [ ] Verify ProGuard rules don't break functionality
- [ ] Clean build: `./gradlew clean`
- [ ] Build release APK: `./gradlew assembleRelease`
- [ ] Test release APK on physical device
- [ ] Verify APK signature: `apksigner verify`

## Release Types

### Beta Releases
- Version format: `1.0-beta1`, `1.0-beta2`, etc.
- For internal testing and early adopters
- Full functionality, may have minor bugs

### Release Candidates
- Version format: `1.0-rc1`, `1.0-rc2`, etc.
- Feature-complete, final testing phase
- Ready for production pending final validation

### Production Releases
- Version format: `1.0`, `1.1`, `2.0`, etc.
- Stable, production-ready
- All features tested and validated

## ProGuard/R8 Code Shrinking

Release builds use ProGuard/R8 for:
- **Code shrinking** - Removes unused code
- **Obfuscation** - Renames classes/methods
- **Optimization** - Optimizes bytecode

### ProGuard Rules
Configuration: `app/proguard-rules.pro`

Key keep rules:
- JavaScript interface classes (`com.frostr.igloo.bridges.**`)
- NIP-55 data classes
- Gson models
- CameraX, OkHttp, Security Crypto libraries

Debug logging is **completely removed** from release builds via `-assumenosideeffects`.

## Testing Release Builds

### Install Release APK
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Monitor Release Build Logs
```bash
# Note: Debug logs are stripped in release builds
adb logcat -s "AndroidRuntime:E" "*:F"
```

### Test Critical Paths
1. **App Launch** - Verify app starts correctly
2. **PWA Loading** - Check PWA loads from bundled assets
3. **Secure Storage** - Test encrypted storage works
4. **Camera** - Verify QR scanning functionality
5. **NIP-55 Signing** - Test with Amethyst:
   - get_public_key
   - sign_event
   - Auto-approval for saved permissions
6. **WebSocket** - Test relay connections

## Distribution

### Internal Testing
Share the APK directly:
```bash
# Copy APK for distribution
cp app/build/outputs/apk/release/app-release.apk ~/igloo-v1.0-beta1.apk
```

### Google Play Store
For Play Store distribution:
1. Create App Bundle instead of APK:
   ```bash
   ./gradlew bundleRelease
   ```
   Output: `app/build/outputs/bundle/release/app-release.aab`

2. Upload AAB to Play Console
3. Follow Play Store review process

## Version Management

### Incrementing Versions

Edit `app/build.gradle`:

```gradle
defaultConfig {
    versionCode 2           // Increment for EVERY release
    versionName "1.0-beta2" // User-facing version string
}
```

**Version Code Rules**:
- Must increase with every release
- Google Play requires versionCode to be higher than previous
- Never reuse a versionCode

**Version Name Guidelines**:
- `X.Y-betaN` - Beta releases
- `X.Y-rcN` - Release candidates
- `X.Y` - Stable releases
- `X.Y.Z` - Patch releases

## Troubleshooting

### Build Fails with Signing Error
- Verify keystore path is correct: `../igloo-release.keystore`
- Check keystore passwords in `build.gradle`
- Ensure keystore file exists and is readable

### ProGuard Breaking Functionality
- Check ProGuard warnings in build output
- Add missing `-keep` rules to `proguard-rules.pro`
- Test release build thoroughly before distribution

### APK Won't Install
- Check version code is higher than installed version
- Uninstall old version: `adb uninstall com.frostr.igloo`
- Verify APK is properly signed: `apksigner verify`

## Security Notes

### Keystore Security
- **NEVER** commit keystore to git
- **NEVER** commit keystore passwords to git
- Store keystore in secure location with backups
- Use environment variables for passwords in CI/CD

### Production Keystore
For production releases, consider:
- Using separate production keystore
- Storing keystore in secure vault (e.g., 1Password, AWS Secrets Manager)
- Using environment variables for credentials:
  ```gradle
  signingConfigs {
      release {
          storeFile file(System.getenv("KEYSTORE_FILE") ?: '../igloo-release.keystore')
          storePassword System.getenv("KEYSTORE_PASSWORD") ?: 'frostr2025'
          keyAlias System.getenv("KEY_ALIAS") ?: 'igloo'
          keyPassword System.getenv("KEY_PASSWORD") ?: 'frostr2025'
      }
  }
  ```

## Current Release Status

**Latest Version**: 1.0-beta1
**Build Status**: ✅ Configured and ready to build
**Signing**: ✅ Keystore configured
**ProGuard**: ✅ Rules configured
**NIP-55**: ✅ Fully functional with Amethyst

**Next Steps**:
1. Build release APK: `./gradlew assembleRelease`
2. Test on physical device
3. Distribute to beta testers
4. Gather feedback
5. Increment version for next release
