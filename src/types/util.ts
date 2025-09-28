import type { ReactNode } from 'react'

import type {
  BifrostNode,
  PeerData
} from '@frostr/bifrost'

export type NodeStatus = 'init' | 'disabled' | 'locked' | 'unlocking' | 'connecting' | 'peer-discovery' | 'signing-ready' | 'online' | 'offline'
export type LogType    = 'info'   | 'debug'    | 'error'  | 'warn'

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
