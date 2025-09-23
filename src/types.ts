import type { ReactNode } from 'react'

import type {
  BifrostNode,
  GroupPackage,
  PeerConfig,
  PeerData,
  SharePackage
} from '@frostr/bifrost'

export type NodeStatus       = 'init'   | 'disabled' | 'locked' | 'online' | 'offline'
export type LogType          = 'info'   | 'debug'    | 'error'  | 'warn'
export type PermissionType   = 'action' | 'event'
export type NIP55RequestType = 'get_public_key' | 'sign_event' | 'nip04_encrypt' | 'nip04_decrypt' | 'nip44_encrypt' | 'nip44_decrypt' | 'decrypt_zap_event'
export type PromptStatus     = 'pending' | 'approved' | 'denied'

export interface ProviderProps {
  children : ReactNode
}

export interface WebConsoleAPI {
  logs  : LogEntry[]
  clear : () => void
  add   : (msg : string, type : LogType, payload? : any) => void
}

export interface BifrostNodeAPI {
  clear  : () => void
  client : BifrostNode | null
  peers  : PeerData[]
  ping   : (pubkey : string) => void
  reset  : () => void
  status : NodeStatus
  unlock : (password : string) => void
}

export interface LogEntry {
  message  : string
  stamp    : number
  type     : LogType
  payload? : any
}

export interface RelayPolicy {
  url   : string
  read  : boolean
  write : boolean
}

export interface CacheData {
  pubkey : string | null
  share  : SharePackage | null
}

export interface PermissionRecord {
  host       : string
  type       : string
  accept     : boolean
  created_at : number
}

export interface PermActionRecord extends PermissionRecord {
  action : string
  type   : 'action'
}

export interface PermEventRecord extends PermissionRecord {
  kind : number
  type : 'event'
}

export interface PermissionPolicy {
  action : PermActionRecord[]
  event  : PermEventRecord[]
}

export interface SettingsData {
  group  : GroupPackage | null
  share  : string       | null
  peers  : PeerConfig[]
  perms  : PermissionPolicy[]
  pubkey : string       | null
  relays : RelayPolicy[]
}

export interface CacheAPI<T> {
  data      : T
  reset     : () => void
  update    : (store: Partial<T>) => void
}

export interface StoreAPI<T> extends CacheAPI<T> {
  subscribe : (callback : () => void) => () => void
}

// NIP-55 Request Types
export interface BaseNIP55Request {
  type         : NIP55RequestType
  id?          : string
  callbackUrl? : string
  current_user?: string
  host         : string
}

export interface GetPublicKeyRequest extends BaseNIP55Request {
  type : 'get_public_key'
}

export interface SignEventRequest extends BaseNIP55Request {
  type  : 'sign_event'
  event : any // Event template to sign
}

export interface EncryptRequest extends BaseNIP55Request {
  type      : 'nip04_encrypt' | 'nip44_encrypt'
  plaintext : string
  pubkey    : string
}

export interface DecryptRequest extends BaseNIP55Request {
  type       : 'nip04_decrypt' | 'nip44_decrypt'
  ciphertext : string
  pubkey     : string
}

export interface DecryptZapRequest extends BaseNIP55Request {
  type  : 'decrypt_zap_event'
  event : any // Zap event to decrypt
}

export type NIP55Request = GetPublicKeyRequest | SignEventRequest | EncryptRequest | DecryptRequest | DecryptZapRequest

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

// Android Bridge Types
export interface AndroidSecureStorage {
  storeSecret(key: string, value: string): boolean
  getSecret(key: string): string | null
  hasSecret(key: string): boolean
  deleteSecret(key: string): boolean
  clearAllSecrets(): void
  getDeviceInfo(): string
  log(message: string): void
}

export interface AndroidSessionPersistence {
  savePassword(password: string): boolean
  clearPassword(): boolean
}

declare global {
  interface Window {
    AndroidSecureStorage?: AndroidSecureStorage
    androidSessionPersistence?: AndroidSessionPersistence
  }
}
