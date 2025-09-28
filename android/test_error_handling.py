#!/usr/bin/env python3
import json
import time
import requests
import threading
from concurrent.futures import ThreadPoolExecutor

def test_error_handling():
    """Test error handling and edge cases for the signing bridge"""
    print("Testing Error Handling and Edge Cases...")

    # Test 1: Server connectivity edge cases
    print("\n1. Testing server connectivity...")

    # Test server is running
    try:
        response = requests.get('http://127.0.0.1:45555/ping', timeout=2)
        print(f"   ✓ Server is running: {response.text}")
    except Exception as e:
        print(f"   ✗ Server connectivity failed: {e}")
        return False

    # Test 2: Malformed HTTP requests
    print("\n2. Testing malformed HTTP requests...")

    test_cases = [
        # Invalid JSON
        ('{"invalid": json}', "Invalid JSON"),
        # Missing required fields
        ('{"type": "get_public_key"}', "Missing required fields"),
        # Empty request
        ('', "Empty request"),
        # Non-JSON content
        ('not json at all', "Non-JSON content"),
        # Very large request
        ('{"type": "get_public_key", "data": "' + 'x' * 10000 + '"}', "Very large request"),
    ]

    for payload, description in test_cases:
        try:
            response = requests.post(
                'http://127.0.0.1:45555/nip55',
                headers={'Content-Type': 'application/json'},
                data=payload,
                timeout=5
            )
            print(f"   {description}: Status {response.status_code}")
            if response.status_code >= 400:
                print(f"      ✓ Properly rejected")
            else:
                print(f"      Response: {response.text[:100]}")
        except Exception as e:
            print(f"   {description}: Exception - {e}")

    # Test 3: Invalid request types and parameters
    print("\n3. Testing invalid request types...")

    invalid_requests = [
        {
            "type": "nonexistent_action",
            "id": "test1",
            "params": {},
            "callingApp": "test",
            "timestamp": int(time.time() * 1000)
        },
        {
            "type": "sign_event",
            "id": "test2",
            "params": {"event": "not_valid_json"},
            "callingApp": "test",
            "timestamp": int(time.time() * 1000)
        },
        {
            "type": "nip04_encrypt",
            "id": "test3",
            "params": {"pubkey": "invalid_pubkey"},  # Missing plaintext
            "callingApp": "test",
            "timestamp": int(time.time() * 1000)
        }
    ]

    for req in invalid_requests:
        try:
            response = requests.post(
                'http://127.0.0.1:45555/nip55',
                headers={'Content-Type': 'application/json'},
                data=json.dumps(req),
                timeout=5
            )
            print(f"   {req['type']}: Status {response.status_code}")
            if "error" in response.text:
                print(f"      ✓ Error properly handled")
            else:
                print(f"      Response: {response.text}")
        except Exception as e:
            print(f"   {req['type']}: Exception - {e}")

    # Test 4: Timeout and concurrent requests
    print("\n4. Testing concurrent requests...")

    def make_request(request_id):
        """Make a single request"""
        test_request = {
            "type": "get_public_key",
            "id": f"concurrent_{request_id}_{int(time.time())}",
            "params": {},
            "callingApp": "concurrent_test",
            "timestamp": int(time.time() * 1000)
        }

        try:
            response = requests.post(
                'http://127.0.0.1:45555/nip55',
                headers={'Content-Type': 'application/json'},
                data=json.dumps(test_request),
                timeout=5
            )
            return f"Request {request_id}: {response.status_code}"
        except Exception as e:
            return f"Request {request_id}: Error - {e}"

    # Make 5 concurrent requests
    with ThreadPoolExecutor(max_workers=5) as executor:
        futures = [executor.submit(make_request, i) for i in range(5)]
        results = [future.result() for future in futures]

    for result in results:
        print(f"   {result}")

    # Test 5: Invalid endpoints
    print("\n5. Testing invalid endpoints...")

    invalid_endpoints = [
        '/nonexistent',
        '/nip55/invalid',
        '/ping/extra',
        '/',
        '/admin'
    ]

    for endpoint in invalid_endpoints:
        try:
            response = requests.get(f'http://127.0.0.1:45555{endpoint}', timeout=3)
            print(f"   {endpoint}: Status {response.status_code}")
        except Exception as e:
            print(f"   {endpoint}: Exception - {e}")

    # Test 6: Very old/future timestamps
    print("\n6. Testing timestamp validation...")

    timestamp_tests = [
        (0, "Zero timestamp"),
        (int(time.time() * 1000) - 24*60*60*1000, "24 hours old"),  # 24 hours ago
        (int(time.time() * 1000) + 24*60*60*1000, "Future timestamp")   # 24 hours future
    ]

    for timestamp, description in timestamp_tests:
        test_request = {
            "type": "get_public_key",
            "id": f"timestamp_test_{timestamp}",
            "params": {},
            "callingApp": "timestamp_test",
            "timestamp": timestamp
        }

        try:
            response = requests.post(
                'http://127.0.0.1:45555/nip55',
                headers={'Content-Type': 'application/json'},
                data=json.dumps(test_request),
                timeout=5
            )
            print(f"   {description}: Status {response.status_code}")
            if response.status_code >= 400:
                print(f"      ✓ Properly rejected")
        except Exception as e:
            print(f"   {description}: Exception - {e}")

    print("\n✓ Error handling and edge case tests completed!")

if __name__ == "__main__":
    test_error_handling()