import { useState } from 'react'
import { usePrompt } from '@/context/prompt.js'

import type { SignEventRequest } from '@/types.js'

export function EventPrompt() {
  const prompt = usePrompt()
  const [remember, setRemember] = useState(false)
  const [loading, setLoading] = useState(false)

  if (!prompt.state.isOpen || !prompt.state.request) {
    return null
  }

  const request = prompt.state.request

  // Only handle sign_event requests
  if (request.type !== 'sign_event') {
    return null
  }

  const signRequest = request as SignEventRequest

  const handleApprove = async () => {
    setLoading(true)
    try {
      await prompt.approve(remember)
    } catch (error) {
      console.error('Failed to approve signing:', error)
      alert('Failed to sign event: ' + (error as Error).message)
    } finally {
      setLoading(false)
    }
  }

  const handleDeny = async () => {
    setLoading(true)
    try {
      await prompt.deny(remember)
    } catch (error) {
      console.error('Failed to deny signing:', error)
    } finally {
      setLoading(false)
    }
  }

  const getEventKindName = (kind: number) => {
    switch (kind) {
      case 0: return 'Profile Metadata'
      case 1: return 'Text Note'
      case 2: return 'Recommend Relay'
      case 3: return 'Contacts'
      case 4: return 'Encrypted Direct Message'
      case 5: return 'Event Deletion'
      case 6: return 'Repost'
      case 7: return 'Reaction'
      case 8: return 'Badge Award'
      case 9734: return 'Zap Request'
      case 9735: return 'Zap'
      case 10000: return 'Mute List'
      case 10001: return 'Pin List'
      case 10002: return 'Relay List Metadata'
      case 22242: return 'Client Authentication'
      case 23194: return 'Wallet Info'
      case 23195: return 'Wallet Request'
      case 24133: return 'Nostr Connect'
      case 30000: return 'Categorized People List'
      case 30001: return 'Categorized Bookmark List'
      case 30008: return 'Profile Badges'
      case 30009: return 'Badge Definition'
      case 30017: return 'Create or update a stall'
      case 30018: return 'Create or update a product'
      case 30023: return 'Long-form Content'
      case 30078: return 'Application-specific Data'
      default: return `Event Kind ${kind}`
    }
  }

  const formatEventPreview = (event: any) => {
    const preview = {
      kind: event.kind,
      content: event.content?.slice(0, 200) + (event.content?.length > 200 ? '...' : ''),
      tags: event.tags?.slice(0, 5) || []
    }

    if (event.tags?.length > 5) {
      preview.tags.push(['...', `${event.tags.length - 5} more tags`])
    }

    return preview
  }

  const isHighRiskEvent = (kind: number) => {
    // Consider certain event kinds as high risk
    return [
      0,     // Profile changes
      3,     // Contact list changes
      10002, // Relay list changes
      23195, // Wallet requests
      24133  // Nostr Connect
    ].includes(kind)
  }

  return (
    <div className="prompt-overlay">
      <div className="prompt-modal">
        <div className="prompt-header">
          <h2>Sign Event</h2>
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
            <p className="description">
              This application wants to sign a <strong>{getEventKindName(signRequest.event.kind)}</strong> event.
            </p>
          </div>

          {isHighRiskEvent(signRequest.event.kind) && (
            <div className="warning-notice">
              <p className="error-text">
                ⚠️ This event type can modify important account settings. Please review carefully.
              </p>
            </div>
          )}

          <div className="request-content">
            <h4>Event Details:</h4>
            <pre className="code-display">
              {JSON.stringify(formatEventPreview(signRequest.event), null, 2)}
            </pre>

            {signRequest.event.content && signRequest.event.content.length > 200 && (
              <details className="full-content">
                <summary>Show full content</summary>
                <pre className="code-display">
                  {signRequest.event.content}
                </pre>
              </details>
            )}

            {signRequest.event.tags && signRequest.event.tags.length > 5 && (
              <details className="full-tags">
                <summary>Show all tags ({signRequest.event.tags.length})</summary>
                <pre className="code-display">
                  {JSON.stringify(signRequest.event.tags, null, 2)}
                </pre>
              </details>
            )}
          </div>

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
                Remember my choice for {getEventKindName(signRequest.event.kind)} events from this application
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
            {loading ? 'Signing...' : 'Sign Event'}
          </button>
        </div>
      </div>
    </div>
  )
}