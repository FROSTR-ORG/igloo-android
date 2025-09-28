#!/usr/bin/env python3
import json
import time
import requests

def test_ipc_signing_bridge():
    """Test the IPC server signing bridge functionality"""

    print("Testing IPC Server Signing Bridge...")

    # Test 1: Ping the server
    try:
        response = requests.get('http://127.0.0.1:45555/ping', timeout=5)
        print(f"✓ Ping test: {response.status_code} - {response.text}")
    except Exception as e:
        print(f"✗ Ping test failed: {e}")
        return False

    # Test 2: Test get_public_key request
    test_request = {
        "type": "get_public_key",
        "id": f"test_{int(time.time())}",
        "params": {},
        "callingApp": "test_app",
        "timestamp": int(time.time() * 1000)
    }

    try:
        response = requests.post(
            'http://127.0.0.1:45555/nip55',
            headers={'Content-Type': 'application/json'},
            data=json.dumps(test_request),
            timeout=10
        )

        print(f"✓ get_public_key test: {response.status_code}")
        print(f"  Response: {response.text}")

        # Try to parse the response
        try:
            result = json.loads(response.text)
            if 'result' in result or 'error' in result:
                print("✓ Response format is valid")
            else:
                print("? Response format might be unexpected")
        except:
            print("? Response is not JSON")

    except Exception as e:
        print(f"✗ get_public_key test failed: {e}")
        return False

    # Test 3: Test sign_event request
    test_event = {
        "kind": 1,
        "content": "Hello from signing bridge test!",
        "tags": [],
        "created_at": int(time.time())
    }

    test_request = {
        "type": "sign_event",
        "id": f"test_sign_{int(time.time())}",
        "params": {
            "event": json.dumps(test_event)
        },
        "callingApp": "test_app",
        "timestamp": int(time.time() * 1000)
    }

    try:
        response = requests.post(
            'http://127.0.0.1:45555/nip55',
            headers={'Content-Type': 'application/json'},
            data=json.dumps(test_request),
            timeout=15
        )

        print(f"✓ sign_event test: {response.status_code}")
        print(f"  Response: {response.text}")

    except Exception as e:
        print(f"✗ sign_event test failed: {e}")
        return False

    print("✓ All IPC signing bridge tests completed!")
    return True

if __name__ == "__main__":
    test_ipc_signing_bridge()