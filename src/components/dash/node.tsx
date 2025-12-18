import { useEffect, useState } from 'react'
import { nip19 }          from 'nostr-tools'
import { useBifrostNode } from '@/context/node.js'
import { useSettings }    from '@/context/settings.js'
import { LockIcon, CopyIcon, CheckIcon } from '@/components/util/icons.js'
import { STORAGE_KEYS }   from '@/const.js'

export function NodeInfoView () {
  const node     = useBifrostNode()
  const settings = useSettings()
  const share_pk = settings.data.pubkey
  const group_pk = settings.data.group?.group_pk

  const [ password, setPassword ]       = useState('')
  const [ error, setError ]             = useState<string | null>(null)
  const [ showHex, setShowHex ]         = useState(false)
  const [ copySuccess, setCopySuccess ] = useState(false)

  const copy_pubkey = async (pk: string) => {
    // Return early if the pubkey is not set.
    if (!pk) return
    // Get the npub value.
    const valueToCopy = get_npub(pk, showHex)
    // Try to copy the value.
    try {
      // Copy the value to the clipboard.
      await navigator.clipboard.writeText(valueToCopy)
      // Set the copy success state.
      setCopySuccess(true)
      // Reset the copy success state after 1 second.
      setTimeout(() => setCopySuccess(false), 1000)
    } catch {
      // Copy failed - ignore silently
    }
  }

  const unlock_node = async (e: React.FormEvent) => {
    // Prevent the default form submission.
    e.preventDefault()
    // Return early if the password is not set.
    if (!password) {
      // Set the error state and return.
      setError('Password is required')
      return
    }
    // Unlock the client.
    node.unlock(password)
    // Reset the password state.
    setPassword('')
  }

  useEffect(() => {
    if (node.status === 'locked') {
      // First check sessionStorage
      const password = sessionStorage.getItem(STORAGE_KEYS.SESSION_PASSWORD)
      // If the password is found, unlock the node.
      if (password) node.unlock(password)
    }
  }, [ node.status ])

  // If client is locked, show locked state
  if (node.status === 'locked') {
    return (
      <div className="dashboard-container">
        <h2 className="section-header">Node Info</h2>
        <div className="node-inline-row locked">
          <span className="node-label">Status</span>
          <span className="status-pill locked">Locked</span>
        </div>
        <form onSubmit={unlock_node} className="unlock-form">
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="Enter password to unlock..."
            className="nsec-input"
            autoComplete="current-password"
          />
          <button 
            type="submit"
            className="button button-primary"
            disabled={!password}
          >
            Unlock
          </button>
        </form>
        {error && <div className="error-text">{error}</div>}
      </div>
    )
  }

  // Show normal state when unlocked
  return (
    <div className="dashboard-container">
      <h2 className="section-header">Node Info</h2>
      <div className="node-inline-row locked">
        <span className="node-label">Status</span>
        <span className={`status-pill ${node.status}`}>{node.status}</span>
      </div>
      <div className="node-inline-row">
        <span className="node-label">Share</span>
        {!share_pk && <pre>no FROSTR share configured</pre>}
        {share_pk &&
          <div className="pubkey-container">
            <span className="node-npub">
              { share_pk }
            </span>
          </div>
        }
      </div>
      <div className="node-inline-row">
        <span className="node-label">Pubkey</span>
        {!group_pk && <pre>no group pubkey set</pre>}
        {group_pk &&
          <div className="pubkey-container">
            <span 
              className="node-npub" 
              onClick={() => setShowHex(!showHex)}
              title={`Click to show ${showHex ? 'npub' : 'hex'} format`}
            >
              { get_npub(group_pk, showHex)}
            </span>
            <button
              onClick={() => copy_pubkey(group_pk)}
              className={`button copy-button ${copySuccess ? 'copied' : ''}`}
              title="Copy to clipboard"
            >
              {copySuccess ? <CheckIcon /> : <CopyIcon />}
            </button>
          </div>
        }
      </div>
      <div className="node-actions">
        <button
          className="button"
          onClick={() => node.reset()}
        >
          Reset Node
        </button>
        <button
          className="button"
          onClick={() => node.lock()}
          title="Lock Node"
        >
          <LockIcon />
        </button>
      </div>
    </div>
  )
}

function get_npub (
  pubkey  : string | null,
  showHex : boolean
) {
  if (pubkey === null) return ''
  if (showHex)         return pubkey
  return nip19.npubEncode(pubkey)
}
