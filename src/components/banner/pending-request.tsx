import { usePrompt } from '@/context/prompt.js'

export function PendingRequestBanner() {
  const prompt = usePrompt()

  // Don't show banner if no pending request
  if (!prompt.state.pendingRequest) {
    return null
  }

  const request = prompt.state.pendingRequest

  const getRequestDescription = () => {
    switch (request.type) {
      case 'get_public_key':
        return 'wants to access your public key'
      case 'sign_event':
        return 'wants you to sign an event'
      case 'nip04_encrypt':
      case 'nip44_encrypt':
        return 'wants to encrypt a message'
      case 'nip04_decrypt':
      case 'nip44_decrypt':
        return 'wants to decrypt a message'
      case 'decrypt_zap_event':
        return 'wants to decrypt a zap event'
      default:
        return 'has a signing request'
    }
  }

  return (
    <div className="pending-banner">
      <div className="pending-banner-content">
        <div className="pending-banner-icon">🔐</div>
        <div className="pending-banner-text">
          <div className="pending-banner-title">
            Signing Request Waiting
          </div>
          <div className="pending-banner-description">
            <strong>{request.host || 'External app'}</strong> {getRequestDescription()}.
            Please log in to continue.
          </div>
        </div>
        <div className="pending-banner-actions">
          <button
            className="pending-banner-button approve"
            onClick={prompt.showPending}
          >
            Show Request
          </button>
          <button
            className="pending-banner-button deny"
            onClick={prompt.clearPending}
          >
            Dismiss
          </button>
        </div>
      </div>
    </div>
  )
}