import { createContext, useContext, useState } from 'react'

import { Assert }    from '@vbyte/micro-lib'
import { LOG_LIMIT } from '@/const'

import type {
  LogEntry,
  LogType,
  ProviderProps,
  WebConsoleAPI
} from '@/types/index.js'

import type { ReactElement } from 'react'

const context = createContext<WebConsoleAPI | null>(null)

export const ConsoleProvider = ({ children }: ProviderProps): ReactElement => {
  // Define the logs state.
  const [ logs, setLogs ] = useState<LogEntry[]>([])
  // Define the add method.
  const add = (msg : string, type : LogType, payload? : any) => {
    // Create a new log entry.
    const new_log = create_log(msg, type, payload)
    // Update the logs array.
    setLogs(prev_logs => {
      // Create a new logs array.
      let new_logs : LogEntry[] = [ ...prev_logs, new_log ]
      // If the logs array is greater than the LOG_LIMIT,
      if (prev_logs.length >= LOG_LIMIT) {
        // Calculate the difference between the logs and the LOG_LIMIT.
        const diff = new_logs.length - LOG_LIMIT
        // Assert that the difference is greater than 0.
        Assert.ok(diff > 0, 'diff must be greater than 0')
        // Update the new logs array.
        new_logs = new_logs.slice(diff)
      }
      // Return the new logs array.
      return new_logs
    })
  }
  // Define the clear method.
  const clear = () => {
    // Update the logs array with an empty array.
    setLogs([])
  }

  return (
    <context.Provider value={{ logs, clear, add }}>
      {children}
    </context.Provider>
  )
}

export const useWebConsole = () => {
  const ctx = useContext(context)
  if (ctx === null) {
    throw new Error('useWebConsole must be used within a ConsoleProvider')
  }
  return ctx
}

function create_log (
  msg      : string,
  type     : LogType,
  payload? : any
) : LogEntry {
  // Create a new log entry.
  return {
    stamp   : Date.now(),
    message : msg,
    type    : type,
    payload : payload
  }
}