import { createContext, useContext, useState, useEffect, useRef } from 'react'
import { useBifrostNode } from '@/context/node.js'
import { BifrostSignDevice } from '@/class/signer.js'
import { addPermissionRule } from '@/lib/permissions.js'
import { executeSigningOperation, setManualPromptCallback } from '@/lib/signer.js'

import type { ReactElement } from 'react'
import type {
  PromptAPI,
  PromptState,
  ProviderProps,
  NIP55Request,
  NIP55Result
} from '@/types/index.js'

const DEFAULT_STATE: PromptState = {
  isOpen: false,
  request: null,
  status: 'pending',
  remember: false
}

const context = createContext<PromptAPI | null>(null)

/**
 * Simplified prompt provider for NIP-55 signing requests
 * Handles basic approve/deny flow without complex permission management
 */
export const PromptProvider = ({ children }: ProviderProps): ReactElement => {
  const [state, setState] = useState<PromptState>(DEFAULT_STATE)
  const node = useBifrostNode()

  // Store a mutable reference to setState for use in callbacks
  const setStateRef = useRef(setState)
  setStateRef.current = setState

  // Manual prompt handling - no events, direct function calls only



  /**
   * Show a signing prompt to the user
   */
  const showPrompt = (request: NIP55Request) => {
    setState({
      isOpen: true,
      request,
      status: 'pending',
      remember: false
    })
  }

  /**
   * User approved the signing request
   */
  const approve = async (remember: boolean = false): Promise<void> => {
    if (!state.request) return

    setState(prev => ({ ...prev, status: 'approved' }))

    const resolve = (window as any).__promptResolve
    const request = state.request

    // Execute the signing operation if node is available
    if (node.status === 'online' && node.client) {
      try {
        const signer = new BifrostSignDevice(node.client)
        const result = await executeSigningOperation(signer, request)

        // Create permission rule if user chose "remember my choice"
        console.log('Permission check:', { remember, host: request.host })
        if (remember && request.host) {
          try {
            addPermissionRule(request.host, request.type, true)
            console.log(`Permission rule created: ${request.host}:${request.type} = allowed`)
          } catch (error) {
            console.error('Failed to create permission rule:', error)
          }
        } else {
          console.log('Permission NOT created - remember:', remember, 'host:', request.host)
        }

        console.log('Signing operation completed successfully')

        // Resolve Promise with success result
        if (resolve) {
          resolve({
            ok: true,
            type: request.type,
            id: request.id,
            result: result || ''
          })
        }
      } catch (error) {
        console.error('Signing operation failed:', error)

        // Resolve Promise with error result
        if (resolve) {
          resolve({
            ok: false,
            type: request.type,
            id: request.id,
            reason: error instanceof Error ? error.message : 'Signing failed'
          })
        }
      }
    } else {
      console.error('Node not available for signing')

      // Resolve Promise with node unavailable error
      if (resolve) {
        resolve({
          ok: false,
          type: request.type,
          id: request.id,
          reason: 'Node not available'
        })
      }
    }

    // Clear the prompt and cleanup
    setState(DEFAULT_STATE)
    delete (window as any).__promptResolve
  }

  /**
   * User denied the signing request
   */
  const deny = (remember: boolean = false): Promise<void> => {
    if (!state.request) return Promise.resolve()

    setState(prev => ({ ...prev, status: 'denied' }))

    const resolve = (window as any).__promptResolve
    const request = state.request

    // Create permission rule if user chose "remember my choice"
    if (remember && request.host) {
      try {
        addPermissionRule(request.host, request.type, false)
        console.log(`Permission rule created: ${request.host}:${request.type} = denied`)
      } catch (error) {
        console.error('Failed to create permission rule:', error)
      }
    }

    console.log('Signing request denied by user')

    // Resolve Promise with denial result
    if (resolve) {
      resolve({
        ok: false,
        type: request.type,
        id: request.id,
        reason: 'User denied request'
      })
    }

    // Clear the prompt and cleanup
    setState(DEFAULT_STATE)
    delete (window as any).__promptResolve
    return Promise.resolve()
  }

  /**
   * Dismiss the prompt without action
   */
  const dismiss = () => {
    setState(DEFAULT_STATE)
  }


  /**
   * Handle manual prompt requests from the signing bridge
   * Returns a Promise that resolves when user makes a decision
   */
  const handleManualPrompt = async (request: NIP55Request): Promise<NIP55Result> => {
    return new Promise((resolve) => {
      // Store the resolve function to call when user decides
      (window as any).__promptResolve = resolve

      // Use the mutable ref to access current setState function
      const currentSetState = setStateRef.current
      currentSetState({
        isOpen: true,
        request,
        status: 'pending',
        remember: false
      })
    })
  }

  // Register the callback with the signing bridge
  useEffect(() => {
    setManualPromptCallback(handleManualPrompt)
  }, [])

  const api: PromptAPI = {
    state,
    showPrompt,
    approve,
    deny,
    dismiss
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