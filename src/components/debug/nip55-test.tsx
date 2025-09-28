import { useState } from 'react'
import { usePrompt } from '@/context/prompt.js'

import type { ReactElement } from 'react'
import type { NIP55Request } from '@/types/index.js'

/**
 * NIP-55 Debug Test Component
 *
 * Allows testing the permission system and prompts directly in the PWA
 */
export function NIP55TestPage(): ReactElement {
  const prompt = usePrompt()
  const [testHost, setTestHost] = useState('com.test.debug')
  const [requestType, setRequestType] = useState<string>('get_public_key')
  const [loading, setLoading] = useState(false)

  const triggerPrompt = () => {
    setLoading(true)

    const testRequest: NIP55Request = {
      type: requestType as any,
      id: `debug_test_${Date.now()}`,
      host: testHost
    }

    // Add type-specific fields
    if (requestType === 'sign_event') {
      (testRequest as any).event = {
        kind: 1,
        content: 'This is a test event for debugging',
        tags: [],
        created_at: Math.floor(Date.now() / 1000)
      }
    } else if (requestType.includes('encrypt')) {
      (testRequest as any).plaintext = 'Test message to encrypt'
      ;(testRequest as any).pubkey = '0000000000000000000000000000000000000000000000000000000000000000'
    } else if (requestType.includes('decrypt')) {
      (testRequest as any).ciphertext = 'test_encrypted_content'
      ;(testRequest as any).pubkey = '0000000000000000000000000000000000000000000000000000000000000000'
    }

    console.log('Triggering debug prompt with request:', testRequest)
    prompt.showPrompt(testRequest)
    setLoading(false)
  }

  const clearPermissions = () => {
    localStorage.removeItem('nip55_permissions')
    console.log('Cleared all permissions')
    alert('All permissions cleared')
  }

  const showStoredPermissions = () => {
    const stored = localStorage.getItem('nip55_permissions')
    console.log('Stored permissions:', stored)
    alert(`Stored permissions: ${stored || 'None'}`)
  }

  return (
    <div className="debug-page">
      <div className="debug-header">
        <h2>NIP-55 Debug Test Page</h2>
        <p>Test the permission system and prompts directly</p>
      </div>

      <div className="debug-controls">
        <div className="form-group">
          <label htmlFor="test-host">Test Host (App ID):</label>
          <input
            id="test-host"
            type="text"
            value={testHost}
            onChange={(e) => setTestHost(e.target.value)}
            placeholder="com.test.debug"
          />
        </div>

        <div className="form-group">
          <label htmlFor="request-type">Request Type:</label>
          <select
            id="request-type"
            value={requestType}
            onChange={(e) => setRequestType(e.target.value)}
          >
            <option value="get_public_key">Get Public Key</option>
            <option value="sign_event">Sign Event</option>
            <option value="nip04_encrypt">NIP-04 Encrypt</option>
            <option value="nip04_decrypt">NIP-04 Decrypt</option>
            <option value="nip44_encrypt">NIP-44 Encrypt</option>
            <option value="nip44_decrypt">NIP-44 Decrypt</option>
          </select>
        </div>

        <div className="button-group">
          <button
            onClick={triggerPrompt}
            disabled={loading}
            className="button button-primary"
          >
            {loading ? 'Loading...' : 'Trigger Prompt'}
          </button>

          <button
            onClick={showStoredPermissions}
            className="button"
          >
            Show Stored Permissions
          </button>

          <button
            onClick={clearPermissions}
            className="button button-remove"
          >
            Clear All Permissions
          </button>
        </div>
      </div>

      <div className="debug-info">
        <h3>Instructions:</h3>
        <ol>
          <li>Set the host and request type</li>
          <li>Click "Trigger Prompt" to show the NIP-55 prompt</li>
          <li>Check "Remember my choice" and approve/deny</li>
          <li>Check console logs and use "Show Stored Permissions"</li>
          <li>Trigger the same prompt again to test automatic handling</li>
        </ol>

        <h3>Debug Info:</h3>
        <p><strong>Current Host:</strong> {testHost}</p>
        <p><strong>Request Type:</strong> {requestType}</p>
        <p><strong>Prompt Open:</strong> {prompt.state.isOpen ? 'Yes' : 'No'}</p>
        {prompt.state.request && (
          <p><strong>Current Request:</strong> {prompt.state.request.type} from {prompt.state.request.host}</p>
        )}
      </div>
    </div>
  )
}