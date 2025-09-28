#!/usr/bin/env python3
"""
NIP-55 Intent Testing Script

This script tests the complete NIP-55 intent handling pipeline:
1. Sends NIP-55 intents to the Android app
2. Verifies PWA permission system integration
3. Tests auto-approval rules from Android context
4. Validates permission synchronization

Prerequisites:
- Android device/emulator with the Igloo app installed
- ADB installed and device connected
- App permissions granted for external intent handling
"""

import subprocess
import json
import time
import sys
import urllib.parse
from typing import Optional, Dict, Any

class NIP55IntentTester:
    def __init__(self):
        self.package_name = "com.frostr.igloo"
        self.activity_name = "com.frostr.igloo/.InvisibleNIP55Handler"
        self.test_app_id = "com.example.testapp"

    def run_adb_command(self, command: str) -> tuple[str, str, int]:
        """Run ADB command and return stdout, stderr, return_code"""
        try:
            result = subprocess.run(
                f"adb {command}",
                shell=True,
                capture_output=True,
                text=True,
                timeout=30
            )
            return result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired:
            return "", "Command timed out", 1
        except Exception as e:
            return "", str(e), 1

    def check_device_connected(self) -> bool:
        """Check if Android device is connected"""
        stdout, stderr, code = self.run_adb_command("devices")
        if code != 0:
            print(f"❌ ADB not available: {stderr}")
            return False

        devices = [line for line in stdout.split('\n') if '\tdevice' in line]
        if not devices:
            print("❌ No Android device connected")
            return False

        print(f"✅ Device connected: {devices[0].split()[0]}")
        return True

    def check_app_installed(self) -> bool:
        """Check if Igloo app is installed"""
        stdout, stderr, code = self.run_adb_command(f"shell pm list packages {self.package_name}")
        if self.package_name in stdout:
            print(f"✅ App installed: {self.package_name}")
            return True
        else:
            print(f"❌ App not installed: {self.package_name}")
            return False

    def send_nip55_intent(self, action_type: str, params: Dict[str, str] = None) -> bool:
        """Send NIP-55 intent to the app"""
        if params is None:
            params = {}

        # Build NIP-55 intent according to the specification
        print(f"🔄 Sending NIP-55 intent: {action_type}")

        if action_type == "get_public_key":
            uri = "nostrsigner:"
            intent_cmd = (
                f"shell am start "
                f"-a android.intent.action.VIEW "
                f"-d '{uri}' "
                f"--es type get_public_key "
                f"--es package {self.test_app_id}"
            )
        elif action_type == "sign_event":
            # Test event (kind 1 note)
            test_event = {
                "kind": 1,
                "content": "Test note from NIP-55 intent",
                "tags": [],
                "created_at": int(time.time())
            }
            event_json = json.dumps(test_event)
            # Properly escape the JSON for shell command
            escaped_event_json = event_json.replace('"', '\\"')
            uri = f"nostrsigner:{escaped_event_json}"
            intent_cmd = (
                f"shell am start "
                f"-a android.intent.action.VIEW "
                f"-d '{uri}' "
                f"--es type sign_event "
                f"--es id test_sign_event_{int(time.time())} "
                f"--es package {self.test_app_id}"
            )
        elif action_type == "nip04_encrypt":
            plaintext = params.get("plaintext", "Hello from NIP-55 test")
            pubkey = params.get("pubkey", "a1633cafcc01ebfb6d78e39f687a1f0995c62fc95f51ead10a02ee0be551b5dc")
            uri = f"nostrsigner:{plaintext}"
            intent_cmd = (
                f"shell am start "
                f"-a android.intent.action.VIEW "
                f"-d '{uri}' "
                f"--es type nip04_encrypt "
                f"--es id test_encrypt_{int(time.time())} "
                f"--es pubkey {pubkey} "
                f"--es package {self.test_app_id}"
            )
        elif action_type == "nip04_decrypt":
            ciphertext = params.get("ciphertext", "test_ciphertext")
            pubkey = params.get("pubkey", "a1633cafcc01ebfb6d78e39f687a1f0995c62fc95f51ead10a02ee0be551b5dc")
            uri = f"nostrsigner:{ciphertext}"
            intent_cmd = (
                f"shell am start "
                f"-a android.intent.action.VIEW "
                f"-d '{uri}' "
                f"--es type nip04_decrypt "
                f"--es id test_decrypt_{int(time.time())} "
                f"--es pubkey {pubkey} "
                f"--es package {self.test_app_id}"
            )
        else:
            print(f"❌ Unknown action type: {action_type}")
            return False

        print(f"   URI: {uri}")
        print(f"   Intent extras: type={action_type}")

        # Send intent via ADB using proper NIP-55 format

        stdout, stderr, code = self.run_adb_command(intent_cmd)

        if code == 0:
            print(f"✅ Intent sent successfully")
            return True
        else:
            print(f"❌ Failed to send intent: {stderr}")
            return False

    def monitor_app_logs(self, duration: int = 10):
        """Monitor app logs for NIP-55 processing"""
        print(f"🔍 Monitoring app logs for {duration} seconds...")

        # Clear existing logs
        self.run_adb_command("logcat -c")

        # Monitor logs with filter for our app
        cmd = f"logcat -s 'NIP55IntentService:*' 'UnifiedSigningBridge:*' 'SecureIglooWrapper:*' 'UnifiedPermissionSync:*'"

        try:
            process = subprocess.Popen(
                f"adb {cmd}",
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )

            start_time = time.time()
            while time.time() - start_time < duration:
                line = process.stdout.readline()
                if line:
                    # Clean up and display log line
                    clean_line = line.strip()
                    if any(keyword in clean_line.lower() for keyword in ['nip55', 'signing', 'permission', 'unified']):
                        print(f"📱 {clean_line}")

                if process.poll() is not None:
                    break

            process.terminate()
            process.wait()

        except KeyboardInterrupt:
            print("\n🛑 Log monitoring stopped")
        except Exception as e:
            print(f"❌ Error monitoring logs: {e}")

    def test_permission_integration(self):
        """Test that NIP-55 intents properly integrate with unified permissions"""
        print("\n🧪 Testing permission integration...")

        # Test 1: First-time request (should prompt)
        print("\n📝 Test 1: First-time get_public_key request")
        if self.send_nip55_intent("get_public_key"):
            self.monitor_app_logs(5)

        time.sleep(2)

        # Test 2: Sign event request
        print("\n📝 Test 2: Sign event request")
        if self.send_nip55_intent("sign_event"):
            self.monitor_app_logs(5)

        time.sleep(2)

        # Test 3: NIP-04 encryption
        print("\n📝 Test 3: NIP-04 encryption request")
        if self.send_nip55_intent("nip04_encrypt", {
            "plaintext": "Test message for encryption",
            "pubkey": "a1633cafcc01ebfb6d78e39f687a1f0995c62fc95f51ead10a02ee0be551b5dc"
        }):
            self.monitor_app_logs(5)

    def test_auto_approval_workflow(self):
        """Test the complete auto-approval workflow"""
        print("\n🤖 Testing auto-approval workflow...")
        print("This test requires manual interaction:")
        print("1. Approve the first request and select 'Remember this decision'")
        print("2. The second identical request should be auto-approved")

        # First request - should prompt
        print("\n📝 First request (should prompt):")
        if self.send_nip55_intent("get_public_key"):
            print("👆 Please approve this request and select 'Remember this decision'")
            self.monitor_app_logs(15)  # Give time for user interaction

        time.sleep(3)

        # Second request - should auto-approve
        print("\n📝 Second request (should auto-approve):")
        if self.send_nip55_intent("get_public_key"):
            self.monitor_app_logs(8)

    def run_comprehensive_test(self):
        """Run all NIP-55 intent tests"""
        print("🚀 Starting NIP-55 Intent Testing")
        print("=" * 50)

        # Prerequisites check
        if not self.check_device_connected():
            return False

        if not self.check_app_installed():
            return False

        # Make sure app is running
        print("\n🔄 Starting app...")
        self.run_adb_command(f"shell am start -n {self.package_name}/.SecureMainActivity")
        time.sleep(3)

        # Run tests
        try:
            self.test_permission_integration()

            print("\n" + "="*50)
            input("Press Enter to continue with auto-approval test...")

            self.test_auto_approval_workflow()

        except KeyboardInterrupt:
            print("\n🛑 Testing interrupted")
            return False

        print("\n✅ NIP-55 intent testing completed!")
        print("\nNext steps:")
        print("1. Check the PWA permissions tab to verify rules were created")
        print("2. Test permission synchronization between PWA and Android")
        print("3. Verify audit trail entries appear in both systems")

        return True

def main():
    print("NIP-55 Intent Testing Tool")
    print("=" * 30)

    if len(sys.argv) > 1:
        action = sys.argv[1]
        tester = NIP55IntentTester()

        if action == "check":
            tester.check_device_connected()
            tester.check_app_installed()
        elif action == "monitor":
            duration = int(sys.argv[2]) if len(sys.argv) > 2 else 10
            tester.monitor_app_logs(duration)
        elif action in ["get_public_key", "sign_event", "nip04_encrypt"]:
            tester.send_nip55_intent(action)
            tester.monitor_app_logs(8)
        elif action == "test":
            tester.run_comprehensive_test()
        else:
            print(f"Unknown action: {action}")
            print("Available actions: check, monitor [seconds], get_public_key, sign_event, nip04_encrypt, test")
    else:
        # Interactive mode
        tester = NIP55IntentTester()
        tester.run_comprehensive_test()

if __name__ == "__main__":
    main()