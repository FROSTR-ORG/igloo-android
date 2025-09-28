import { createContext, useContext, useState, useEffect } from 'react'
import { useBifrostNode } from '@/context/node.js'
import { BifrostSignDevice } from '@/class/signer.js'
import { addPermissionRule } from '@/lib/permissions.js'

import type { ReactElement } from 'react'
import type {
  PromptAPI,
  PromptState,
  ProviderProps,
  NIP55Request
} from '@/types/index.js'

const DEFAULT_STATE: PromptState = {
  isOpen: false,
  request: null,
  status: 'pending',
  remember: false,
  pendingRequest: null
}

const context = createContext<PromptAPI | null>(null)

/**
 * Simplified prompt provider for NIP-55 signing requests
 * Handles basic approve/deny flow without complex permission management
 */
export const PromptProvider = ({ children }: ProviderProps): ReactElement => {
  const [state, setState] = useState<PromptState>(DEFAULT_STATE)
  const node = useBifrostNode()

  // Handle manual signing prompt requests only
  useEffect(() => {
    const handlePromptRequest = (event: Event) => {
      const customEvent = event as CustomEvent<any>
      const { request } = customEvent.detail
      console.log('Received manual signing request:', request)

      // Only handle manual prompts - auto-approval is handled in signer.ts
      showPrompt(request)
    }

    window.addEventListener('nip55-prompt-request', handlePromptRequest)
    return () => {
      window.removeEventListener('nip55-prompt-request', handlePromptRequest)
    }
  }, [])


  /**
   * Execute the appropriate signing operation based on request type
   */
  const executeSigningOperation = async (signer: BifrostSignDevice, request: NIP55Request): Promise<any> => {
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
   * Show a signing prompt to the user
   */
  const showPrompt = (request: NIP55Request) => {
    setState({
      isOpen: true,
      request,
      status: 'pending',
      remember: false,
      pendingRequest: null
    })
  }

  /**
   * User approved the signing request
   */
  const approve = async (remember: boolean = false): Promise<void> => {
    if (!state.request) return

    setState(prev => ({ ...prev, status: 'approved' }))

    // Execute the signing operation if node is available
    if (node.status === 'online' && node.client) {
      try {
        const signer = new BifrostSignDevice(node.client)
        const result = await executeSigningOperation(signer, state.request)

        // Create permission rule if user chose "remember my choice"
        console.log('Permission check:', { remember, host: state.request.host })
        if (remember && state.request.host) {
          try {
            addPermissionRule(state.request.host, state.request.type, true)
            console.log(`Permission rule created: ${state.request.host}:${state.request.type} = allowed`)
          } catch (error) {
            console.error('Failed to create permission rule:', error)
          }
        } else {
          console.log('Permission NOT created - remember:', remember, 'host:', state.request.host)
        }

        // Dispatch success events
        window.dispatchEvent(new CustomEvent('nip55-signing-result', {
          detail: { requestId: state.request.id, success: true, result }
        }))
        window.dispatchEvent(new CustomEvent('nip55-prompt-response', {
          detail: { requestId: state.request.id, approved: true }
        }))

        console.log('Signing operation completed successfully')
      } catch (error) {
        console.error('Signing operation failed:', error)

        // Dispatch error events
        window.dispatchEvent(new CustomEvent('nip55-signing-result', {
          detail: { requestId: state.request.id, success: false, error: String(error) }
        }))
        window.dispatchEvent(new CustomEvent('nip55-prompt-response', {
          detail: { requestId: state.request.id, approved: false }
        }))
      }
    } else {
      console.error('Node not available for signing')

      // Dispatch node unavailable error
      window.dispatchEvent(new CustomEvent('nip55-signing-result', {
        detail: { requestId: state.request.id, success: false, error: 'Node not available' }
      }))
      window.dispatchEvent(new CustomEvent('nip55-prompt-response', {
        detail: { requestId: state.request.id, approved: false }
      }))
    }

    // Clear the prompt
    setState(DEFAULT_STATE)
  }

  /**
   * User denied the signing request
   */
  const deny = (remember: boolean = false): Promise<void> => {
    if (!state.request) return Promise.resolve()

    setState(prev => ({ ...prev, status: 'denied' }))

    // Create permission rule if user chose "remember my choice"
    if (remember && state.request.host) {
      try {
        addPermissionRule(state.request.host, state.request.type, false)
        console.log(`Permission rule created: ${state.request.host}:${state.request.type} = denied`)
      } catch (error) {
        console.error('Failed to create permission rule:', error)
      }
    }

    // Dispatch denial event
    window.dispatchEvent(new CustomEvent('nip55-prompt-response', {
      detail: { requestId: state.request.id, approved: false }
    }))

    console.log('Signing request denied by user')

    // Clear the prompt
    setState(DEFAULT_STATE)
    return Promise.resolve()
  }

  /**
   * Dismiss the prompt without action
   */
  const dismiss = () => {
    setState(DEFAULT_STATE)
  }

  /**
   * Show pending request (if user was offline)
   */
  const showPending = () => {
    if (state.pendingRequest) {
      setState(prev => ({
        isOpen: true,
        request: prev.pendingRequest,
        status: 'pending',
        remember: false,
        pendingRequest: null
      }))
    }
  }

  /**
   * Clear pending request without showing
   */
  const clearPending = () => {
    setState(prev => ({
      ...prev,
      pendingRequest: null
    }))
  }

  const api: PromptAPI = {
    state,
    showPrompt,
    approve,
    deny,
    dismiss,
    showPending,
    clearPending
  }

  return (
    <context.Provider value={api}>
      {children}
    </context.Provider>
  )
}

export const usePrompt = () => {
  const ctx = useContext(context)
  if (ctx === null) {
    throw new Error('usePrompt must be used within a PromptProvider')
  }
  return ctx
}