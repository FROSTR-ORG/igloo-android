import type {
  GroupPackage,
  PeerConfig,
  SharePackage
} from '@frostr/bifrost'

import type { Permission } from './prompt.js'

export interface RelayPolicy {
  url   : string
  read  : boolean
  write : boolean
}

export interface CacheData {
  pubkey : string | null
  share  : SharePackage | null
}

export interface SettingsData {
  group       : GroupPackage | null
  share       : string       | null
  peers       : PeerConfig[]
  pubkey      : string       | null
  relays      : RelayPolicy[]
}

export interface CacheAPI<T> {
  data      : T
  reset     : () => void
  update    : (store: Partial<T>) => void
}

export interface StoreAPI<T> extends CacheAPI<T> {
  subscribe : (callback : () => void) => () => void
}
