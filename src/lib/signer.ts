import { BifrostNode } from '@frostr/bifrost'
import { BifrostSignDevice } from '@/class/signer.js'
import { checkPermission } from '@/lib/permissions.js'

import type {
  NIP55Request,
  NIP55WindowAPI,
  NIP55Result
} from '@/types/index.js'

/**
 * Check permission using stored permission rules
 */
function checkBasicPermission(request: NIP55Request): 'approved' | 'prompt_required' | 'denied' {
  const permissionStatus = checkPermission(request)

  // Map the permission result to the expected return type
  switch (permissionStatus) {
    case 'allowed':
      return 'approved'
    case 'denied':
      return 'denied'
    case 'prompt_required':
    default:
      return 'prompt_required'
  }
}

/**
 * Prompt user for signing approval and execute the operation
 */
async function promptUserForSigning(request: NIP55Request): Promise<{ approved: boolean, result?: string }> {
  console.log('Prompting user for signing approval:', request.type)

  return new Promise((resolve) => {
    let signingResult: any = null

    // Listen for signing result from prompt context
    const signingResultHandler = (event: CustomEvent) => {
      if (event.detail.requestId === request.id) {
        signingResult = event.detail
      }
    }

    // Listen for user approval/denial
    const promptResponseHandler = (event: CustomEvent) => {
      if (event.detail.requestId === request.id) {
        // Clean up event listeners
        window.removeEventListener('nip55-signing-result', signingResultHandler)
        window.removeEventListener('nip55-prompt-response', promptResponseHandler)

        const approved = event.detail.approved
        if (approved && signingResult?.success) {
          resolve({ approved: true, result: signingResult.result })
        } else {
          resolve({
            approved: false,
            result: signingResult?.error || 'User denied or signing failed'
          })
        }
      }
    }

    // Set up event listeners
    window.addEventListener('nip55-signing-result', signingResultHandler)
    window.addEventListener('nip55-prompt-response', promptResponseHandler)

    // Trigger the prompt
    window.dispatchEvent(new CustomEvent('nip55-prompt-request', {
      detail: { request }
    }))

    // Timeout after 5 minutes
    setTimeout(() => {
      window.removeEventListener('nip55-signing-result', signingResultHandler)
      window.removeEventListener('nip55-prompt-response', promptResponseHandler)
      resolve({ approved: false, result: 'Request timeout' })
    }, 300000)
  })
}

/**
 * Create the main NIP-55 signing bridge function
 */
export function createSigningBridge(node: BifrostNode): NIP55WindowAPI {
  return async (request: NIP55Request): Promise<NIP55Result> => {
    console.log('NIP-55 signing request:', request.type, request.id)
    console.log('Full NIP55Request object:', JSON.stringify(request, null, 2))

    try {
      // Basic input validation
      if (!request.id || !request.type) {
        throw new Error('Invalid request: missing id or type')
      }

      // Check basic permissions
      const permissionStatus = checkBasicPermission(request)

      if (permissionStatus === 'denied') {
        return {
          ok: false,
          type: request.type,
          id: request.id,
          reason: 'Permission denied'
        }
      }

      // For any permission that requires prompt, ask the user
      if (permissionStatus === 'prompt_required') {
        const userResponse = await promptUserForSigning(request)

        if (!userResponse.approved) {
          return {
            ok: false,
            type: request.type,
            id: request.id,
            reason: userResponse.result || 'User denied'
          }
        }

        // User approved and signing completed successfully
        return {
          ok: true,
          type: request.type,
          id: request.id,
          result: userResponse.result || ''
        }
      }

      // Auto-approved - execute signing directly without user prompt
      if (permissionStatus === 'approved') {
        // We need access to the BifrostNode to perform signing
        // For now, return success but we need to integrate with prompt context for actual signing
        console.log('Auto-approving request:', request.type, 'from', request.host)

        // Trigger the prompt context to handle auto-approval
        window.dispatchEvent(new CustomEvent('nip55-prompt-request', {
          detail: { request, autoApprove: true }
        }))

        // Return a pending result - the actual result will come from the prompt context
        return new Promise((resolve) => {
          const responseHandler = (event: CustomEvent) => {
            if (event.detail.requestId === request.id) {
              window.removeEventListener('nip55-prompt-response', responseHandler)
              if (event.detail.approved) {
                resolve({
                  ok: true,
                  type: request.type,
                  id: request.id,
                  result: event.detail.result || ''
                })
              } else {
                resolve({
                  ok: false,
                  type: request.type,
                  id: request.id,
                  reason: 'Auto-approval failed'
                })
              }
            }
          }

          window.addEventListener('nip55-prompt-response', responseHandler)

          // Timeout after 10 seconds
          setTimeout(() => {
            window.removeEventListener('nip55-prompt-response', responseHandler)
            resolve({
              ok: false,
              type: request.type,
              id: request.id,
              reason: 'Auto-approval timeout'
            })
          }, 10000)
        })
      }

      // This shouldn't happen
      return {
        ok: false,
        type: request.type,
        id: request.id,
        reason: 'Unknown permission status'
      }

    } catch (error) {
      console.error('Signing bridge error:', error)
      return {
        ok: false,
        type: request.type,
        id: request.id,
        reason: error instanceof Error ? error.message : 'Unknown error'
      }
    }
  }
}