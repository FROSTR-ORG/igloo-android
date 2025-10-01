import { useEffect, useState }   from 'react'
import { useBifrostNode }        from '@/context/node.js'
import { create_signing_bridge, executeAutoSigning, requestManualPrompt } from '@/lib/signer.js'

import type { ReactElement } from 'react'
import type { NIP55Bridge } from '@/types/bridge.js'

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

        // Create clean consolidated bridge interface
        const bridge: NIP55Bridge = {
          ready: true,
          nodeClient: node.client,
          autoSign: executeAutoSigning,
          requestManualPrompt: requestManualPrompt
        }

        // Expose clean interface on window.nostr
        if (!window.nostr) {
          window.nostr = {}
        }
        window.nostr.nip55 = signing_bridge
        window.nostr.bridge = bridge

        set_bridge_ready(true)
        console.log('NIP-55 bridge initialized with clean interface')

      } catch (error) {
        console.error('Failed to initialize NIP-55 bridge:', error)
        set_bridge_ready(false)
        // Clean up bridge on error
        if (window.nostr?.bridge) {
          window.nostr.bridge.ready = false
          window.nostr.bridge.nodeClient = null
        }
      }
    } else {
      // Clean up bridge if node goes offline
      if (window.nostr?.nip55) {
        delete window.nostr.nip55
        if (window.nostr.bridge) {
          window.nostr.bridge.ready = false
          window.nostr.bridge.nodeClient = null
        }
        set_bridge_ready(false)
        console.log('NIP-55 bridge cleaned up (node offline)')
      }
    }
  }, [node.client, node.status])

  // This component doesn't render anything visible
  // It just manages the global window.nostr.nip55 interface
  return null
}