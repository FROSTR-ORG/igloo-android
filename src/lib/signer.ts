import { BifrostNode }                from '@frostr/bifrost'
import { BifrostSignDevice }         from '@/class/signer.js'

import type {
  NIP55Request,
  NIP55WindowAPI,
  NIP55Result
} from '@/types/index.js'


/**
 * Execute signing operation directly with the BifrostSignDevice
 */
export async function executeSigningOperation(signer: BifrostSignDevice, request: NIP55Request): Promise<any> {
  switch (request.type) {
    case 'get_public_key':
      return signer.get_pubkey()
    case 'sign_event':
      return await signer.sign_event(request.event)
    case 'nip04_encrypt':
      return await signer.nip04_encrypt(request.pubkey, request.plaintext)
    case 'nip04_decrypt':
      return await signer.nip04_decrypt(request.pubkey, request.ciphertext)
    case 'nip44_encrypt':
      return await signer.nip44_encrypt(request.pubkey, request.plaintext)
    case 'nip44_decrypt':
      return await signer.nip44_decrypt(request.pubkey, request.ciphertext)
    case 'decrypt_zap_event':
      throw new Error('decrypt_zap_event not implemented')
    default:
      throw new Error(`Unknown request type: ${(request as any).type}`)
  }
}

/**
 * Execute automatic signing operation for Content Resolver requests
 * Clean implementation using bridge interface
 */
/**
 * Wait for node client to become available (for auto-unlock scenarios)
 */
async function waitForNodeClient(maxWaitMs: number = 3000): Promise<BifrostNode | null> {
  const startTime = Date.now()

  while (Date.now() - startTime < maxWaitMs) {
    if (window.nostr?.bridge?.nodeClient) {
      console.log(`Node client became available after ${Date.now() - startTime}ms`)
      return window.nostr.bridge.nodeClient
    }
    // Wait 100ms before checking again
    await new Promise(resolve => setTimeout(resolve, 100))
  }

  return null
}

export async function executeAutoSigning(request: NIP55Request): Promise<NIP55Result> {
  console.log('Auto-signing request:', request.type, 'from', request.host)

  try {
    // Check bridge availability
    if (!window.nostr?.bridge?.ready) {
      throw new Error('NIP-55 bridge not ready')
    }

    let nodeClient = window.nostr.bridge.nodeClient

    // For get_public_key, we can read from settings even when locked
    if (!nodeClient && request.type === 'get_public_key') {
      // Try to get pubkey from settings
      const stored_settings_json = localStorage.getItem('igloo-pwa')
      if (stored_settings_json) {
        const settings = JSON.parse(stored_settings_json)
        if (settings.pubkey) {
          console.log('Auto-signing get_public_key from settings (node locked)')
          return {
            ok: true,
            type: request.type,
            id: request.id,
            result: settings.pubkey
          }
        }
      }
      throw new Error('No public key available')
    }

    // For signing operations, check if auto-unlock might happen
    if (!nodeClient && request.type !== 'get_public_key') {
      // Check if there's a session password (indicates auto-unlock will happen)
      const sessionPassword = sessionStorage.getItem('igloo_session_password')

      if (sessionPassword) {
        console.log('Node locked but session password found - waiting for auto-unlock...')
        nodeClient = await waitForNodeClient(3000)

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

    console.log('Auto-signing completed successfully')

    return {
      ok: true,
      type: request.type,
      id: request.id,
      result: result || ''
    }

  } catch (error) {
    console.error('Auto-signing failed:', error)

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
    const start_time = Date.now()
    console.log('NIP-55 signing request:', request.type, request.id)

    try {
      // Basic input validation
      if (!request.id || !request.type) {
        throw new Error('Invalid request: missing id or type')
      }

      // Execute signing directly - Android already checked permissions
      console.log(`Executing auto-signing for ${request.host}:${request.type}`)
      const result = await executeAutoSigning(request)

      const duration = Date.now() - start_time
      console.log(`Auto-signing completed in ${duration}ms`)

      return result

    } catch (error) {
      console.error('Signing bridge error:', error)
      return {
        ok     : false,
        type   : request.type,
        id     : request.id,
        reason : error instanceof Error ? error.message : 'Unknown error'
      }
    }
  }
}
