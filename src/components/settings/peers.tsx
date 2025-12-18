import { useEffect, useState } from 'react'
import { useSettings }         from '@/context/settings.js'

import type { GroupPackage, PeerConfig } from '@frostr/bifrost'
import { convert_pubkey } from '@frostr/bifrost/util'

export function PeerConfigField() {
  const settings = useSettings()

  const [ peers, setPeers ]     = useState<PeerConfig[]>([])
  const [ changes, setChanges ] = useState<boolean>(false)
  const [ saved, setSaved ]     = useState<boolean>(false)

  // Update the peer policies in the store.
  const update = () => {
    settings.update({ peers })
    setChanges(false)
    setSaved(true)
    setTimeout(() => setSaved(false), 1500)
  }

  // Discard changes by resetting local state from store
  const cancel = () => {
    setPeers(settings.data.peers)
    setChanges(false)
  }

  // Update peer connectivity status locally
  const update_peer = (idx: number, key: 'send' | 'recv', value: boolean) => {
    setPeers(prev => {
      const updated = [ ...prev ?? [] ]
      updated[idx].policy[key] = value
      return updated
    })
    setChanges(true)
  }

  useEffect(() => {
    const { group, peers,pubkey } = settings.data
    if (group !== null && pubkey !== null) {
      if (peers.length === 0) {
        setPeers(init_peers(group, pubkey))
      } else {
        setPeers(peers)
      }
    }
  }, [ settings.data ])

  useEffect(() => {
    if (settings.data.pubkey === null) {
      setPeers([])
    } else if (
      settings.data.peers.length === 0 &&
      settings.data.group !== null
    ) {
      setPeers(init_peers(settings.data.group, settings.data.pubkey))
    }
  }, [ settings.data.pubkey ])

  return (
    <div className="container">
      <h2 className="section-header">Peer Connections</h2>
      <p className="description">Configure how you communicate with other peers in your signing group. "Send" will send signature requests to that peer, and "Sign" will co-sign requests from that peer.</p>

      {peers.length === 0 &&
        <p className="description">You must configure your node's credentials first.</p>
      }

      {peers.length > 0 &&
        <div>
          <table>
            <thead>
              <tr>
                <th>Peer Public Key</th>
                <th className="checkbox-cell">Send</th>
                <th className="checkbox-cell">Sign</th>
              </tr>
            </thead>
            <tbody>
              {peers.map((peer, idx) => (
                <tr key={idx}>
                  <td className="pubkey-cell">{peer.pubkey.slice(2)}</td>
                  <td className="checkbox-cell">
                    <input
                      type="checkbox"
                      className="peer-checkbox"
                      checked={peer.policy.send}
                      onChange={() => update_peer(idx, 'send', !peer.policy.send)}
                    />
                  </td>
                  <td className="checkbox-cell">
                    <input
                      type="checkbox"
                      className="peer-checkbox"
                      checked={peer.policy.recv}
                      onChange={() => update_peer(idx, 'recv', !peer.policy.recv)}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          
          <div className="action-buttons">
            <button 
              onClick={update}
              disabled={!changes}
              className={`button button-primary action-button ${saved ? 'saved-button' : ''}`}
            >
              {saved ? 'Saved' : 'Save'}
            </button>
            
            {changes && (
              <button 
                onClick={cancel}
                className="button"
              >
                Cancel
              </button>
            )}
          </div>
        </div>
      }
    </div>
  )
}

function init_peers (
  group  : GroupPackage,
  pubkey : string
) : PeerConfig[] {
  const filtered = group.commits.filter(commit => convert_pubkey(commit.pubkey, 'bip340') !== pubkey)
  return filtered.map(commit => ({
    pubkey : commit.pubkey,
    policy : { send: false, recv: true }
  }))
}
