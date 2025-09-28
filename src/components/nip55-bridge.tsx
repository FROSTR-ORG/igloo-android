import { useEffect, useState } from 'react'
import { useBifrostNode }      from '@/context/node.js'
import { createSigningBridge } from '@/lib/signer.js'

/**
 * NIP-55 Bridge Component
 *
 * Sets up the window.nostr.nip55 interface when the Bifrost node is ready.
 * Provides the main entry point for NIP-55 signing requests.
 */
export function NIP55Bridge() {
  const node = useBifrostNode()
  const [bridgeReady, setBridgeReady] = useState(false)

  useEffect(() => {
    // Only initialize bridge when node is online and ready
    if (node.client && node.status === 'online') {
      try {
        // Create the signing bridge function
        const signingBridge = createSigningBridge(node.client)

        // Expose it on window.nostr.nip55
        if (!window.nostr) {
          window.nostr = {}
        }
        window.nostr.nip55 = signingBridge

        setBridgeReady(true)
        console.log('NIP-55 bridge initialized successfully')

      } catch (error) {
        console.error('Failed to initialize NIP-55 bridge:', error)
        setBridgeReady(false)
      }
    } else {
      // Clean up bridge if node goes offline
      if (window.nostr?.nip55) {
        delete window.nostr.nip55
        setBridgeReady(false)
        console.log('NIP-55 bridge cleaned up (node offline)')
      }
    }
  }, [node.client, node.status])

  // This component doesn't render anything visible
  // It just manages the global window.nostr.nip55 interface
  return null
}