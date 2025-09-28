#!/usr/bin/env node

/**
 * Polyfill Test Server
 *
 * Serves our polyfill test pages independently from the PWA
 * This allows us to test polyfill functionality without modifying the PWA source.
 */

import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000; // Same port as PWA dev server for app integration

// MIME types
const mimeTypes = {
    '.html': 'text/html',
    '.js': 'application/javascript',
    '.css': 'text/css',
    '.json': 'application/json',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.gif': 'image/gif',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon'
};

// Create test pages dynamically
function createTestPages() {
    const pages = {
        '/': createIndexPage(),
        '/websocket-test': createWebSocketTest(),
        '/storage-test': createStorageTest(),
        '/camera-test': createCameraTest(),
        '/all-tests': createAllTestsPage()
    };
    return pages;
}

function createIndexPage() {
    return `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Polyfill Test Suite</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .test-card { background: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .test-link { display: inline-block; background: #1976d2; color: white; padding: 10px 20px; text-decoration: none; border-radius: 4px; margin: 5px; }
        .test-link:hover { background: #1565c0; }
        .status { padding: 10px; border-radius: 4px; margin: 10px 0; }
        .success { background: #e8f5e8; color: #2e7d2e; }
        .error { background: #ffe8e8; color: #d32f2f; }
        .info { background: #e8f4fd; color: #1976d2; }
    </style>
</head>
<body>
    <h1>🧪 Polyfill Test Suite</h1>
    <p>Test individual polyfills or run all tests together</p>

    <div class="test-card">
        <h2>🔌 WebSocket Polyfill</h2>
        <p>Tests WebSocket API replacement with Android bridge</p>
        <a href="/websocket-test" class="test-link">Test WebSocket</a>
    </div>

    <div class="test-card">
        <h2>🗄️ Storage Polyfill</h2>
        <p>Tests localStorage/sessionStorage with encrypted Android storage</p>
        <a href="/storage-test" class="test-link">Test Storage</a>
    </div>

    <div class="test-card">
        <h2>📷 Camera Polyfill</h2>
        <p>Tests getUserMedia API with Android Camera2 bridge</p>
        <a href="/camera-test" class="test-link">Test Camera</a>
    </div>

    <div class="test-card">
        <h2>🚀 All Tests</h2>
        <p>Comprehensive test of all polyfills</p>
        <a href="/all-tests" class="test-link">Run All Tests</a>
    </div>

    <div class="test-card">
        <h2>📊 Bridge Status</h2>
        <div id="bridge-status">Checking...</div>
    </div>

    <script>
        // Check bridge availability
        function checkBridges() {
            const status = document.getElementById('bridge-status');
            let html = '';

            if (window.WebSocketBridge) {
                html += '<div class="status success">✓ WebSocketBridge: Available</div>';
            } else {
                html += '<div class="status error">✗ WebSocketBridge: Missing</div>';
            }

            if (window.SecureStorageBridge) {
                html += '<div class="status success">✓ SecureStorageBridge: Available</div>';
            } else {
                html += '<div class="status error">✗ SecureStorageBridge: Missing</div>';
            }

            if (window.CameraBridge) {
                html += '<div class="status success">✓ CameraBridge: Available</div>';
            } else {
                html += '<div class="status error">✗ CameraBridge: Missing</div>';
            }

            if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                html += '<div class="status success">✓ getUserMedia: Available (via polyfill)</div>';
            } else {
                html += '<div class="status error">✗ getUserMedia: Missing</div>';
            }

            if (window.localStorage && window.sessionStorage) {
                html += '<div class="status success">✓ Storage APIs: Available (via polyfill)</div>';
            } else {
                html += '<div class="status error">✗ Storage APIs: Missing</div>';
            }

            if (window.WebSocket) {
                html += '<div class="status success">✓ WebSocket: Available (via polyfill)</div>';
            } else {
                html += '<div class="status error">✗ WebSocket: Missing</div>';
            }

            status.innerHTML = html;
        }

        setTimeout(checkBridges, 1000);
    </script>
</body>
</html>`;
}

function createWebSocketTest() {
    return `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>WebSocket Polyfill Test</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .test-section { background: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        button { background: #1976d2; color: white; border: none; padding: 10px 20px; margin: 5px; border-radius: 4px; cursor: pointer; }
        button:hover { background: #1565c0; }
        .log { background: #f8f8f8; border: 1px solid #ddd; padding: 10px; margin: 10px 0; border-radius: 4px; font-family: monospace; font-size: 12px; max-height: 300px; overflow-y: auto; }
        .status { padding: 10px; border-radius: 4px; margin: 10px 0; }
        .success { background: #e8f5e8; color: #2e7d2e; }
        .error { background: #ffe8e8; color: #d32f2f; }
        .info { background: #e8f4fd; color: #1976d2; }
    </style>
</head>
<body>
    <h1><a href="/">←</a> 🔌 WebSocket Polyfill Test</h1>

    <div class="test-section">
        <h2>WebSocket API Tests</h2>
        <div id="ws-status" class="status info">Ready to test...</div>

        <button onclick="testWebSocketAPI()">Test WebSocket API</button>
        <button onclick="testWebSocketConnection()">Test Connection</button>
        <button onclick="testWebSocketEcho()">Test Echo Server</button>
        <button onclick="testWebSocketEvents()">Test Events</button>

        <div id="ws-log" class="log"></div>
    </div>

    <script>
        let currentWS = null;

        function log(message) {
            const logEl = document.getElementById('ws-log');
            const timestamp = new Date().toLocaleTimeString();
            logEl.innerHTML += \`<div>[\${timestamp}] \${message}</div>\`;
            logEl.scrollTop = logEl.scrollHeight;
            console.log(message);
        }

        function setStatus(message, type = 'info') {
            const statusEl = document.getElementById('ws-status');
            statusEl.textContent = message;
            statusEl.className = \`status \${type}\`;
        }

        function testWebSocketAPI() {
            log('Testing WebSocket API availability...');

            if (typeof WebSocket === 'undefined') {
                log('✗ WebSocket is not defined');
                setStatus('WebSocket API not available', 'error');
                return;
            }

            log('✓ WebSocket constructor available');

            // Test WebSocket constants
            const constants = ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'];
            constants.forEach(constant => {
                if (typeof WebSocket[constant] === 'number') {
                    log(\`✓ WebSocket.\${constant} = \${WebSocket[constant]}\`);
                } else {
                    log(\`✗ WebSocket.\${constant} missing\`);
                }
            });

            // Test bridge availability
            if (window.WebSocketBridge) {
                log('✓ WebSocketBridge available');
                const methods = ['createWebSocket', 'sendMessage', 'closeWebSocket', 'getConnectionState'];
                methods.forEach(method => {
                    if (typeof window.WebSocketBridge[method] === 'function') {
                        log(\`✓ WebSocketBridge.\${method} available\`);
                    } else {
                        log(\`✗ WebSocketBridge.\${method} missing\`);
                    }
                });
            } else {
                log('✗ WebSocketBridge not available');
            }

            setStatus('WebSocket API test completed', 'success');
        }

        function testWebSocketConnection() {
            log('Testing WebSocket connection...');
            setStatus('Creating WebSocket connection...', 'info');

            try {
                const ws = new WebSocket('wss://echo.websocket.org');
                currentWS = ws;

                log(\`✓ WebSocket created: readyState = \${ws.readyState}\`);

                ws.onopen = function(event) {
                    log('✓ WebSocket opened');
                    setStatus('WebSocket connected', 'success');
                };

                ws.onerror = function(event) {
                    log('✗ WebSocket error occurred');
                    setStatus('WebSocket connection failed', 'error');
                };

                ws.onclose = function(event) {
                    log(\`✓ WebSocket closed (code: \${event.code})\`);
                };

            } catch (error) {
                log(\`✗ WebSocket creation failed: \${error.message}\`);
                setStatus('WebSocket creation failed', 'error');
            }
        }

        function testWebSocketEcho() {
            if (!currentWS || currentWS.readyState !== WebSocket.OPEN) {
                log('Creating new WebSocket for echo test...');
                testWebSocketConnection();

                // Wait for connection
                setTimeout(() => {
                    if (currentWS && currentWS.readyState === WebSocket.OPEN) {
                        performEchoTest();
                    } else {
                        log('✗ WebSocket not ready for echo test');
                    }
                }, 2000);
            } else {
                performEchoTest();
            }
        }

        function performEchoTest() {
            log('Testing WebSocket echo...');
            const testMessage = 'Hello from polyfill test!';

            currentWS.onmessage = function(event) {
                log(\`✓ Received echo: \${event.data}\`);
                if (event.data === testMessage) {
                    log('✓ Echo test passed!');
                    setStatus('Echo test successful', 'success');
                } else {
                    log('✗ Echo data mismatch');
                    setStatus('Echo test failed', 'error');
                }
            };

            currentWS.send(testMessage);
            log(\`→ Sent: \${testMessage}\`);
        }

        function testWebSocketEvents() {
            log('Testing WebSocket event handling...');

            try {
                const ws = new WebSocket('wss://echo.websocket.org');

                const events = ['open', 'message', 'close', 'error'];
                events.forEach(eventType => {
                    ws.addEventListener(eventType, function(event) {
                        log(\`✓ Event listener triggered: \${eventType}\`);
                    });
                });

                log('✓ Event listeners attached');
                setStatus('Event test setup complete', 'success');

            } catch (error) {
                log(\`✗ Event test failed: \${error.message}\`);
                setStatus('Event test failed', 'error');
            }
        }

        // Initialize
        setTimeout(() => {
            log('WebSocket polyfill test page loaded');
            testWebSocketAPI();
        }, 500);
    </script>
</body>
</html>`;
}

function createStorageTest() {
    return `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Storage Polyfill Test</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .test-section { background: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        button { background: #1976d2; color: white; border: none; padding: 10px 20px; margin: 5px; border-radius: 4px; cursor: pointer; }
        button:hover { background: #1565c0; }
        .log { background: #f8f8f8; border: 1px solid #ddd; padding: 10px; margin: 10px 0; border-radius: 4px; font-family: monospace; font-size: 12px; max-height: 300px; overflow-y: auto; }
        .status { padding: 10px; border-radius: 4px; margin: 10px 0; }
        .success { background: #e8f5e8; color: #2e7d2e; }
        .error { background: #ffe8e8; color: #d32f2f; }
        .info { background: #e8f4fd; color: #1976d2; }
    </style>
</head>
<body>
    <h1><a href="/">←</a> 🗄️ Storage Polyfill Test</h1>

    <div class="test-section">
        <h2>Storage API Tests</h2>
        <div id="storage-status" class="status info">Ready to test...</div>

        <button onclick="testStorageAPI()">Test Storage API</button>
        <button onclick="testLocalStorage()">Test localStorage</button>
        <button onclick="testSessionStorage()">Test sessionStorage</button>
        <button onclick="testStorageEvents()">Test Events</button>
        <button onclick="clearAllStorage()">Clear All</button>

        <div id="storage-log" class="log"></div>
    </div>

    <script>
        function log(message) {
            const logEl = document.getElementById('storage-log');
            const timestamp = new Date().toLocaleTimeString();
            logEl.innerHTML += \`<div>[\${timestamp}] \${message}</div>\`;
            logEl.scrollTop = logEl.scrollHeight;
            console.log(message);
        }

        function setStatus(message, type = 'info') {
            const statusEl = document.getElementById('storage-status');
            statusEl.textContent = message;
            statusEl.className = \`status \${type}\`;
        }

        function testStorageAPI() {
            log('Testing Storage API availability...');

            if (typeof localStorage === 'undefined') {
                log('✗ localStorage is not defined');
                setStatus('localStorage not available', 'error');
                return;
            }

            if (typeof sessionStorage === 'undefined') {
                log('✗ sessionStorage is not defined');
                setStatus('sessionStorage not available', 'error');
                return;
            }

            log('✓ localStorage and sessionStorage available');

            // Test Storage interface methods
            const requiredMethods = ['getItem', 'setItem', 'removeItem', 'clear', 'key'];
            requiredMethods.forEach(method => {
                if (typeof localStorage[method] === 'function') {
                    log(\`✓ localStorage.\${method} available\`);
                } else {
                    log(\`✗ localStorage.\${method} missing\`);
                }

                if (typeof sessionStorage[method] === 'function') {
                    log(\`✓ sessionStorage.\${method} available\`);
                } else {
                    log(\`✗ sessionStorage.\${method} missing\`);
                }
            });

            // Test length property
            if (typeof localStorage.length === 'number') {
                log(\`✓ localStorage.length = \${localStorage.length}\`);
            } else {
                log('✗ localStorage.length missing or invalid');
            }

            // Test bridge availability
            if (window.SecureStorageBridge) {
                log('✓ SecureStorageBridge available');
                const methods = ['getItem', 'setItem', 'removeItem', 'clear', 'key', 'length'];
                methods.forEach(method => {
                    if (typeof window.SecureStorageBridge[method] === 'function') {
                        log(\`✓ SecureStorageBridge.\${method} available\`);
                    } else {
                        log(\`✗ SecureStorageBridge.\${method} missing\`);
                    }
                });
            } else {
                log('✗ SecureStorageBridge not available');
            }

            setStatus('Storage API test completed', 'success');
        }

        function testLocalStorage() {
            log('Testing localStorage operations...');
            setStatus('Testing localStorage...', 'info');

            try {
                const testKey = 'test_key_' + Date.now();
                const testValue = 'test_value_' + Math.random();

                // Test setItem
                localStorage.setItem(testKey, testValue);
                log(\`✓ setItem: \${testKey} = \${testValue}\`);

                // Test getItem
                const retrieved = localStorage.getItem(testKey);
                if (retrieved === testValue) {
                    log('✓ getItem: Retrieved correct value');
                } else {
                    throw new Error(\`getItem mismatch: expected \${testValue}, got \${retrieved}\`);
                }

                // Test length
                const length = localStorage.length;
                log(\`✓ length: \${length} items in localStorage\`);

                // Test key()
                const keyAtIndex = localStorage.key(0);
                log(\`✓ key(0): \${keyAtIndex}\`);

                // Test removeItem
                localStorage.removeItem(testKey);
                const afterRemove = localStorage.getItem(testKey);
                if (afterRemove === null) {
                    log('✓ removeItem: Item successfully removed');
                } else {
                    throw new Error('removeItem failed: item still exists');
                }

                setStatus('localStorage test passed!', 'success');

            } catch (error) {
                log(\`✗ localStorage test failed: \${error.message}\`);
                setStatus('localStorage test failed!', 'error');
            }
        }

        function testSessionStorage() {
            log('Testing sessionStorage operations...');
            setStatus('Testing sessionStorage...', 'info');

            try {
                const testKey = 'session_test_' + Date.now();
                const testValue = 'session_value_' + Math.random();

                sessionStorage.setItem(testKey, testValue);
                log(\`✓ sessionStorage.setItem: \${testKey} = \${testValue}\`);

                const retrieved = sessionStorage.getItem(testKey);
                if (retrieved === testValue) {
                    log('✓ sessionStorage.getItem: Retrieved correct value');
                } else {
                    throw new Error('sessionStorage getItem mismatch');
                }

                sessionStorage.removeItem(testKey);
                log('✓ sessionStorage.removeItem: Item removed');

                setStatus('sessionStorage test passed!', 'success');

            } catch (error) {
                log(\`✗ sessionStorage test failed: \${error.message}\`);
                setStatus('sessionStorage test failed!', 'error');
            }
        }

        function testStorageEvents() {
            log('Testing storage events...');

            try {
                let eventReceived = false;
                const testKey = 'event_test_' + Date.now();
                const testValue = 'event_value';

                const eventHandler = (event) => {
                    log(\`✓ Storage event received: key=\${event.key}, newValue=\${event.newValue}\`);
                    eventReceived = true;
                };

                window.addEventListener('storage', eventHandler);

                localStorage.setItem(testKey, testValue);

                setTimeout(() => {
                    window.removeEventListener('storage', eventHandler);
                    localStorage.removeItem(testKey);

                    if (eventReceived) {
                        log('✓ Storage events working correctly');
                        setStatus('Storage events test passed!', 'success');
                    } else {
                        log('⚠ Storage event not received (may be normal in single-tab scenario)');
                        setStatus('Storage events test completed', 'info');
                    }
                }, 100);

            } catch (error) {
                log(\`✗ Storage event test failed: \${error.message}\`);
                setStatus('Storage events test failed!', 'error');
            }
        }

        function clearAllStorage() {
            try {
                localStorage.clear();
                sessionStorage.clear();
                log('✓ All storage cleared');
                setStatus('Storage cleared', 'success');
            } catch (error) {
                log(\`✗ Clear storage failed: \${error.message}\`);
                setStatus('Clear storage failed', 'error');
            }
        }

        // Initialize
        setTimeout(() => {
            log('Storage polyfill test page loaded');
            testStorageAPI();
        }, 500);
    </script>
</body>
</html>`;
}

function createCameraTest() {
    return `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Camera Polyfill Test</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .test-section { background: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        button { background: #1976d2; color: white; border: none; padding: 10px 20px; margin: 5px; border-radius: 4px; cursor: pointer; }
        button:hover { background: #1565c0; }
        .log { background: #f8f8f8; border: 1px solid #ddd; padding: 10px; margin: 10px 0; border-radius: 4px; font-family: monospace; font-size: 12px; max-height: 300px; overflow-y: auto; }
        .status { padding: 10px; border-radius: 4px; margin: 10px 0; }
        .success { background: #e8f5e8; color: #2e7d2e; }
        .error { background: #ffe8e8; color: #d32f2f; }
        .info { background: #e8f4fd; color: #1976d2; }
        video { max-width: 100%; height: auto; border: 2px solid #ddd; border-radius: 8px; margin: 10px 0; }
    </style>
</head>
<body>
    <h1><a href="/">←</a> 📷 Camera Polyfill Test</h1>

    <div class="test-section">
        <h2>Camera API Tests</h2>
        <div id="camera-status" class="status info">Ready to test...</div>

        <button onclick="testCameraAPI()">Test Camera API</button>
        <button onclick="testEnumerateDevices()">Enumerate Devices</button>
        <button onclick="testGetUserMedia()">Get User Media</button>
        <button onclick="stopCamera()">Stop Camera</button>

        <video id="camera-video" autoplay muted playsinline style="display: none;"></video>

        <div id="camera-log" class="log"></div>
    </div>

    <script>
        let currentStream = null;

        function log(message) {
            const logEl = document.getElementById('camera-log');
            const timestamp = new Date().toLocaleTimeString();
            logEl.innerHTML += \`<div>[\${timestamp}] \${message}</div>\`;
            logEl.scrollTop = logEl.scrollHeight;
            console.log(message);
        }

        function setStatus(message, type = 'info') {
            const statusEl = document.getElementById('camera-status');
            statusEl.textContent = message;
            statusEl.className = \`status \${type}\`;
        }

        function testCameraAPI() {
            log('Testing Camera API availability...');

            if (!navigator.mediaDevices) {
                log('✗ navigator.mediaDevices is not defined');
                setStatus('MediaDevices API not available', 'error');
                return;
            }

            log('✓ navigator.mediaDevices available');

            if (!navigator.mediaDevices.getUserMedia) {
                log('✗ getUserMedia is not defined');
                setStatus('getUserMedia not available', 'error');
                return;
            }

            log('✓ navigator.mediaDevices.getUserMedia available');

            if (!navigator.mediaDevices.enumerateDevices) {
                log('✗ enumerateDevices is not defined');
            } else {
                log('✓ navigator.mediaDevices.enumerateDevices available');
            }

            // Test bridge availability
            if (window.CameraBridge) {
                log('✓ CameraBridge available');
                const methods = ['getUserMedia', 'enumerateDevices', 'stopUserMedia', 'captureFrame', 'getCapabilities'];
                methods.forEach(method => {
                    if (typeof window.CameraBridge[method] === 'function') {
                        log(\`✓ CameraBridge.\${method} available\`);
                    } else {
                        log(\`✗ CameraBridge.\${method} missing\`);
                    }
                });
            } else {
                log('✗ CameraBridge not available');
            }

            setStatus('Camera API test completed', 'success');
        }

        async function testEnumerateDevices() {
            log('Testing device enumeration...');
            setStatus('Enumerating devices...', 'info');

            try {
                const devices = await navigator.mediaDevices.enumerateDevices();
                log(\`✓ Found \${devices.length} total devices\`);

                const videoDevices = devices.filter(device => device.kind === 'videoinput');
                log(\`✓ Found \${videoDevices.length} camera devices\`);

                videoDevices.forEach((device, index) => {
                    log(\`  Camera \${index + 1}: \${device.label || 'Unnamed'} (ID: \${device.deviceId})\`);
                });

                setStatus(\`Found \${videoDevices.length} camera devices\`, 'success');

            } catch (error) {
                log(\`✗ Device enumeration failed: \${error.message}\`);
                setStatus('Device enumeration failed', 'error');
            }
        }

        async function testGetUserMedia() {
            log('Testing getUserMedia...');
            setStatus('Requesting camera access...', 'info');

            try {
                const constraints = {
                    video: {
                        width: { ideal: 1280 },
                        height: { ideal: 720 }
                    }
                };

                const stream = await navigator.mediaDevices.getUserMedia(constraints);
                log(\`✓ Camera stream created: \${stream.id}\`);

                currentStream = stream;

                const video = document.getElementById('camera-video');
                video.srcObject = stream;
                video.style.display = 'block';

                log(\`✓ Video element connected to stream\`);
                log(\`✓ Video tracks: \${stream.getVideoTracks().length}\`);

                stream.getVideoTracks().forEach((track, index) => {
                    log(\`  Track \${index}: \${track.label} (enabled: \${track.enabled})\`);
                });

                setStatus('Camera access successful!', 'success');

            } catch (error) {
                log(\`✗ getUserMedia failed: \${error.message}\`);
                setStatus('Camera access failed!', 'error');
            }
        }

        function stopCamera() {
            log('Stopping camera...');

            try {
                if (currentStream) {
                    currentStream.getTracks().forEach(track => {
                        track.stop();
                        log(\`✓ Stopped track: \${track.kind}\`);
                    });

                    const video = document.getElementById('camera-video');
                    video.srcObject = null;
                    video.style.display = 'none';

                    currentStream = null;

                    log('✓ Camera stopped');
                    setStatus('Camera stopped', 'info');
                } else {
                    log('⚠ No active stream to stop');
                }

            } catch (error) {
                log(\`✗ Failed to stop camera: \${error.message}\`);
                setStatus('Stop camera failed', 'error');
            }
        }

        // Initialize
        setTimeout(() => {
            log('Camera polyfill test page loaded');
            testCameraAPI();
        }, 500);
    </script>
</body>
</html>`;
}

function createAllTestsPage() {
    return `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>All Polyfill Tests</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .test-section { background: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        button { background: #1976d2; color: white; border: none; padding: 10px 20px; margin: 5px; border-radius: 4px; cursor: pointer; }
        button:hover { background: #1565c0; }
        .log { background: #f8f8f8; border: 1px solid #ddd; padding: 10px; margin: 10px 0; border-radius: 4px; font-family: monospace; font-size: 12px; max-height: 200px; overflow-y: auto; }
        .status { padding: 10px; border-radius: 4px; margin: 10px 0; }
        .success { background: #e8f5e8; color: #2e7d2e; }
        .error { background: #ffe8e8; color: #d32f2f; }
        .info { background: #e8f4fd; color: #1976d2; }
        .summary { background: #f0f0f0; padding: 15px; border-radius: 8px; margin: 20px 0; }
    </style>
</head>
<body>
    <h1><a href="/">←</a> 🚀 All Polyfill Tests</h1>

    <div class="summary" id="test-summary">
        <h2>Test Summary</h2>
        <div>Tests: <span id="test-count">0</span> | Passed: <span id="test-passed">0</span> | Failed: <span id="test-failed">0</span></div>
    </div>

    <button onclick="runAllTests()">Run All Tests</button>
    <button onclick="clearResults()">Clear Results</button>

    <div class="test-section">
        <h2>🔌 WebSocket Tests</h2>
        <div id="ws-status" class="status info">Waiting...</div>
        <div id="ws-log" class="log"></div>
    </div>

    <div class="test-section">
        <h2>🗄️ Storage Tests</h2>
        <div id="storage-status" class="status info">Waiting...</div>
        <div id="storage-log" class="log"></div>
    </div>

    <div class="test-section">
        <h2>📷 Camera Tests</h2>
        <div id="camera-status" class="status info">Waiting...</div>
        <div id="camera-log" class="log"></div>
    </div>

    <script>
        let testResults = {
            total: 0,
            passed: 0,
            failed: 0
        };

        function log(section, message) {
            const logEl = document.getElementById(section + '-log');
            const timestamp = new Date().toLocaleTimeString();
            logEl.innerHTML += \`<div>[\${timestamp}] \${message}</div>\`;
            logEl.scrollTop = logEl.scrollHeight;
            console.log(\`[\${section}] \${message}\`);
        }

        function setStatus(section, message, type = 'info') {
            const statusEl = document.getElementById(section + '-status');
            statusEl.textContent = message;
            statusEl.className = \`status \${type}\`;
        }

        function updateSummary() {
            document.getElementById('test-count').textContent = testResults.total;
            document.getElementById('test-passed').textContent = testResults.passed;
            document.getElementById('test-failed').textContent = testResults.failed;
        }

        function addTestResult(passed) {
            testResults.total++;
            if (passed) {
                testResults.passed++;
            } else {
                testResults.failed++;
            }
            updateSummary();
        }

        async function runAllTests() {
            log('ws', 'Starting comprehensive polyfill test suite...');
            testResults = { total: 0, passed: 0, failed: 0 };

            // Test WebSocket
            await testWebSocket();

            // Test Storage
            await testStorage();

            // Test Camera
            await testCamera();

            // Final summary
            const passRate = (testResults.passed / testResults.total * 100).toFixed(1);
            log('ws', \`=== FINAL RESULTS ===\`);
            log('ws', \`Total tests: \${testResults.total}\`);
            log('ws', \`Passed: \${testResults.passed}\`);
            log('ws', \`Failed: \${testResults.failed}\`);
            log('ws', \`Pass rate: \${passRate}%\`);
        }

        async function testWebSocket() {
            log('ws', 'Testing WebSocket polyfill...');
            setStatus('ws', 'Testing WebSocket...', 'info');

            try {
                // Test API availability
                if (typeof WebSocket !== 'undefined') {
                    log('ws', '✓ WebSocket API available');
                    addTestResult(true);
                } else {
                    log('ws', '✗ WebSocket API missing');
                    addTestResult(false);
                }

                // Test bridge
                if (window.WebSocketBridge) {
                    log('ws', '✓ WebSocketBridge available');
                    addTestResult(true);
                } else {
                    log('ws', '✗ WebSocketBridge missing');
                    addTestResult(false);
                }

                setStatus('ws', 'WebSocket tests completed', 'success');

            } catch (error) {
                log('ws', \`✗ WebSocket test failed: \${error.message}\`);
                setStatus('ws', 'WebSocket tests failed', 'error');
                addTestResult(false);
            }
        }

        async function testStorage() {
            log('storage', 'Testing Storage polyfill...');
            setStatus('storage', 'Testing Storage...', 'info');

            try {
                // Test localStorage
                if (typeof localStorage !== 'undefined') {
                    log('storage', '✓ localStorage API available');
                    addTestResult(true);

                    // Test basic operations
                    const testKey = 'test_' + Date.now();
                    const testValue = 'test_value';

                    localStorage.setItem(testKey, testValue);
                    const retrieved = localStorage.getItem(testKey);
                    localStorage.removeItem(testKey);

                    if (retrieved === testValue) {
                        log('storage', '✓ localStorage operations working');
                        addTestResult(true);
                    } else {
                        log('storage', '✗ localStorage operations failed');
                        addTestResult(false);
                    }
                } else {
                    log('storage', '✗ localStorage API missing');
                    addTestResult(false);
                }

                // Test sessionStorage
                if (typeof sessionStorage !== 'undefined') {
                    log('storage', '✓ sessionStorage API available');
                    addTestResult(true);
                } else {
                    log('storage', '✗ sessionStorage API missing');
                    addTestResult(false);
                }

                // Test bridge
                if (window.SecureStorageBridge) {
                    log('storage', '✓ SecureStorageBridge available');
                    addTestResult(true);
                } else {
                    log('storage', '✗ SecureStorageBridge missing');
                    addTestResult(false);
                }

                setStatus('storage', 'Storage tests completed', 'success');

            } catch (error) {
                log('storage', \`✗ Storage test failed: \${error.message}\`);
                setStatus('storage', 'Storage tests failed', 'error');
                addTestResult(false);
            }
        }

        async function testCamera() {
            log('camera', 'Testing Camera polyfill...');
            setStatus('camera', 'Testing Camera...', 'info');

            try {
                // Test MediaDevices API
                if (navigator.mediaDevices) {
                    log('camera', '✓ navigator.mediaDevices available');
                    addTestResult(true);
                } else {
                    log('camera', '✗ navigator.mediaDevices missing');
                    addTestResult(false);
                }

                // Test getUserMedia
                if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                    log('camera', '✓ getUserMedia API available');
                    addTestResult(true);
                } else {
                    log('camera', '✗ getUserMedia API missing');
                    addTestResult(false);
                }

                // Test enumerateDevices
                if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
                    log('camera', '✓ enumerateDevices API available');
                    addTestResult(true);
                } else {
                    log('camera', '✗ enumerateDevices API missing');
                    addTestResult(false);
                }

                // Test bridge
                if (window.CameraBridge) {
                    log('camera', '✓ CameraBridge available');
                    addTestResult(true);
                } else {
                    log('camera', '✗ CameraBridge missing');
                    addTestResult(false);
                }

                setStatus('camera', 'Camera tests completed', 'success');

            } catch (error) {
                log('camera', \`✗ Camera test failed: \${error.message}\`);
                setStatus('camera', 'Camera tests failed', 'error');
                addTestResult(false);
            }
        }

        function clearResults() {
            ['ws', 'storage', 'camera'].forEach(section => {
                document.getElementById(section + '-log').innerHTML = '';
                setStatus(section, 'Waiting...', 'info');
            });
            testResults = { total: 0, passed: 0, failed: 0 };
            updateSummary();
        }

        // Initialize
        setTimeout(() => {
            log('ws', 'All tests page loaded and ready');
        }, 500);
    </script>
</body>
</html>`;
}

const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const pathname = url.pathname;

    // Set CORS headers
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }

    const pages = createTestPages();

    if (pages[pathname]) {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(pages[pathname]);
    } else {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('Page not found');
    }
});

server.listen(PORT, () => {
    console.log(`\n🧪 Polyfill Test Server running on http://localhost:${PORT}`);
    console.log(`\nAvailable test pages:`);
    console.log(`  📋 All Tests: http://localhost:${PORT}/`);
    console.log(`  🔌 WebSocket: http://localhost:${PORT}/websocket-test`);
    console.log(`  🗄️ Storage:   http://localhost:${PORT}/storage-test`);
    console.log(`  📷 Camera:    http://localhost:${PORT}/camera-test`);
    console.log(`  🚀 All:       http://localhost:${PORT}/all-tests`);
    console.log(`\nTo test with Android app, navigate to these URLs in the WebView.`);
});