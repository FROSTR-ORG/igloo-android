import { useEffect, useState }   from 'react'
import { useBifrostNode }        from '@/context/node.js'
import { create_signing_bridge } from '@/lib/signer.js'

import type { ReactElement } from 'react'

/**
 * NIP-55 Bridge Component with Content Resolver Support
 *
 * Sets up the window.nostr.nip55 interface when the Bifrost node is ready.
 * Provides automatic permission support for Content Resolver background operations.
 * Synchronizes permissions to window context for ContentProvider access.
 */
export function NIP55Bridge(): ReactElement | null {
  const node = useBifrostNode()
  const [ _, set_bridge_ready ] = useState(false)

  // No permission synchronization needed - components read directly from storage

  useEffect(() => {
    // Only initialize bridge when node is online and ready
    if (node.client && node.status === 'online') {
      try {
        // Create the enhanced signing bridge function
        const signing_bridge = create_signing_bridge()

        // Expose it on window.nostr.nip55
        if (!window.nostr) {
          window.nostr = {}
        }
        window.nostr.nip55 = signing_bridge

        // Mark bridge as ready for Content Resolver operations
        window.NIP55_BRIDGE_READY = true

        // Expose node client for direct library access
        window.NIP55_NODE_CLIENT = node.client

        set_bridge_ready(true)
        console.log('NIP-55 bridge initialized')

      } catch (error) {
        console.error('Failed to initialize NIP-55 bridge:', error)
        set_bridge_ready(false)
        window.NIP55_BRIDGE_READY = false
      }
    } else {
      // Clean up bridge if node goes offline
      if (window.nostr?.nip55) {
        delete window.nostr.nip55
        window.NIP55_BRIDGE_READY = false
        window.NIP55_NODE_CLIENT = null
        set_bridge_ready(false)
        console.log('NIP-55 bridge cleaned up (node offline)')
      }
    }
  }, [node.client, node.status])

  // This component doesn't render anything visible
  // It just manages the global window.nostr.nip55 interface
  return null
}