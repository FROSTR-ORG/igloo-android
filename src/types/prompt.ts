import { NIP55Request } from './signer.js'

export type PromptStatus   = 'pending' | 'approved' | 'denied'
export type PermissionType = 'action' | 'event'

export interface PromptState {
  isOpen        : boolean
  request       : NIP55Request | null
  status        : PromptStatus
  remember      : boolean
  pendingRequest: NIP55Request | null  // Request waiting for user to login
}

export interface PromptAPI {
  state         : PromptState
  showPrompt    : (request: NIP55Request) => void
  approve       : (remember?: boolean) => Promise<any>
  deny          : (remember?: boolean) => Promise<void>
  dismiss       : () => void
  showPending   : () => void  // Show the pending request after login
  clearPending  : () => void  // Clear pending request
}