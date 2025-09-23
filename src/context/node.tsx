import { createContext, useContext } from 'react'
import { useBifrost }                from '@/hooks/useBifrost.js'

import type { ReactElement }                  from 'react'
import type { BifrostNodeAPI, ProviderProps } from '@/types.js'

const context = createContext<BifrostNodeAPI | null>(null)

export const NodeProvider = ({ children }: ProviderProps): ReactElement => {
  // Get the node API.
  const node = useBifrost()

  return (
    <context.Provider value={node}>
      {children}
    </context.Provider>
  )
}

export const useBifrostNode = () => {
  const ctx = useContext(context)
  if (ctx === null) {
    throw new Error('useBifrostNode must be used within a NodeProvider')
  }
  return ctx
}
