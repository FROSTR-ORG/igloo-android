import { StoreController } from '../class/store.js'

import {
  createContext,
  useContext,
  useState
} from 'react'

import type { ReactElement } from 'react'

import type {
  SettingsData,
  StoreAPI,
  ProviderProps
}  from '@/types/index.js'

export const DEFAULT_STORE : SettingsData = {
  group  : null,
  share  : null,
  peers  : [],
  pubkey : null,
  relays : []
}

const STORE_KEY  = 'igloo-pwa'
const params     = new URLSearchParams(window.location.search)
const username   = params.get('user')
const store_key  = username ? `${STORE_KEY}-${username}` : STORE_KEY

const context    = createContext<StoreAPI<SettingsData> | null>(null)
const controller = new StoreController<SettingsData>(store_key, DEFAULT_STORE)

export const SettingsProvider = ({ children }: ProviderProps): ReactElement => {
  const [ _store, _setStore ] = useState<SettingsData>(controller.get())

  const reset = () => {
    controller.reset()
    _setStore(controller.defaults)
  }

  const subscribe = (callback: () => void) => {
    return controller.subscribe(callback)
  }

  const update = (store: Partial<SettingsData>) => {
    const new_store = { ..._store, ...store }
    controller.set(new_store)
    _setStore(new_store)
  }

  return (
    <context.Provider value={{ data : _store, reset, subscribe, update }}>
      {children}
    </context.Provider>
  )
}

export const useSettings = () => {
  const ctx = useContext(context)
  if (ctx === null) {
    throw new Error('useSettings must be used within a SettingsProvider')
  }
  return ctx
}
