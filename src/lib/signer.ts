import { BifrostNode }                from '@frostr/bifrost'
import { BifrostSignDevice }         from '@/class/signer.js'

import type {
  NIP55Request,
  NIP55WindowAPI,
  NIP55Result
} from '@/types/index.js'

/**
 * Check automatic permission reading directly from secure storage
 */
async function check_permission(request: NIP55Request): Promise<'allowed' | 'prompt_required' | 'denied'> {
  try {
    // Read permissions directly from secure storage (no window global needed)
    const stored_permissions_json = localStorage.getItem('nip55_permissions')
    const stored_permissions = stored_permissions_json ? JSON.parse(stored_permissions_json) : []

    // Check if permission exists for this app + operation
    const permission = stored_permissions.find((p: any) =>
      p.appId === request.host && p.type === request.type
    )

    if (permission) {
      if (permission.allowed) {
        console.log(`Permission found: ${request.host}:${request.type} = allowed`)
        return 'allowed'
      } else {
        console.log(`Permission found: ${request.host}:${request.type} = denied`)
        return 'denied'
      }
    }

    // No permission found - prompt required
    console.log(`No permission found for ${request.host}:${request.type}`)
    return 'prompt_required'

  } catch (error) {
    console.error('Failed to check permission:', error)
    return 'prompt_required'
  }
}


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
export async function executeAutoSigning(request: NIP55Request): Promise<NIP55Result> {
  console.log('Auto-signing request:', request.type, 'from', request.host)

  try {
    // Check bridge availability
    if (!window.nostr?.bridge?.ready) {
      throw new Error('NIP-55 bridge not ready')
    }

    const nodeClient = window.nostr.bridge.nodeClient
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
 * Global callback for manual prompt - set by React PromptProvider
 */
let promptCallback: ((request: NIP55Request) => Promise<NIP55Result>) | null = null

/**
 * Set the manual prompt callback (called by React PromptProvider)
 */
export function setManualPromptCallback(callback: (request: NIP55Request) => Promise<NIP55Result>) {
  promptCallback = callback
}

/**
 * Request manual user prompt for signing
 * Clean implementation that connects to React prompt context
 */
export async function requestManualPrompt(request: NIP55Request): Promise<NIP55Result> {
  console.log('Manual prompt request:', request.type, 'from', request.host)

  if (!promptCallback) {
    console.error('Manual prompt callback not set - PromptProvider not initialized')
    return {
      ok: false,
      type: request.type,
      id: request.id,
      reason: 'Prompt system not available'
    }
  }

  return await promptCallback(request)
}

/**
 * Create the main NIP-55 signing bridge function with automatic permission support
 */
export function create_signing_bridge(): NIP55WindowAPI {
  return async (request: NIP55Request): Promise<NIP55Result> => {
    const start_time = Date.now()
    console.log('NIP-55 signing request:', request.type, request.id)

    // Log request type for debugging
    console.log('NIP-55 request received')

    try {
      // Basic input validation
      if (!request.id || !request.type) {
        throw new Error('Invalid request: missing id or type')
      }

      // Check if permission exists
      const permission_status = await check_permission(request)

      // Handle denied permissions
      if (permission_status === 'denied') {
        console.log(`Permission denied for ${request.host}:${request.type}`)
        return {
          ok     : false,
          type   : request.type,
          id     : request.id,
          reason : 'Permission denied'
        }
      }

      // Handle allowed permissions - auto-sign
      if (permission_status === 'allowed') {
        console.log(`Permission allowed for ${request.host}:${request.type} - auto-signing`)
        const result = await executeAutoSigning(request)

        const duration = Date.now() - start_time
        console.log(`Auto-signing completed in ${duration}ms`)

        return result
      }

      // Handle no permission - prompt required
      if (permission_status === 'prompt_required') {
        console.log(`Prompting user for ${request.host}:${request.type}`)
        return await requestManualPrompt(request)
      }

      // This shouldn't happen
      return {
        ok     : false,
        type   : request.type,
        id     : request.id,
        reason : 'Unknown permission status'
      }

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
