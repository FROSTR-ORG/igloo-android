import { useEffect, useState }   from 'react'
import { useBifrostNode }        from '@/context/node.js'

import { create_signing_bridge, executeAutoSigning } from '@/lib/signer.js'

import type { ReactElement } from 'react'
import type { NIP55Bridge }  from '@/types/bridge.js'

/**
 * NIP-55 Bridge Component with Content Resolver Support
 *
 * Sets up the window.nostr.nip55 interface when the Bifrost node is ready.
 * Provides automatic permission support for Content Resolver background operations.
 *
 * Note: Android-side NIP55RequestQueue handles all deduplication, caching, and batching.
 * No PWA-side queues needed.
 */
export function NIP55Bridge(): ReactElement | null {
  const node = useBifrostNode()
  const [ _, set_bridge_ready ] = useState(false)

  useEffect(() => {
    console.log('[NIP55Bridge] Effect triggered, status:', node.status, 'client:', node.client ? 'EXISTS' : 'NULL')

    // Initialize bridge when node is online, locked, or offline (connecting)
    // Only disable for 'init' (not configured) or 'disabled' states
    if (node.status === 'online' || node.status === 'locked' || node.status === 'offline') {
      try {
        // Create the enhanced signing bridge function
        const signing_bridge = create_signing_bridge()

        // Create clean consolidated bridge interface
        const bridge: NIP55Bridge = {
          ready: true,
          nodeClient: node.client || null,  // May be null when locked, set when connecting/online
          autoSign: executeAutoSigning
        }

        // Expose clean interface on window.nostr
        if (!window.nostr) {
          window.nostr = {}
        }
        window.nostr.nip55 = signing_bridge
        window.nostr.bridge = bridge

        console.log('[NIP55Bridge] Bridge set up, nodeClient:', node.client ? 'EXISTS' : 'NULL')
        set_bridge_ready(true)

      } catch {
        set_bridge_ready(false)
        // Clean up bridge on error
        if (window.nostr?.bridge) {
          window.nostr.bridge.ready = false
          window.nostr.bridge.nodeClient = null
        }
      }
    } else {
      // Clean up bridge if node is not configured (init/disabled)
      console.log('[NIP55Bridge] Cleaning up bridge, status:', node.status)
      if (window.nostr?.nip55) {
        delete window.nostr.nip55
        if (window.nostr.bridge) {
          window.nostr.bridge.ready = false
          window.nostr.bridge.nodeClient = null
        }
        set_bridge_ready(false)
      }
    }
  }, [node.client, node.status])

  // This component doesn't render anything visible
  // It just manages the global window.nostr.nip55 interface
  return null
}
