import { NIP55WindowAPI } from './signer.js'

declare global {
  interface Window {
    nostr? : { nip55 ?: NIP55WindowAPI }
    NIP55_BRIDGE_READY?: boolean
  }
}
