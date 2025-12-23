import { BifrostNode }                from '@frostr/bifrost'
import { BifrostSignDevice }         from '@/class/signer.js'
import { nip19 }                     from 'nostr-tools'
import { STORAGE_KEYS }              from '@/const.js'

import type {
  NIP55Request,
  NIP55WindowAPI,
  NIP55Result
} from '@/types/index.js'


/**
 * Execute signing operation directly with the BifrostSignDevice
 */
export async function executeSigningOperation(signer: BifrostSignDevice, request: NIP55Request): Promise<any> {
  console.log('[signer] executeSigningOperation START:', request.type, 'id:', request.id)
  const startTime = Date.now()

  try {
    let result: any
    switch (request.type) {
      case 'get_public_key':
        result = signer.get_pubkey()
        break
      case 'sign_event':
        console.log('[signer] Calling signer.sign_event...')
        result = await signer.sign_event(request.event)
        break
      case 'nip04_encrypt':
        console.log('[signer] Calling signer.nip04_encrypt...')
        result = await signer.nip04_encrypt(request.pubkey, request.plaintext)
        break
      case 'nip04_decrypt':
        console.log('[signer] Calling signer.nip04_decrypt...')
        result = await signer.nip04_decrypt(request.pubkey, request.ciphertext)
        break
      case 'nip44_encrypt':
        console.log('[signer] Calling signer.nip44_encrypt...')
        result = await signer.nip44_encrypt(request.pubkey, request.plaintext)
        break
      case 'nip44_decrypt':
        console.log('[signer] Calling signer.nip44_decrypt...')
        result = await signer.nip44_decrypt(request.pubkey, request.ciphertext)
        break
      case 'decrypt_zap_event':
        throw new Error('decrypt_zap_event not implemented')
      default:
        throw new Error(`Unknown request type: ${(request as any).type}`)
    }

    const duration = Date.now() - startTime
    console.log('[signer] executeSigningOperation COMPLETE:', request.type, 'duration:', duration, 'ms')
    return result
  } catch (error) {
    const duration = Date.now() - startTime
    console.error('[signer] executeSigningOperation ERROR:', request.type, 'duration:', duration, 'ms', 'error:', error)
    throw error
  }
}

/**
 * Execute automatic signing operation for Content Resolver requests
 * Clean implementation using bridge interface
 */
/**
 * Wait for node client to become available (for auto-unlock scenarios)
 * Increased timeout to 10 seconds to allow for bifrost relay connection
 */
async function waitForNodeClient(maxWaitMs: number = 10000): Promise<BifrostNode | null> {
  const startTime = Date.now()
  console.log('[signer] waitForNodeClient: starting wait, max:', maxWaitMs, 'ms')

  while (Date.now() - startTime < maxWaitMs) {
    const client = window.nostr?.bridge?.nodeClient
    if (client) {
      console.log('[signer] waitForNodeClient: client found after', Date.now() - startTime, 'ms')
      return client
    }
    // Wait 100ms before checking again
    await new Promise(resolve => setTimeout(resolve, 100))
  }

  console.log('[signer] waitForNodeClient: timeout after', maxWaitMs, 'ms')
  return null
}

export async function executeAutoSigning(request: NIP55Request): Promise<NIP55Result> {
  console.log('[signer] executeAutoSigning START:', request.type, 'id:', request.id)
  const startTime = Date.now()

  try {
    // Check bridge availability
    if (!window.nostr?.bridge?.ready) {
      console.error('[signer] Bridge not ready')
      throw new Error('NIP-55 bridge not ready')
    }

    let nodeClient = window.nostr.bridge.nodeClient
    console.log('[signer] nodeClient available:', !!nodeClient)

    // For get_public_key, we can read from settings even when locked
    if (!nodeClient && request.type === 'get_public_key') {
      // Try to get GROUP pubkey from settings (not share pubkey)
      const stored_settings_json = localStorage.getItem(STORAGE_KEYS.SETTINGS)
      if (stored_settings_json) {
        const settings = JSON.parse(stored_settings_json)
        // Return the FROSTR group pubkey, not the share pubkey
        // Group pubkey is what signatures are verified against
        if (settings.group?.group_pk) {
          // group_pk has "02" prefix (compressed key), slice it off for hex pubkey
          const groupPubkey = settings.group.group_pk.slice(2)
          // Encode pubkey as npub for Coracle compatibility
          const npub = nip19.npubEncode(groupPubkey)
          return {
            ok: true,
            type: request.type,
            id: request.id,
            result: groupPubkey,  // Hex format for Amethyst - GROUP pubkey
            npub: npub            // Bech32 format for Coracle
          } as any
        }
      }
      throw new Error('No public key available')
    }

    // For signing operations, check if auto-unlock might happen
    if (!nodeClient && request.type !== 'get_public_key') {
      // Check if there's a session password (indicates auto-unlock will happen)
      const sessionPassword = sessionStorage.getItem(STORAGE_KEYS.SESSION_PASSWORD)
      console.log('[signer] executeAutoSigning: nodeClient null, sessionPassword:', sessionPassword ? 'FOUND' : 'NOT_FOUND')

      if (sessionPassword) {
        // Wait up to 10 seconds for auto-unlock to complete (bifrost relay connection takes time)
        nodeClient = await waitForNodeClient(10000)

        if (!nodeClient) {
          throw new Error('Auto-unlock timed out - node still locked')
        }
      } else {
        throw new Error('Node is locked - please unlock to sign events')
      }
    }

    // Node must be available at this point
    if (!nodeClient) {
      throw new Error('Node client not available')
    }

    // Create signer and execute operation
    const signer = new BifrostSignDevice(nodeClient)
    const result = await executeSigningOperation(signer, request)

    // For get_public_key, add npub field for Coracle compatibility
    if (request.type === 'get_public_key') {
      const npub = nip19.npubEncode(result)
      const duration = Date.now() - startTime
      console.log('[signer] executeAutoSigning SUCCESS (get_public_key), duration:', duration, 'ms')
      return {
        ok: true,
        type: request.type,
        id: request.id,
        result: result || '',  // Hex format for Amethyst
        npub: npub             // Bech32 format for Coracle
      } as any
    }

    const duration = Date.now() - startTime
    console.log('[signer] executeAutoSigning SUCCESS:', request.type, 'duration:', duration, 'ms')
    return {
      ok: true,
      type: request.type,
      id: request.id,
      result: result || ''
    }

  } catch (error) {
    const duration = Date.now() - startTime
    console.error('[signer] executeAutoSigning ERROR:', request.type, 'duration:', duration, 'ms', 'error:', error)
    return {
      ok: false,
      type: request.type,
      id: request.id,
      reason: error instanceof Error ? error.message : 'Unknown signing error'
    }
  }
}

/**
 * NOTE: Manual prompt system removed - Android handles all prompting via native dialogs
 * PWA no longer shows permission prompts - permissions must be pre-approved in localStorage
 */

/**
 * Create the main NIP-55 signing bridge function
 *
 * Pure signing interface - no permission checking
 * Android handles all permission logic via InvisibleNIP55Handler
 */
export function create_signing_bridge(): NIP55WindowAPI {
  return async (request: NIP55Request): Promise<NIP55Result> => {
    console.log('[signing_bridge] Request received:', request.type, 'id:', request.id)
    const startTime = Date.now()

    try {
      // Basic input validation
      if (!request.id || !request.type) {
        throw new Error('Invalid request: missing id or type')
      }

      // Execute signing directly - Android already checked permissions
      const result = await executeAutoSigning(request)

      const duration = Date.now() - startTime
      console.log('[signing_bridge] Returning result:', 'ok:', result.ok, 'duration:', duration, 'ms')
      return result

    } catch (error) {
      const duration = Date.now() - startTime
      console.error('[signing_bridge] Returning error:', error, 'duration:', duration, 'ms')
      return {
        ok     : false,
        type   : request.type,
        id     : request.id,
        reason : error instanceof Error ? error.message : 'Unknown error'
      }
    }
  }
}
