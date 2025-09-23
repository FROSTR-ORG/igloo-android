// Demo component showing how to use the NIP-55 prompt system
// This is for development/testing purposes

import { usePrompt } from '@/context/prompt.js'
import { createTestNIP55URL } from '@/lib/nip55.js'
import type { NIP55Request } from '@/types.js'

export function PromptDemo() {
  const prompt = usePrompt()

  const testGetPublicKey = () => {
    const request: NIP55Request = {
      type: 'get_public_key',
      host: 'demo.example.com',
      callbackUrl: window.location.origin + '/demo-result'
    }
    prompt.showPrompt(request)
  }

  const testSignEvent = () => {
    const request: NIP55Request = {
      type: 'sign_event',
      host: 'demo.example.com',
      event: {
        kind: 1,
        content: 'This is a test note from the demo!',
        tags: [
          ['p', '1234567890abcdef1234567890abcdef12345678'],
          ['t', 'demo'],
          ['t', 'test']
        ]
      },
      id: 'demo-sign-123'
    }
    prompt.showPrompt(request)
  }

  const testEncrypt = () => {
    const request: NIP55Request = {
      type: 'nip04_encrypt',
      host: 'demo.example.com',
      plaintext: 'This is a secret message that will be encrypted!',
      pubkey: '1234567890abcdef1234567890abcdef12345678901234567890abcdef1234567890'
    }
    prompt.showPrompt(request)
  }

  const testDecrypt = () => {
    const request: NIP55Request = {
      type: 'nip04_decrypt',
      host: 'demo.example.com',
      ciphertext: 'U2FsdGVkX1+ABC123DEF456GHI789JKL012MNO345PQR678STU901VWX234YZA567BCD?iv=890DEF123GHI456JKL789MNO012PQR',
      pubkey: '1234567890abcdef1234567890abcdef12345678901234567890abcdef1234567890'
    }
    prompt.showPrompt(request)
  }

  const testHighRiskEvent = () => {
    const request: NIP55Request = {
      type: 'sign_event',
      host: 'suspicious.example.com',
      event: {
        kind: 0, // Profile metadata - high risk
        content: JSON.stringify({
          name: 'New Profile Name',
          about: 'Changed profile description',
          picture: 'https://example.com/new-avatar.jpg'
        }),
        tags: []
      }
    }
    prompt.showPrompt(request)
  }

  const testProtocolHandler = (requestType: string) => {
    let url = ''

    switch (requestType) {
      case 'get_public_key':
        url = createTestNIP55URL({
          type: 'get_public_key',
          callbackUrl: window.location.origin + '/demo-result'
        })
        break
      case 'sign_event':
        url = createTestNIP55URL({
          type: 'sign_event',
          event: {
            kind: 1,
            content: 'Hello from protocol handler test!',
            tags: [['t', 'test']]
          },
          callbackUrl: window.location.origin + '/demo-result'
        })
        break
      case 'encrypt':
        url = createTestNIP55URL({
          type: 'nip04_encrypt',
          plaintext: 'Secret message via protocol handler!',
          pubkey: '1234567890abcdef1234567890abcdef12345678901234567890abcdef1234567890'
        })
        break
    }

    if (url) {
      console.log('Testing protocol handler with URL:', url)
      window.location.href = url
    }
  }

  return (
    <div className="container">
      <h2 className="section-header">NIP-55 Prompt Demo</h2>
      <p className="description">
        Test the NIP-55 prompt system with various request types.
      </p>

      <div className="demo-controls">
        <div className="settings-group">
          <h3>Direct Prompt Testing</h3>
          <p className="description">Test prompts directly via React context:</p>
          <div className="action-buttons">
            <button onClick={testGetPublicKey} className="button">
              Test Get Public Key
            </button>
            <button onClick={testEncrypt} className="button">
              Test NIP-04 Encrypt
            </button>
            <button onClick={testDecrypt} className="button">
              Test NIP-04 Decrypt
            </button>
          </div>
        </div>

        <div className="settings-group">
          <h3>Event Signing</h3>
          <div className="action-buttons">
            <button onClick={testSignEvent} className="button">
              Test Sign Text Note
            </button>
            <button onClick={testHighRiskEvent} className="button button-danger">
              Test High-Risk Event (Profile)
            </button>
          </div>
        </div>

        <div className="settings-group">
          <h3>Protocol Handler Testing</h3>
          <p className="description">Test NIP-55 URL scheme handling (requires browser protocol registration):</p>
          <div className="action-buttons">
            <button onClick={() => testProtocolHandler('get_public_key')} className="button">
              🔗 Test nostrsigner: Get Public Key
            </button>
            <button onClick={() => testProtocolHandler('sign_event')} className="button">
              🔗 Test nostrsigner: Sign Event
            </button>
            <button onClick={() => testProtocolHandler('encrypt')} className="button">
              🔗 Test nostrsigner: Encrypt
            </button>
          </div>
          <p className="text-small text-muted">
            Note: Protocol handler tests will trigger URL navigation. Make sure your browser has registered this PWA as the handler for nostrsigner: URLs.
          </p>
        </div>

        <div className="settings-group">
          <h3>Current Prompt State</h3>
          <pre className="code-display">
            {JSON.stringify({
              isOpen: prompt.state.isOpen,
              requestType: prompt.state.request?.type || null,
              status: prompt.state.status,
              host: prompt.state.request?.host || null
            }, null, 2)}
          </pre>
        </div>

        <div className="settings-group">
          <h3>Manual URL Testing</h3>
          <p className="description">Click these URLs to test the protocol handler directly:</p>
          <div className="test-urls">
            <div className="url-test">
              <strong>Get Public Key:</strong>
              <div style={{margin: '8px 0', display: 'flex', alignItems: 'center', gap: '10px'}}>
                <a
                  href={createTestNIP55URL({
                    type: 'get_public_key',
                    callbackUrl: window.location.origin + '/demo-result'
                  })}
                  className="button"
                  style={{fontSize: '12px', padding: '6px 12px'}}
                >
                  🔗 Click to Test
                </a>
                <button
                  onClick={() => {
                    const url = createTestNIP55URL({
                      type: 'get_public_key',
                      callbackUrl: window.location.origin + '/demo-result'
                    })
                    navigator.clipboard.writeText(url)
                    alert('URL copied to clipboard!')
                  }}
                  className="button"
                  style={{fontSize: '12px', padding: '6px 12px'}}
                >
                  📋 Copy URL
                </button>
              </div>
              <pre className="code-display" style={{fontSize: '10px', margin: '4px 0', maxHeight: '60px', overflow: 'auto'}}>
                {createTestNIP55URL({
                  type: 'get_public_key',
                  callbackUrl: window.location.origin + '/demo-result'
                })}
              </pre>
            </div>

            <div className="url-test">
              <strong>Sign Text Note:</strong>
              <div style={{margin: '8px 0', display: 'flex', alignItems: 'center', gap: '10px'}}>
                <a
                  href={createTestNIP55URL({
                    type: 'sign_event',
                    event: { kind: 1, content: 'Hello from NIP-55 test!', tags: [['t', 'test']] }
                  })}
                  className="button"
                  style={{fontSize: '12px', padding: '6px 12px'}}
                >
                  🔗 Click to Test
                </a>
                <button
                  onClick={() => {
                    const url = createTestNIP55URL({
                      type: 'sign_event',
                      event: { kind: 1, content: 'Hello from NIP-55 test!', tags: [['t', 'test']] }
                    })
                    navigator.clipboard.writeText(url)
                    alert('URL copied to clipboard!')
                  }}
                  className="button"
                  style={{fontSize: '12px', padding: '6px 12px'}}
                >
                  📋 Copy URL
                </button>
              </div>
              <pre className="code-display" style={{fontSize: '10px', margin: '4px 0', maxHeight: '60px', overflow: 'auto'}}>
                {createTestNIP55URL({
                  type: 'sign_event',
                  event: { kind: 1, content: 'Hello from NIP-55 test!', tags: [['t', 'test']] }
                })}
              </pre>
            </div>

            <div className="url-test">
              <strong>NIP-04 Encrypt:</strong>
              <div style={{margin: '8px 0', display: 'flex', alignItems: 'center', gap: '10px'}}>
                <a
                  href={createTestNIP55URL({
                    type: 'nip04_encrypt',
                    plaintext: 'Secret message from URL test!',
                    pubkey: '1234567890abcdef1234567890abcdef12345678901234567890abcdef1234567890'
                  })}
                  className="button"
                  style={{fontSize: '12px', padding: '6px 12px'}}
                >
                  🔗 Click to Test
                </a>
                <button
                  onClick={() => {
                    const url = createTestNIP55URL({
                      type: 'nip04_encrypt',
                      plaintext: 'Secret message from URL test!',
                      pubkey: '1234567890abcdef1234567890abcdef12345678901234567890abcdef1234567890'
                    })
                    navigator.clipboard.writeText(url)
                    alert('URL copied to clipboard!')
                  }}
                  className="button"
                  style={{fontSize: '12px', padding: '6px 12px'}}
                >
                  📋 Copy URL
                </button>
              </div>
              <pre className="code-display" style={{fontSize: '10px', margin: '4px 0', maxHeight: '60px', overflow: 'auto'}}>
                {createTestNIP55URL({
                  type: 'nip04_encrypt',
                  plaintext: 'Secret message from URL test!',
                  pubkey: '1234567890abcdef1234567890abcdef12345678901234567890abcdef1234567890'
                })}
              </pre>
            </div>

            <div className="url-test">
              <strong>High-Risk Profile Event:</strong>
              <div style={{margin: '8px 0', display: 'flex', alignItems: 'center', gap: '10px'}}>
                <a
                  href={createTestNIP55URL({
                    type: 'sign_event',
                    event: {
                      kind: 0,
                      content: JSON.stringify({
                        name: 'Test Profile',
                        about: 'Profile update via NIP-55 test',
                        picture: 'https://example.com/avatar.jpg'
                      }),
                      tags: []
                    }
                  })}
                  className="button button-danger"
                  style={{fontSize: '12px', padding: '6px 12px'}}
                >
                  ⚠️ Click to Test (High Risk)
                </a>
                <button
                  onClick={() => {
                    const url = createTestNIP55URL({
                      type: 'sign_event',
                      event: {
                        kind: 0,
                        content: JSON.stringify({
                          name: 'Test Profile',
                          about: 'Profile update via NIP-55 test'
                        }),
                        tags: []
                      }
                    })
                    navigator.clipboard.writeText(url)
                    alert('URL copied to clipboard!')
                  }}
                  className="button"
                  style={{fontSize: '12px', padding: '6px 12px'}}
                >
                  📋 Copy URL
                </button>
              </div>
              <pre className="code-display" style={{fontSize: '10px', margin: '4px 0', maxHeight: '60px', overflow: 'auto'}}>
                {createTestNIP55URL({
                  type: 'sign_event',
                  event: {
                    kind: 0,
                    content: JSON.stringify({
                      name: 'Test Profile',
                      about: 'Profile update via NIP-55 test'
                    }),
                    tags: []
                  }
                })}
              </pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}