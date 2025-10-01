// This file is deprecated - Window interface is now defined in bridge.ts
// Keeping minimal definition for backwards compatibility until full migration
import { NIP55WindowAPI } from './signer.js'

declare global {
  interface Window {
    nostr? : { nip55 ?: NIP55WindowAPI }
  }
}
