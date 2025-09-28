#!/usr/bin/env python3
import json
import time
import requests

def test_permission_system():
    """Test the permission checking system via content provider and IPC"""
    print("Testing Permission System Integration...")

    # First, let's test the NIP55ContentProvider permission check endpoint
    # This would normally be accessed via Android ContentResolver, but we can test
    # the IPC server's permission checking logic

    # Test with a well-known app that might have permissions
    test_request_amethyst = {
        "type": "get_public_key",
        "id": f"amethyst_test_{int(time.time())}",
        "params": {},
        "callingApp": "com.vitorpamplona.amethyst",  # Known Nostr app
        "timestamp": int(time.time() * 1000)
    }

    print("\n1. Testing with known Nostr app (Amethyst)...")
    try:
        response = requests.post(
            'http://127.0.0.1:45555/nip55',
            headers={'Content-Type': 'application/json'},
            data=json.dumps(test_request_amethyst),
            timeout=10
        )

        print(f"   Status: {response.status_code}")
        print(f"   Response: {response.text}")

        try:
            result = json.loads(response.text)
            if "processing" in response.text:
                print("   → Request requires user interaction (expected for new app)")
            elif "result" in result:
                print("   → Request was auto-approved!")
            elif "error" in result:
                print(f"   → Error: {result.get('error', 'Unknown')}")
        except:
            print("   → Non-JSON response")

    except Exception as e:
        print(f"   ✗ Test failed: {e}")

    # Test with different request types
    test_cases = [
        ("sign_event", {"event": json.dumps({"kind": 1, "content": "test", "tags": [], "created_at": int(time.time())})}),
        ("get_public_key", {}),
        ("nip04_encrypt", {"pubkey": "test_pubkey", "plaintext": "test message"}),
        ("nip04_decrypt", {"pubkey": "test_pubkey", "ciphertext": "test_cipher"})
    ]

    print("\n2. Testing different request types...")
    for req_type, params in test_cases:
        test_request = {
            "type": req_type,
            "id": f"test_{req_type}_{int(time.time())}",
            "params": params,
            "callingApp": "test_permission_app",
            "timestamp": int(time.time() * 1000)
        }

        print(f"\n   Testing {req_type}...")
        try:
            response = requests.post(
                'http://127.0.0.1:45555/nip55',
                headers={'Content-Type': 'application/json'},
                data=json.dumps(test_request),
                timeout=8
            )

            print(f"   Status: {response.status_code}")
            if response.status_code == 200:
                try:
                    result = json.loads(response.text)
                    if "processing" in response.text:
                        print("   → Requires user prompt (default behavior)")
                    elif "error" in result:
                        print(f"   → Error: {result.get('error', 'Unknown')}")
                    else:
                        print("   → Unexpected response format")
                except:
                    print("   → Non-JSON response")
            else:
                print(f"   → HTTP error: {response.status_code}")

        except Exception as e:
            print(f"   ✗ {req_type} test failed: {e}")

    print("\n3. Testing invalid requests...")

    # Test malformed request
    malformed_request = {
        "type": "invalid_action",
        "id": "test_invalid",
        "params": {},
        "callingApp": "test_app",
        "timestamp": int(time.time() * 1000)
    }

    try:
        response = requests.post(
            'http://127.0.0.1:45555/nip55',
            headers={'Content-Type': 'application/json'},
            data=json.dumps(malformed_request),
            timeout=5
        )

        print(f"   Invalid request status: {response.status_code}")
        print(f"   Response: {response.text}")

    except Exception as e:
        print(f"   ✗ Invalid request test failed: {e}")

    print("\n✓ Permission system integration tests completed!")

if __name__ == "__main__":
    test_permission_system()