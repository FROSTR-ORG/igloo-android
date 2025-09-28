#!/usr/bin/env node

const http = require('http');

// Function to send DevTools protocol message to a specific page
async function sendDevToolsMessage(method, params = {}, pageId = null) {
  // Get page ID if not provided
  if (!pageId) {
    const pagesResponse = await new Promise((resolve, reject) => {
      http.get('http://localhost:9222/json', (res) => {
        let data = '';
        res.on('data', (chunk) => data += chunk);
        res.on('end', () => {
          try {
            resolve(JSON.parse(data));
          } catch (err) {
            reject(err);
          }
        });
      }).on('error', reject);
    });

    const iglooPage = pagesResponse.find(page => page.title === 'Igloo PWA');
    if (!iglooPage) {
      throw new Error('Igloo PWA page not found');
    }
    pageId = iglooPage.id;
  }

  const postData = JSON.stringify({
    id: Date.now(),
    method,
    params
  });

  return new Promise((resolve, reject) => {
    const req = http.request({
      hostname: 'localhost',
      port: 9222,
      path: `/json/runtime/evaluate`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData),
        'Target': pageId
      }
    }, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (err) {
          reject(err);
        }
      });
    });

    req.on('error', reject);
    req.write(postData);
    req.end();
  });
}

// Test the signing bridge
async function testSigningBridge() {
  console.log('🧪 Testing NIP-55 Signing Bridge in WebView');
  console.log('===========================================');

  try {
    // Test 1: Check if signing bridge is available
    console.log('1. Checking signing bridge availability...');
    const availabilityTest = await sendDevToolsMessage('Runtime.evaluate', {
      expression: 'JSON.stringify({nostr: !!window.nostr, nip55: !!window.nostr?.nip55, nostrKeys: Object.keys(window.nostr || {})})'
    });

    if (availabilityTest.result && availabilityTest.result.value) {
      const result = JSON.parse(availabilityTest.result.value);
      console.log('✅ Availability test result:', result);

      if (result.nip55) {
        // Test 2: Try get_public_key
        console.log('\\n2. Testing get_public_key function...');
        const getPubkeyTest = await sendDevToolsMessage('Runtime.evaluate', {
          expression: `
            (async () => {
              try {
                const request = { type: 'get_public_key', id: 'test-${Date.now()}' };
                console.log('Sending request:', request);
                const result = await window.nostr.nip55(request);
                return JSON.stringify({ success: true, result });
              } catch (error) {
                return JSON.stringify({ success: false, error: error.message, stack: error.stack });
              }
            })()
          `,
          awaitPromise: true
        });

        if (getPubkeyTest.result && getPubkeyTest.result.value) {
          const testResult = JSON.parse(getPubkeyTest.result.value);
          if (testResult.success) {
            console.log('✅ get_public_key SUCCESS:', testResult.result);

            // Test 3: Test prompt system with sign_event
            console.log('\\n3. Testing sign_event with prompt system...');
            const signEventTest = await sendDevToolsMessage('Runtime.evaluate', {
              expression: `
                (async () => {
                  try {
                    const testEvent = {
                      kind: 1,
                      content: 'Test event for NIP-55 signing bridge',
                      tags: [],
                      created_at: Math.floor(Date.now() / 1000)
                    };
                    const request = {
                      type: 'sign_event',
                      id: 'test-sign-${Date.now()}',
                      event: JSON.stringify(testEvent)
                    };
                    console.log('Sending sign_event request:', request);
                    const result = await window.nostr.nip55(request);
                    return JSON.stringify({ success: true, result });
                  } catch (error) {
                    return JSON.stringify({ success: false, error: error.message, stack: error.stack });
                  }
                })()
              `,
              awaitPromise: true
            });

            if (signEventTest.result && signEventTest.result.value) {
              const signResult = JSON.parse(signEventTest.result.value);
              if (signResult.success) {
                console.log('✅ sign_event SUCCESS:', signResult.result);
              } else {
                console.log('❌ sign_event ERROR:', signResult.error);
              }
            }
          } else {
            console.log('❌ get_public_key ERROR:', testResult.error);
            if (testResult.stack) {
              console.log('Stack trace:', testResult.stack);
            }
          }
        }
      } else {
        console.log('❌ NIP-55 signing bridge not available');

        // Additional debug info
        console.log('\\n🔍 Debug: Checking app state...');
        const debugTest = await sendDevToolsMessage('Runtime.evaluate', {
          expression: `
            JSON.stringify({
              location: window.location.href,
              readyState: document.readyState,
              hasReact: typeof React !== 'undefined',
              customEvents: window.addEventListener ? 'available' : 'not available',
              nodeContext: typeof window.nodeContext !== 'undefined',
              errors: window.lastError || 'none'
            })
          `
        });

        if (debugTest.result && debugTest.result.value) {
          const debugInfo = JSON.parse(debugTest.result.value);
          console.log('Debug info:', debugInfo);
        }
      }
    }

  } catch (error) {
    console.error('❌ Test failed:', error.message);
  }
}

testSigningBridge();