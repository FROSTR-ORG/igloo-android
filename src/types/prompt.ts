import type { NIP55Request }           from './signer.js'
import type { BulkPermissionRequest }  from './permissions.js'

export type PromptStatus   = 'pending' | 'approved' | 'denied'
export type PermissionType = 'action' | 'event'
export type PermissionMode = 'prompt' | 'automatic' | 'denied'

export interface PromptState {
  isOpen   : boolean
  request  : NIP55Request | null
  status   : PromptStatus
  remember : boolean
}

export interface BulkPermissionState {
  isOpen   : boolean
  request  : BulkPermissionRequest | null
}

export interface PromptAPI {
  state                    : PromptState
  bulkPermissionState      : BulkPermissionState
  showPrompt               : (request: NIP55Request) => void
  showBulkPermissionPrompt : (request: BulkPermissionRequest) => Promise<{ approved: boolean; pubkey?: string }>
  approve                  : (remember?: boolean) => Promise<any>
  deny                     : (remember?: boolean) => Promise<void>
  dismiss                  : () => void
}