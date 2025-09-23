#!/bin/bash

# emulator.sh - Start Android emulator for Igloo PWA development
# Simple script to ensure an emulator is running and ready for PWA testing

set -e

# Global variables
EMULATOR_PID=""
AVD_NAME=""
PORTS=()

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Show usage
show_usage() {
    echo "Usage: $0 [-p PORT1,PORT2,...] [-k] [-h]"
    echo ""
    echo "Options:"
    echo "  -p PORTS    Comma-separated list of ports to forward (e.g., -p 3000,8080)"
    echo "  -k          Kill all running emulators and exit"
    echo "  -h          Show this help message"
    echo ""
    echo "Interactive Commands (when running):"
    echo "  q           Quit and shutdown emulator"
    echo "  i           Show PWA testing instructions"
    echo "  c           Open Chrome with PWA URL"
    echo ""
    echo "Examples:"
    echo "  $0                    # Default: Forward ports 3000 (PWA) and 8080 (relay)"
    echo "  $0 -p 3000           # Forward port 3000 explicitly"
    echo "  $0 -p 3000,8080      # Forward ports 3000 and 8080"
    echo "  $0 -k                 # Kill all emulators"
}

# Cleanup on exit
cleanup() {
    echo ""
    info "Shutting down emulator..."

    # Set environment variable to reduce shutdown wait time
    export ANDROID_EMULATOR_WAIT_TIME_BEFORE_KILL=5

    # Step 1: Try graceful shutdown via ADB (with output suppression)
    info "Attempting graceful shutdown..."
    adb devices 2>/dev/null | grep "emulator-" | awk '{print $1}' | while read device; do
        adb -s "$device" emu kill >/dev/null 2>&1 || true
    done
    sleep 2

    # Step 2: Kill emulator process if still running
    if [ -n "$EMULATOR_PID" ] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
        info "Force terminating emulator process..."
        kill -TERM "$EMULATOR_PID" >/dev/null 2>&1 || true
        sleep 1

        # If still running, force kill
        if kill -0 "$EMULATOR_PID" 2>/dev/null; then
            kill -KILL "$EMULATOR_PID" >/dev/null 2>&1 || true
        fi
    fi

    # Step 3: Clean up any lingering processes (silently)
    info "Cleaning up lingering processes..."

    # Kill all QEMU processes related to this AVD
    if [ -n "$AVD_NAME" ]; then
        pgrep -f "qemu-system.*$AVD_NAME" 2>/dev/null | xargs -r kill -KILL >/dev/null 2>&1 || true
    fi

    # Kill any remaining emulator processes
    pgrep -f "emulator.*-avd" 2>/dev/null | xargs -r kill -KILL >/dev/null 2>&1 || true

    # Kill any remaining adb processes that might be stuck
    pgrep -f "adb.*emulator" 2>/dev/null | xargs -r kill -KILL >/dev/null 2>&1 || true

    # Step 4: Clear port forwards
    info "Clearing port forwards..."
    adb reverse --remove-all >/dev/null 2>&1 || true

    success "Emulator shutdown complete."
    exit 0
}

trap cleanup SIGINT SIGTERM EXIT

# Check tools and system requirements
check_tools() {
    command -v adb >/dev/null || { error "adb not found. Install Android SDK."; exit 1; }
    command -v emulator >/dev/null || { error "emulator not found. Install Android SDK."; exit 1; }

    # Check available memory (8GB emulator + 2GB overhead = 10GB recommended)
    local available_mem_kb=$(grep MemAvailable /proc/meminfo | awk '{print $2}' 2>/dev/null || echo "0")
    local available_mem_gb=$((available_mem_kb / 1024 / 1024))

    if [ "$available_mem_gb" -lt 6 ]; then
        error "Warning: Low system memory ($available_mem_gb GB available). 8GB+ recommended for optimal performance."
        info "Consider closing other applications or reducing emulator memory."
    else
        info "System memory check: $available_mem_gb GB available ✓"
    fi

    # Check for KVM support
    if [ -e /dev/kvm ]; then
        info "KVM acceleration available ✓"
    else
        error "Warning: KVM not available. Emulator will be slower."
        info "Run: sudo apt install qemu-kvm libvirt-daemon-system"
    fi
}

# Check if device is running
device_running() {
    adb devices | grep -q "device$"
}

# Start emulator
start_emulator() {
    info "Starting Android emulator..."
    
    # Get first available AVD
    AVD_NAME=$(emulator -list-avds | head -n 1)
    [ -z "$AVD_NAME" ] && { error "No AVDs found. Create one in Android Studio."; exit 1; }
    
    info "Starting AVD: $AVD_NAME"
    # Performance and compatibility environment variables
    export QT_QPA_PLATFORM=xcb
    export ANDROID_EMULATOR_USE_SYSTEM_LIBS=1
    export QT_X11_NO_MITSHM=1
    export _JAVA_AWT_WM_NONREPARENTING=1
    export ANDROID_EMULATOR_WAIT_TIME_BEFORE_KILL=5
    export ANDROID_ADB_SERVER_PORT=5037
    export ANDROID_SERIAL=""
    # Disable telemetry for faster startup
    export ANDROID_SDK_ROOT_TELEMETRY_DISABLE=1
    export ANDROID_EMULATOR_METRICS_DISABLE=1
    
    emulator -avd "$AVD_NAME" \
        -no-snapshot-save -no-snapshot-load \
        -accel auto -gpu swiftshader_indirect \
        -memory 8192 -partition-size 4096 -cores 4 \
        -camera-back none -camera-front none \
        -no-audio -no-boot-anim \
        -skin 1080x1920 -netdelay none -netspeed full \
        -qemu -enable-kvm 2>/dev/null &
    EMULATOR_PID=$!
    
    sleep 5
    kill -0 "$EMULATOR_PID" 2>/dev/null || { error "Emulator failed to start"; exit 1; }
    
    # Wait for device
    info "Waiting for emulator to be ready..."
    local timeout=120
    local elapsed=0
    
    while [ $elapsed -lt $timeout ]; do
        if device_running; then
            success "Emulator ready! Setting up port forwarding..."
            adb wait-for-device
            sleep 3
            
            # Set up reverse port forwarding
            setup_port_forwarding
            success "Ready!"
            return 0
        fi
        
        echo -n "."
        sleep 3
        elapsed=$((elapsed + 3))
        
        [ $((elapsed % 30)) -eq 0 ] && echo "" && info "Still waiting... (${elapsed}/${timeout}s)"
    done
    
    error "Timeout waiting for emulator"
    exit 1
}

# Set up port forwarding
setup_port_forwarding() {
    if [ ${#PORTS[@]} -eq 0 ]; then
        info "No ports specified for forwarding"
        return 0
    fi
    
    info "Setting up port forwarding for ports: ${PORTS[*]}"
    for port in "${PORTS[@]}"; do
        if adb reverse tcp:$port tcp:$port; then
            success "Port $port forwarded successfully"
        else
            error "Failed to forward port $port"
        fi
    done
}

# Show PWA testing instructions
show_pwa_instructions() {
    echo ""
    echo "🚀 PWA Testing Instructions:"
    echo "════════════════════════════"
    echo ""
    echo "1. Open Chrome on the emulator and navigate to:"
    echo "   http://localhost:3000"
    echo ""
    echo "2. Install the PWA:"
    echo "   • Look for the install icon in the address bar"
    echo "   • Tap 'Add to Home screen' or 'Install'"
    echo ""
    echo "3. Test protocol handlers:"
    echo "   • Open the installed PWA"
    echo "   • Register protocol handler (should happen automatically)"
    echo "   • Test with: adb shell am start -a android.intent.action.VIEW -d \"nostrsigner:?type=get_public_key\""
    echo ""
    echo "4. Alternative testing:"
    echo "   • Navigate to: http://localhost:3000/test/nip55-external-test.html"
    echo "   • Use the test buttons to trigger nostrsigner: URLs"
    echo ""
    echo "Press 'q' to quit, 'i' for instructions, 'c' to open Chrome, 'a' to install Android app"
    echo ""
}

# Open Chrome on Android emulator
open_chrome() {
    info "Opening Chrome on emulator..."
    adb shell am start -a android.intent.action.MAIN -n com.android.chrome/com.google.android.apps.chrome.Main -d "http://localhost:3000" || {
        error "Failed to open Chrome. Make sure Chrome is installed on the emulator."
        info "You can manually open Chrome and navigate to http://localhost:3000"
    }
}

# Install and launch Android app
install_android_app() {
    info "Installing Android app..."

    local apk_path="android/app/build/outputs/apk/debug/app-debug.apk"

    if [ ! -f "$apk_path" ]; then
        error "APK not found at $apk_path"
        info "Build the Android app first with: cd android && ./gradlew assembleDebug"
        return 1
    fi

    info "Installing APK..."
    if adb install -r "$apk_path"; then
        success "Android app installed successfully!"
        info "Launching app..."
        adb shell am start -n com.frostr.igloo/.MainActivity
        success "Android app launched!"
    else
        error "Failed to install Android app"
        return 1
    fi
}

# Keep emulator running with interactive commands
keep_running() {
    show_pwa_instructions

    while true; do
        read -n 1 -s key
        case "$key" in
            'q'|'Q')
                cleanup
                ;;
            'i'|'I')
                show_pwa_instructions
                ;;
            'c'|'C')
                open_chrome
                ;;
            'a'|'A')
                install_android_app
                ;;
            *)
                # Ignore other keys silently
                ;;
        esac
    done
}

# Force kill all emulators
force_kill_emulators() {
    info "Force killing all running emulators..."
    
    # Kill via ADB first
    adb devices | grep "emulator-" | awk '{print $1}' | while read device; do
        info "Killing emulator: $device"
        adb -s "$device" emu kill 2>/dev/null || true
    done
    sleep 2
    
    # Force kill all emulator processes
    pgrep -f "emulator.*-avd" | while read pid; do
        info "Force killing emulator process: $pid"
        kill -KILL "$pid" 2>/dev/null || true
    done
    
    # Force kill all QEMU processes
    pgrep -f "qemu-system" | while read pid; do
        info "Force killing QEMU process: $pid"
        kill -KILL "$pid" 2>/dev/null || true
    done
    
    # Clear all port forwards
    adb reverse --remove-all 2>/dev/null || true
    
    success "All emulators killed."
}

# Parse command line arguments
parse_args() {
    # Set default port for PWA dev server if no ports specified
    local default_ports_set=false

    while getopts "p:kh" opt; do
        case $opt in
            p)
                IFS=',' read -ra PORTS <<< "$OPTARG"
                default_ports_set=true
                ;;
            k)
                force_kill_emulators
                exit 0
                ;;
            h)
                show_usage
                exit 0
                ;;
            \?)
                error "Invalid option: -$OPTARG"
                show_usage
                exit 1
                ;;
        esac
    done

    # If no ports specified, default to 3000 for PWA dev server and 8080 for relay
    if [ "$default_ports_set" = false ]; then
        PORTS=(3000 8080)
        info "No ports specified, defaulting to ports 3000 (PWA) and 8080 (relay)"
    fi
}

# Main
main() {
    parse_args "$@"
    
    echo "========================================"
    echo "   Igloo PWA Android Testing Helper"
    echo "========================================"
    echo ""
    
    if [ ${#PORTS[@]} -gt 0 ]; then
        info "Ports to forward: ${PORTS[*]}"
    else
        info "No port forwarding configured"
    fi
    echo ""
    
    check_tools
    info "Checking for running devices..."
    
    if device_running; then
        success "Device already running! Setting up port forwarding..."
        adb devices | grep "device$"
        
        # Set up reverse port forwarding
        setup_port_forwarding
        success "Ready!"
    else
        info "No devices found"
        start_emulator
    fi
    
    keep_running
}

main "$@"