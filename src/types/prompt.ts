import { NIP55Request } from './signer.js'

export type PromptStatus   = 'pending' | 'approved' | 'denied'
export type PermissionType = 'action' | 'event'
export type PermissionMode = 'prompt' | 'automatic' | 'denied'

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

// Unified Permission format (matches ContentProvider format)
export interface Permission {
  appId: string
  type: string
  allowed: boolean
  timestamp: number
}

// Helper functions for permissions
export interface PermissionAPI {
  has_permission    : (host: string, type: string) => Promise<boolean>
  set_permission    : (host: string, type: string, allowed: boolean) => Promise<void>
  revoke_permission : (host: string, type: string) => Promise<void>
  list_permissions  : () => Promise<Permission[]>
}