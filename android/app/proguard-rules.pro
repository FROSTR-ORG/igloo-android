# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep JavaScript Interface classes
-keep class com.frostr.igloo.bridges.** { *; }

# Keep classes that are used via reflection
-keep class com.frostr.igloo.data.** { *; }

# Remove debug logging in release builds
# This will completely remove all debug logging calls from the compiled bytecode
-assumenosideeffects class com.frostr.igloo.debug.NIP55DebugLogger {
    public static *** logFlowStart(...);
    public static *** logFlowEnd(...);
    public static *** logIntent(...);
    public static *** logIPC(...);
    public static *** logPWABridge(...);
    public static *** logPermission(...);
    public static *** logSigning(...);
    public static *** logTiming(...);
    public static *** logError(...);
    public static *** logStateTransition(...);
    public static *** logJSON(...);
    public static *** logSummary(...);
}

# Remove debug config constants in release builds
-assumenosideeffects class com.frostr.igloo.debug.DebugConfig {
    public static final boolean DEBUG_ENABLED return false;
    public static final boolean NIP55_LOGGING return false;
    public static final boolean INTENT_LOGGING return false;
    public static final boolean IPC_LOGGING return false;
    public static final boolean TIMING_LOGGING return false;
    public static final boolean PERMISSION_LOGGING return false;
    public static final boolean PWA_BRIDGE_LOGGING return false;
    public static final boolean SIGNING_LOGGING return false;
    public static final boolean ERROR_LOGGING return false;
    public static final boolean FLOW_LOGGING return false;
    public static final boolean VERBOSE_LOGGING return false;
}

# Keep NIP-55 data classes and new request structure
-keep class com.frostr.igloo.NIP55Request { *; }
-keep class com.frostr.igloo.bridges.NIP55RequestContext { *; }

# Keep WelcomeDialog inner classes for Gson deserialization
-keep class com.frostr.igloo.WelcomeDialog$WelcomeContent { *; }

# Gson specific rules
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.stream.** { *; }

# OkHttp specific rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep generic signature of Call, Response (R8 full mode strips signatures from non-kept items).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }
-keep class androidx.security.crypto.MasterKeys { *; }

# CameraX
-keep class androidx.camera.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep line numbers for debugging stack traces in release builds
-keepattributes SourceFile,LineNumberTable

# Keep custom exceptions
-keep public class * extends java.lang.Exception

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile