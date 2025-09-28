import { createContext, useContext, useState } from 'react'

import type { ReactElement }                       from 'react'
import type { CacheData, CacheAPI, ProviderProps } from '@/types/index.js'

const DEFAULT_CACHE : CacheData = {
  pubkey : null,
  share  : null
}

const context = createContext<CacheAPI<CacheData> | null>(null)

export const CacheProvider = ({ children }: ProviderProps): ReactElement => {
  const [ _store, _setStore ] = useState<CacheData>(DEFAULT_CACHE)

  const reset = () => {
    _setStore(DEFAULT_CACHE)
  }

  const update = (store: Partial<CacheData>) => {
    _setStore({ ..._store, ...store })
  }

  return (
    <context.Provider value={{ data : _store, reset, update }}>
      {children}
    </context.Provider>
  )
}

export const useCache = () => {
  const ctx = useContext(context)
  if (ctx === null) {
    throw new Error('useCache must be used within a CacheProvider')
  }
  return ctx
}
