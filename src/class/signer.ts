import { get_event_id }   from '@cmdcode/nostr-p2p/lib'
import { BifrostNode }    from '@frostr/bifrost'
import { convert_pubkey } from '@frostr/bifrost/util'
import * as cipher        from '@/lib/cipher.js'

import type { EventTemplate } from 'nostr-tools'
import type { SignedEvent }   from '@cmdcode/nostr-p2p'

const SIGN_METHODS : Record<string, string> = {
  sign_event    : 'sign_event',
  nip04_encrypt : 'nip04_encrypt',
  nip04_decrypt : 'nip04_decrypt',
  nip44_encrypt : 'nip44_encrypt',
  nip44_decrypt : 'nip44_decrypt'
}

/**
 * BifrostSignDevice - Direct bifrost signing without PWA-side queuing
 *
 * Android-side NIP55RequestQueue handles all deduplication, caching, and batching.
 * This class now makes direct bifrost calls for simplicity.
 */
export class BifrostSignDevice {
  private _node : BifrostNode

  constructor (node : BifrostNode) {
    this._node = node
  }

  get_methods () : string[] {
    return Object.values(SIGN_METHODS)
  }

  get_pubkey () {
    return this._node.group.group_pk.slice(2)
  }

  /**
   * Get ECDH shared secret for a pubkey
   * Direct bifrost call - Android handles all queuing/dedup
   */
  private async getECDHSecret (pubkey : string) : Promise<string> {
    console.log('[BifrostSignDevice] getECDHSecret START for pubkey:', pubkey.slice(0, 16) + '...')
    const startTime = Date.now()

    try {
      console.log('[BifrostSignDevice] Calling this._node.req.ecdh...')
      const res = await this._node.req.ecdh(pubkey)
      const duration = Date.now() - startTime
      console.log('[BifrostSignDevice] ECDH response received:', 'ok:', res.ok, 'duration:', duration, 'ms')

      if (!res.ok) {
        console.error('[BifrostSignDevice] ECDH failed:', res.err)
        throw new Error(res.err)
      }

      const secret = convert_pubkey(res.data, 'bip340')
      console.log('[BifrostSignDevice] getECDHSecret COMPLETE, duration:', duration, 'ms')
      return secret
    } catch (error) {
      const duration = Date.now() - startTime
      console.error('[BifrostSignDevice] getECDHSecret ERROR after', duration, 'ms:', error)
      throw error
    }
  }

  /**
   * Sign an event
   * Direct bifrost call - Android handles all queuing/dedup
   */
  async sign_event (event : EventTemplate) : Promise<SignedEvent> {
    const { content, created_at, kind, tags } = event
    const pubkey = this._node.group.group_pk.slice(2)
    const tmpl   = { content, created_at, kind, pubkey, tags }
    const id     = get_event_id(tmpl)

    console.log('[BifrostSignDevice] sign_event START for event id:', id.slice(0, 16) + '...', 'kind:', kind)
    const startTime = Date.now()

    try {
      console.log('[BifrostSignDevice] Calling this._node.req.sign...')
      const res = await this._node.req.sign(id)
      const duration = Date.now() - startTime
      console.log('[BifrostSignDevice] Sign response received:', 'ok:', res.ok, 'duration:', duration, 'ms')

      if (!res.ok) {
        console.error('[BifrostSignDevice] Sign failed:', res.err)
        throw new Error(res.err)
      }

      const payload = res.data.at(0)
      if (payload?.at(0) !== id) {
        console.error('[BifrostSignDevice] Event ID mismatch:', payload?.at(0), 'vs', id)
        throw new Error('event id mismatch')
      }
      if (payload?.at(1)?.slice(2) !== pubkey) {
        console.error('[BifrostSignDevice] Pubkey mismatch')
        throw new Error('event pubkey mismatch')
      }

      const sig = payload.at(2)
      if (!sig) {
        console.error('[BifrostSignDevice] Signature missing from response')
        throw new Error('signature missing from response')
      }

      console.log('[BifrostSignDevice] sign_event COMPLETE, sig:', sig.slice(0, 16) + '...', 'duration:', duration, 'ms')
      return { ...tmpl, id, sig }
    } catch (error) {
      const duration = Date.now() - startTime
      console.error('[BifrostSignDevice] sign_event ERROR after', duration, 'ms:', error)
      throw error
    }
  }

  async nip04_encrypt (pubkey : string, plaintext : string) : Promise<string> {
    const secret = await this.getECDHSecret(pubkey)
    return cipher.nip04_encrypt(secret, plaintext)
  }

  async nip04_decrypt (pubkey : string, ciphertext : string) : Promise<string> {
    const secret = await this.getECDHSecret(pubkey)
    return cipher.nip04_decrypt(secret, ciphertext)
  }

  async nip44_encrypt (pubkey : string, plaintext : string) : Promise<string> {
    const secret = await this.getECDHSecret(pubkey)
    return cipher.nip44_encrypt(secret, plaintext)
  }

  async nip44_decrypt (pubkey : string, ciphertext : string) : Promise<string> {
    const secret = await this.getECDHSecret(pubkey)
    return cipher.nip44_decrypt(secret, ciphertext)
  }
}
