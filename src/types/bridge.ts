import type { BifrostNode } from '@frostr/bifrost'
import type { NIP55Request, NIP55Result } from '@/types/index.js'

/**
 * Clean internal bridge interface for NIP-55 operations
 *
 * Android-side NIP55RequestQueue handles all deduplication, caching, and batching.
 * This interface is now simplified - no PWA-side queues needed.
 */
export interface NIP55Bridge {
  /** Bridge initialization status */
  ready: boolean

  /** Direct access to Bifrost node client */
  nodeClient: BifrostNode | null

  /** Execute automatic signing without user interaction */
  autoSign: (request: NIP55Request) => Promise<NIP55Result>
}

/**
 * Global window interface extension
 */
declare global {
  interface Window {
    nostr: {
      nip55?: (request: NIP55Request) => Promise<NIP55Result>
      bridge?: NIP55Bridge
    }
  }
}
