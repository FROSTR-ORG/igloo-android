import { useState } from 'react'
import { NodeInfoView } from './node.js'
import { PeerInfoView } from './peers.js'
import { ConsoleView }  from './console.js'
import { NIP55TestPage } from '@/components/debug/nip55-test.js'

export function DashboardView () {
  const [showDebug, setShowDebug] = useState(false)

  if (showDebug) {
    return (
      <>
        <div style={{ marginBottom: '1rem' }}>
          <button
            onClick={() => setShowDebug(false)}
            className="button"
          >
            ← Back to Dashboard
          </button>
        </div>
        <NIP55TestPage />
      </>
    )
  }

  return (
    <>
      <NodeInfoView />
      <PeerInfoView />
      <ConsoleView />

      <div style={{ marginTop: '2rem', textAlign: 'center' }}>
        <button
          onClick={() => setShowDebug(true)}
          className="button"
          style={{
            fontSize: '0.8rem',
            padding: '0.25rem 0.5rem',
            opacity: 0.7
          }}
        >
          🐛 NIP-55 Debug
        </button>
      </div>
    </>
  )
}
