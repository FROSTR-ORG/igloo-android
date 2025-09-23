import { useState } from 'react'
import { usePrompt } from '@/context/prompt.js'

import type {
  GetPublicKeyRequest,
  EncryptRequest,
  DecryptRequest,
  DecryptZapRequest
} from '@/types.js'

export function ActionPrompt() {
  const prompt = usePrompt()
  const [remember, setRemember] = useState(false)
  const [loading, setLoading] = useState(false)

  if (!prompt.state.isOpen || !prompt.state.request) {
    return null
  }

  const request = prompt.state.request

  // Only handle non-sign_event requests
  if (request.type === 'sign_event') {
    return null
  }

  const handleApprove = async () => {
    setLoading(true)
    try {
      await prompt.approve(remember)
    } catch (error) {
      console.error('Failed to approve action:', error)
      alert('Failed to execute action: ' + (error as Error).message)
    } finally {
      setLoading(false)
    }
  }

  const handleDeny = async () => {
    setLoading(true)
    try {
      await prompt.deny(remember)
    } catch (error) {
      console.error('Failed to deny action:', error)
    } finally {
      setLoading(false)
    }
  }

  const getActionTitle = (type: string) => {
    switch (type) {
      case 'get_public_key': return 'Share Public Key'
      case 'nip04_encrypt': return 'NIP-04 Encrypt Message'
      case 'nip04_decrypt': return 'NIP-04 Decrypt Message'
      case 'nip44_encrypt': return 'NIP-44 Encrypt Message'
      case 'nip44_decrypt': return 'NIP-44 Decrypt Message'
      case 'decrypt_zap_event': return 'Decrypt Zap Event'
      default: return 'Unknown Action'
    }
  }

  const getActionDescription = (request: any) => {
    switch (request.type) {
      case 'get_public_key':
        return 'This application wants to know your public key to identify you.'
      case 'nip04_encrypt':
        return `Encrypt a message for ${(request as EncryptRequest).pubkey.slice(0, 16)}...`
      case 'nip04_decrypt':
        return `Decrypt a message from ${(request as DecryptRequest).pubkey.slice(0, 16)}...`
      case 'nip44_encrypt':
        return `Encrypt a message (NIP-44) for ${(request as EncryptRequest).pubkey.slice(0, 16)}...`
      case 'nip44_decrypt':
        return `Decrypt a message (NIP-44) from ${(request as DecryptRequest).pubkey.slice(0, 16)}...`
      case 'decrypt_zap_event':
        return 'Decrypt a zap event to view payment details.'
      default:
        return 'An external application is requesting to perform an action.'
    }
  }

  const showContent = () => {
    if (request.type === 'nip04_encrypt' || request.type === 'nip44_encrypt') {
      const encryptReq = request as EncryptRequest
      return (
        <div className="request-content">
          <h4>Content to encrypt:</h4>
          <pre className="code-display">{encryptReq.plaintext}</pre>
          <p><strong>Recipient:</strong> {encryptReq.pubkey}</p>
        </div>
      )
    }

    if (request.type === 'nip04_decrypt' || request.type === 'nip44_decrypt') {
      const decryptReq = request as DecryptRequest
      return (
        <div className="request-content">
          <h4>Encrypted content:</h4>
          <pre className="code-display">{decryptReq.ciphertext.slice(0, 100)}...</pre>
          <p><strong>Sender:</strong> {decryptReq.pubkey}</p>
        </div>
      )
    }

    if (request.type === 'decrypt_zap_event') {
      const zapReq = request as DecryptZapRequest
      return (
        <div className="request-content">
          <h4>Zap event to decrypt:</h4>
          <pre className="code-display">{JSON.stringify(zapReq.event, null, 2).slice(0, 200)}...</pre>
        </div>
      )
    }

    return null
  }

  return (
    <div className="prompt-overlay">
      <div className="prompt-modal">
        <div className="prompt-header">
          <h2>{getActionTitle(request.type)}</h2>
          <button
            onClick={prompt.dismiss}
            className="button button-remove prompt-close"
            disabled={loading}
          >
            ×
          </button>
        </div>

        <div className="prompt-body">
          <div className="request-info">
            <p><strong>Application:</strong> {request.host}</p>
            <p className="description">{getActionDescription(request)}</p>
          </div>

          {showContent()}

          <div className="permission-controls">
            <div className="checkbox-container">
              <input
                type="checkbox"
                id="remember-choice"
                checked={remember}
                onChange={(e) => setRemember(e.target.checked)}
                disabled={loading}
              />
              <label htmlFor="remember-choice">
                Remember my choice for this application
              </label>
            </div>
          </div>
        </div>

        <div className="prompt-actions">
          <button
            onClick={handleDeny}
            className="button"
            disabled={loading}
          >
            Deny
          </button>
          <button
            onClick={handleApprove}
            className="button button-primary"
            disabled={loading}
          >
            {loading ? 'Processing...' : 'Approve'}
          </button>
        </div>
      </div>
    </div>
  )
}