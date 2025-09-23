import { createContext, useContext, useState, useEffect } from 'react'
import { useSettings } from '@/context/settings.js'
import { useBifrostNode } from '@/context/node.js'
import { BifrostSignDevice } from '@/class/signer.js'

import type { ReactElement } from 'react'

import type {
  PromptAPI,
  PromptState,
  ProviderProps,
  NIP55Request,
  PermissionPolicy,
  PermActionRecord,
  PermEventRecord
} from '@/types.js'

const DEFAULT_STATE: PromptState = {
  isOpen: false,
  request: null,
  status: 'pending',
  remember: false,
  pendingRequest: null
}

const context = createContext<PromptAPI | null>(null)

export const PromptProvider = ({ children }: ProviderProps): ReactElement => {
  const [state, setState] = useState<PromptState>(DEFAULT_STATE)
  const settings = useSettings()
  const node = useBifrostNode()

  // Show a new prompt
  const showPrompt = async (request: NIP55Request) => {
    console.log('showPrompt called with request:', request)
    console.log('Node status:', node.status)

    // If node is initializing, wait briefly for auto-unlock to complete
    if (node.status === 'init') {
      console.log('Node is initializing, waiting for auto-unlock...')
      // Wait up to 2 seconds for node to stabilize
      let attempts = 0
      const maxAttempts = 20 // 20 * 100ms = 2 seconds

      while (node.status === 'init' && attempts < maxAttempts) {
        await new Promise(resolve => setTimeout(resolve, 100))
        attempts++
      }
      console.log(`After waiting, node status is: ${node.status}`)
    }

    // If user is not online/unlocked, store as pending request instead of showing prompt
    if (node.status !== 'online') {
      console.log(`User is not online (status: ${node.status}), storing as pending request`)
      setState(prev => ({
        ...prev,
        pendingRequest: request
      }))
      return
    }

    // Check if we have an existing permission for this request
    const existingPermission = getExistingPermission(request)
    if (existingPermission) {
      console.log('Found existing permission:', existingPermission)
      if (existingPermission.accept) {
        console.log('Auto-approving based on existing permission')
        // Execute the request automatically
        try {
          const result = await executeOperation(request)
          await sendResult(request, result, true)
        } catch (error) {
          console.error('Auto-approval failed:', error)
          // Fall back to showing prompt on error
          setState({
            isOpen: true,
            request,
            status: 'pending',
            remember: false,
            pendingRequest: null
          })
        }
      } else {
        console.log('Auto-denying based on existing permission')
        await sendResult(request, null, false)
      }
      return
    }

    // No existing permission, show the prompt normally
    console.log('No existing permission found, showing prompt')
    setState({
      isOpen: true,
      request,
      status: 'pending',
      remember: false,
      pendingRequest: null
    })
  }

  // Check if permission already exists for this request
  const getExistingPermission = (request: NIP55Request) => {
    const permissions = settings.data.perms
    for (const policy of permissions) {
      if (request.type === 'sign_event') {
        const eventRecord = policy.event.find((e: PermEventRecord) =>
          e.host === request.host && e.kind === request.event?.kind
        )
        if (eventRecord) return eventRecord
      } else {
        const actionRecord = policy.action.find((a: PermActionRecord) =>
          a.host === request.host && a.action === request.type
        )
        if (actionRecord) return actionRecord
      }
    }
    return null
  }

  // Add permission to settings
  const addPermission = (request: NIP55Request, accept: boolean) => {
    const timestamp = Math.floor(Date.now() / 1000)
    const permissions = [...settings.data.perms]

    // Find existing policy for this host or create new one
    let policy = permissions.find(p =>
      p.action.some(a => a.host === request.host) ||
      p.event.some(e => e.host === request.host)
    )

    if (!policy) {
      policy = { action: [], event: [] }
      permissions.push(policy)
    }

    if (request.type === 'sign_event') {
      // Check if permission already exists for this host/kind combination
      const existingIndex = policy.event.findIndex(e =>
        e.host === request.host && e.kind === (request.event?.kind || 0)
      )

      const eventRecord: PermEventRecord = {
        host: request.host,
        type: 'event',
        kind: request.event?.kind || 0,
        accept,
        created_at: timestamp
      }

      if (existingIndex >= 0) {
        // Update existing permission
        policy.event[existingIndex] = eventRecord
      } else {
        // Add new permission
        policy.event.push(eventRecord)
      }
    } else {
      // Check if permission already exists for this host/action combination
      const existingIndex = policy.action.findIndex(a =>
        a.host === request.host && a.action === request.type
      )

      const actionRecord: PermActionRecord = {
        host: request.host,
        type: 'action',
        action: request.type,
        accept,
        created_at: timestamp
      }

      if (existingIndex >= 0) {
        // Update existing permission
        policy.action[existingIndex] = actionRecord
      } else {
        // Add new permission
        policy.action.push(actionRecord)
      }
    }

    settings.update({ perms: permissions })
  }

  // Execute the signer operation
  const executeOperation = async (request: NIP55Request): Promise<any> => {
    console.log('Executing operation:', request.type)
    console.log('Node client available:', !!node.client)

    if (!node.client) {
      console.error('Node client not available - user may need to be logged in')
      throw new Error('Node client not available - please ensure you are logged in')
    }

    console.log('Creating BifrostSignDevice with node client')
    const signer = new BifrostSignDevice(node.client)

    try {
      switch (request.type) {
        case 'get_public_key':
          console.log('Getting public key...')
          const pubkey = signer.get_pubkey()
          console.log('Public key:', pubkey)
          return pubkey

        case 'sign_event':
          console.log('Signing event:', request.event)
          const signedEvent = await signer.sign_event(request.event)
          console.log('Signed event:', signedEvent)
          return signedEvent

        case 'nip04_encrypt':
          console.log('NIP-04 encrypting for pubkey:', request.pubkey)
          const encrypted04 = await signer.nip04_encrypt(request.pubkey, request.plaintext)
          console.log('NIP-04 encrypted result length:', encrypted04.length)
          return encrypted04

        case 'nip04_decrypt':
          console.log('NIP-04 decrypting from pubkey:', request.pubkey)
          const decrypted04 = await signer.nip04_decrypt(request.pubkey, request.ciphertext)
          console.log('NIP-04 decrypted result length:', decrypted04.length)
          return decrypted04

        case 'nip44_encrypt':
          console.log('NIP-44 encrypting for pubkey:', request.pubkey)
          const encrypted44 = await signer.nip44_encrypt(request.pubkey, request.plaintext)
          console.log('NIP-44 encrypted result length:', encrypted44.length)
          return encrypted44

        case 'nip44_decrypt':
          console.log('NIP-44 decrypting from pubkey:', request.pubkey)
          const decrypted44 = await signer.nip44_decrypt(request.pubkey, request.ciphertext)
          console.log('NIP-44 decrypted result length:', decrypted44.length)
          return decrypted44

        case 'decrypt_zap_event':
          console.log('decrypt_zap_event not yet implemented')
          throw new Error('decrypt_zap_event not yet implemented')

        default:
          console.error('Unknown request type:', (request as any).type)
          throw new Error(`Unknown request type: ${(request as any).type}`)
      }
    } catch (error) {
      console.error('Error in signer operation:', error)
      throw error
    }
  }

  // Send result via Android bridge, callback URL, or clipboard
  const sendResult = async (request: NIP55Request, result: any, isApproved: boolean) => {
    // Check if we're running in Android WebView with NIP-55 bridge
    const androidBridge = (window as any).AndroidNIP55
    if (androidBridge) {
      try {
        if (isApproved) {
          console.log('Sending approval to Android bridge:', result)

          let resultString: string
          let eventString: string = ''

          if (request.type === 'sign_event' && result && typeof result === 'object') {
            // For sign_event, result should be the signed event object
            resultString = result.sig || JSON.stringify(result)
            eventString = JSON.stringify(result)
          } else {
            resultString = typeof result === 'object' ? JSON.stringify(result) : String(result)
          }

          androidBridge.approveRequest(
            resultString,
            request.id || '',
            eventString
          )
          return
        } else {
          console.log('Sending denial to Android bridge')
          androidBridge.denyRequest(
            request.id || '',
            'User denied the request'
          )
          return
        }
      } catch (error) {
        console.error('Failed to communicate with Android bridge:', error)
        // Fall through to other methods
      }
    }

    if (!isApproved) {
      // For denied requests, we typically don't send results
      if (request.callbackUrl) {
        // Could optionally send error to callback URL
        console.log('Request denied, not sending result')
      }
      return
    }

    if (request.callbackUrl) {
      try {
        const params = new URLSearchParams()
        if (request.id) params.set('id', request.id)

        if (request.type === 'sign_event') {
          params.set('result', result.sig)
          params.set('event', JSON.stringify(result))
        } else {
          params.set('result', result)
        }

        const url = `${request.callbackUrl}${request.callbackUrl.includes('?') ? '&' : '?'}${params.toString()}`
        window.location.href = url
      } catch (error) {
        console.error('Failed to send result to callback URL:', error)
        // Fallback to clipboard
        navigator.clipboard?.writeText(JSON.stringify(result))
      }
    } else {
      // Copy result to clipboard
      try {
        const resultText = request.type === 'sign_event'
          ? JSON.stringify(result)
          : String(result)
        await navigator.clipboard.writeText(resultText)
        console.log('Result copied to clipboard')
      } catch (error) {
        console.error('Failed to copy to clipboard:', error)
      }
    }
  }

  // Approve the request
  const approve = async (remember: boolean = false): Promise<any> => {
    console.log('=== APPROVE REQUEST STARTED ===')
    console.log('Request:', state.request)
    console.log('Remember choice:', remember)

    if (!state.request) {
      console.log('No request to approve, returning')
      return
    }

    try {
      console.log('Setting status to approved')
      setState(prev => ({ ...prev, status: 'approved' }))

      console.log('Executing operation...')
      const result = await executeOperation(state.request)
      console.log('Operation result:', result)

      if (remember) {
        console.log('Adding permission...')
        addPermission(state.request, true)
      }

      console.log('Sending result...')
      await sendResult(state.request, result, true)

      console.log('Clearing state...')
      setState(DEFAULT_STATE)
      console.log('=== APPROVE REQUEST COMPLETED ===')
      return result
    } catch (error) {
      console.error('=== APPROVE REQUEST FAILED ===')
      console.error('Error details:', error)
      console.error('Error message:', error instanceof Error ? error.message : String(error))
      console.error('Error stack:', error instanceof Error ? error.stack : 'No stack trace')
      setState(prev => ({ ...prev, status: 'pending' }))
      throw error
    }
  }

  // Deny the request
  const deny = async (remember: boolean = false): Promise<void> => {
    if (!state.request) return

    setState(prev => ({ ...prev, status: 'denied' }))

    if (remember) {
      addPermission(state.request, false)
    }

    await sendResult(state.request, null, false)
    setState(DEFAULT_STATE)
  }

  // Dismiss the prompt without action
  const dismiss = () => {
    setState(DEFAULT_STATE)
  }

  // Show pending request (after user logs in)
  const showPending = () => {
    console.log('showPending called')
    if (state.pendingRequest) {
      console.log('Showing pending request:', state.pendingRequest)
      setState(prev => ({
        isOpen: true,
        request: prev.pendingRequest,
        status: 'pending',
        remember: false,
        pendingRequest: null
      }))
    } else {
      console.log('No pending request to show')
    }
  }

  // Clear pending request without showing it
  const clearPending = () => {
    console.log('clearPending called')
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