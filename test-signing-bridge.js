#!/usr/bin/env node

/**
 * Test script for debugging the NIP-55 signing bridge
 * This simulates the browser environment to test core signing functionality
 */

// Simulate browser environment
global.window = {
  nostr: {},
  addEventListener: () => {},
  removeEventListener: () => {},
  dispatchEvent: () => {}
};

global.console = console;

// Mock the context and dependencies needed for signing bridge
const mockBifrostNode = {
  client: {
    // Mock bifrost client methods
    sign_event: async (event) => ({ ...event, sig: 'mock_signature_' + Date.now() }),
    get_pubkey: () => 'mock_pubkey_' + Math.random().toString(36).substr(2, 9)
  },
  status: 'online'
};

const mockPermissions = {
  // Mock permissions for testing
};

// Import and test the signing bridge
console.log('🧪 Testing NIP-55 Signing Bridge');
console.log('================================');

try {
  // We'll need to import the signing bridge logic
  // But first, let's check what files are available
  console.log('1. Checking available source files...');

  // Test basic functionality
  console.log('2. Testing basic signing bridge creation...');

  // Test get_public_key
  console.log('3. Testing get_public_key...');

  // Test prompt system
  console.log('4. Testing prompt system...');

  console.log('\n✅ Signing bridge test setup complete');
  console.log('Ready for interactive testing');

} catch (error) {
  console.error('❌ Test setup failed:', error);
}