#!/bin/bash

# WebView Debugging Setup Script
# Automatically sets up port forwarding for WebView debugging

echo "🔍 Setting up WebView debugging for Igloo PWA..."

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "❌ adb command not found. Please install Android SDK platform tools."
    exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device/emulator found. Please connect a device or start an emulator."
    exit 1
fi

# Find Igloo app PID
echo "🔍 Looking for Igloo app process..."
PID=$(adb shell ps | grep "com.frostr.igloo:main" | awk '{print $2}')

if [ -z "$PID" ]; then
    echo "❌ Igloo app not found. Please make sure the app is running."
    echo "   Start it with: adb shell am start -n com.frostr.igloo/.SecureMainActivity"
    exit 1
fi

echo "✅ Found Igloo app with PID: $PID"

# Set up port forwarding
echo "🔗 Setting up port forwarding..."
adb forward tcp:9222 localabstract:webview_devtools_remote_$PID

if [ $? -eq 0 ]; then
    echo "✅ Port forwarding established: tcp:9222 -> webview_devtools_remote_$PID"

    # Test the connection
    echo "🧪 Testing connection to DevTools..."
    if curl -s -f http://localhost:9222/json > /dev/null; then
        echo "✅ DevTools connection successful!"

        # Show available pages
        echo ""
        echo "📱 Available debugging targets:"
        curl -s http://localhost:9222/json | node -e "
            const targets = JSON.parse(require('fs').readFileSync(0, 'utf8'));
            targets.forEach((target, i) => {
                console.log(\`  \${i + 1}. \${target.title} (\${target.url})\`);
            });
        "

        echo ""
        echo "🚀 Setup complete! You can now use:"
        echo "   bun android/test/webview-js-executor.ts \"window.location.href\""
        echo "   bun android/test/webview-js-executor.ts \"JSON.stringify({nostr: !!window.nostr, nip55: !!window.nostr?.nip55})\""

    else
        echo "⚠️  Port forwarding set up but connection test failed."
        echo "   Make sure WebView debugging is enabled in the app."
    fi
else
    echo "❌ Failed to set up port forwarding"
    exit 1
fi