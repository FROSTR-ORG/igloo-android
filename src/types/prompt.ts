import { NIP55Request } from './signer.js'

export type PromptStatus   = 'pending' | 'approved' | 'denied'
export type PermissionType = 'action' | 'event'
export type PermissionMode = 'prompt' | 'automatic' | 'denied'

export interface PromptState {
  isOpen        : boolean
  request       : NIP55Request | null
  status        : PromptStatus
  remember      : boolean
}

// Bulk permission request (from get_public_key with permissions array)
export interface BulkPermissionRequest {
  appId: string
  permissions: Array<{ type: string; kind?: number }>
}

export interface BulkPermissionState {
  isOpen: boolean
  request: BulkPermissionRequest | null
}

export interface PromptAPI {
  state         : PromptState
  bulkPermissionState: BulkPermissionState
  showPrompt    : (request: NIP55Request) => void
  showBulkPermissionPrompt: (request: BulkPermissionRequest) => Promise<{ approved: boolean; pubkey?: string }>
  approve       : (remember?: boolean) => Promise<any>
  deny          : (remember?: boolean) => Promise<void>
  dismiss       : () => void
}

// Unified Permission format (matches ContentProvider format)
export interface Permission {
  appId: string
  type: string
  kind?: number  // NEW: Optional event kind filter
  allowed: boolean
  timestamp: number
}

// Helper functions for permissions
export interface PermissionAPI {
  has_permission    : (host: string, type: string, kind?: number) => Promise<boolean>
  set_permission    : (host: string, type: string, allowed: boolean, kind?: number) => Promise<void>
  revoke_permission : (host: string, type: string, kind?: number) => Promise<void>
  list_permissions  : (host?: string) => Promise<Permission[]>
  bulk_set_permissions : (rules: Permission[]) => Promise<void>  // NEW: Bulk operations
  revoke_all_for_app   : (host: string) => Promise<void>  // NEW: Revoke all for app
}