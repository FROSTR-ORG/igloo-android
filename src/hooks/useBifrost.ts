import { useEffect, useState, useRef } from 'react'

import { BifrostNode }      from '@frostr/bifrost'
import { decrypt_content }  from '@/lib/enclave.js'
import { decode_share_pkg } from '@frostr/bifrost/encoder'
import { convert_pubkey }   from '@frostr/bifrost/util'
import { useWebConsole }    from '@/context/console.js'
import { useSettings }      from '@/context/settings.js'
import { STORAGE_KEYS }     from '@/const.js'

import type {
  GroupPackage,
  PeerData,
  SharePackage
} from '@frostr/bifrost'

import type {
  BifrostNodeAPI,
  LogType,
  NodeStatus,
  SettingsData,
} from '@/types/index.js'

interface FullSettingsData extends SettingsData {
  group  : GroupPackage
  pubkey : string
  share  : string
}

export function useBifrost () : BifrostNodeAPI {
  const [ client, setClient ] = useState<BifrostNode | null>(null)
  const [ peers, setPeers ]   = useState<PeerData[]>([])
  const [ status, setStatus ] = useState<NodeStatus>('init')

  // Track reconnection state
  const reconnectAttempts = useRef(0)
  const reconnectTimer    = useRef<ReturnType<typeof setTimeout> | null>(null)
  const maxReconnectAttempts = 5
  const baseReconnectDelay   = 2000 // 2 seconds

  const logger   = useWebConsole()
  const settings = useSettings()

  const _start = (share : SharePackage) => {
    if (!is_store_ready(settings.data)) {
      logger.add('node tried to start with missing settings', 'error')
      return
    }
    // Get the group from the store.
    const { group, peers, relays } = settings.data
    // Get the peers from the store.
    const policies = peers.map(p => ({
      pubkey : convert_pubkey(p.pubkey, 'bip340'),
      policy : { send : p.policy.send, recv : p.policy.recv }
    }))
    // Get the URLs from the relays.
    const urls    = relays.map(r => r.url)
    // Create a new node.
    const _client = new BifrostNode(group, share, urls, { policies })
    // Register listeners.
    _client.on('ready', () => {
      _client.req.echo('echo')
      logger.add('bifrost node initialized', 'info')
      setPeers(_client.peers.slice())
      setStatus('online')

      // Reset reconnection counter on successful connection
      reconnectAttempts.current = 0

      // Ping all peers using _client directly (not the state variable which may not be updated yet)
      for (const peer of _client.peers) {
        _client.req.ping(peer.pubkey)
      }
    })

    _client.on('closed', () => {
      setStatus('offline')
    })

    _client.on('error', () => {
      setStatus('offline')
    })

    _client.on('/ping/handler/ret', () => {
      setPeers(_client.peers.slice())
    })

    _client.on('/ping/sender/ret', () => {
      setPeers(_client.peers.slice())
    })

    _client.on('*', (event, data) => {
      // Skip message events.
      if (event === 'message')       return
      if (event.startsWith('/echo')) return
      if (event.startsWith('/ping')) return

      let type: LogType = 'info' // Default log type
      let message = String(event)
      let payload: any = data

      // Determine log type and refine message based on event string
      if (message.toLowerCase() === 'ready') {
        type    = 'info'
        payload = undefined
      } else if (message.startsWith('/sign')) {
        type = 'info' // General sign type, can be refined
        if (message.endsWith('/req')) {
          type = 'info'
        } else if (message.endsWith('/res')) {
          type = 'info'
        }
      } else if (message.startsWith('/error')) {
        type = 'error'
      } // Add more specific event type handling as needed

      // If payload is an empty object, set it to undefined so no dropdown is shown
      if (typeof payload === 'object' && payload !== null && Object.keys(payload).length === 0) {
        payload = undefined
      }

      logger.add(message, type, payload)
    })
    // Connect the client.
    _client.connect()
    // Set the client state.
    setClient(_client)
  }

  const clear = () => {
    // Clear the node.
    setClient(null)
    // Clear session password on logout
    sessionStorage.removeItem(STORAGE_KEYS.SESSION_PASSWORD)
    if (!is_store_ready(settings.data)) {
      setStatus('init')
    } else {
      setStatus('locked')
    }
  }

  const lock = () => {
    // Close the client connection if it exists
    if (client !== null) {
      try {
        client.close()
      } catch (e) {
        // Ignore errors when closing
      }
    }
    // Clear the node state
    setClient(null)
    // Clear session password from sessionStorage
    sessionStorage.removeItem(STORAGE_KEYS.SESSION_PASSWORD)
    // Also clear the Android session backup if running in WebView
    // This prevents auto-unlock after app restart
    if (typeof window !== 'undefined' && (window as any).StorageBridge?.clearSessionStorageAndBackup) {
      try {
        (window as any).StorageBridge.clearSessionStorageAndBackup()
      } catch (e) {
        // Ignore errors - may not be running in Android WebView
      }
    }
    // Set status to locked (settings should still be ready)
    setStatus('locked')
    logger.add('node locked by user', 'info')
  }

  const ping = (pubkey : string) => {
    // Return early if the node is not ready.
    if (client === null) return
    // Ping the peer.
    client.req.ping(pubkey)
  }

  const reset = () => {
    // Clear the node if the store is not ready.
    if (!is_store_ready(settings.data)) clear()
    // Return early if the node is not ready.
    if (client === null) return
    // Get the share from the current node.
    const share = (client.signer as any)._share
    // Restart the node with the share.
    _start(share)
  }

  const unlock = (password : string) => {
    // Return early if the store is not ready.
    if (!is_store_ready(settings.data)) return

    // Decrypt the share.
    const decrypted = decrypt_content(settings.data.share, password)
    // Return early if the share is not decrypted.
    if (!decrypted) {
      logger.add('failed to decrypt share', 'error')
      return
    }
    // Store password in sessionStorage for auto-unlock after URI refresh
    sessionStorage.setItem(STORAGE_KEYS.SESSION_PASSWORD, password)
    // Decode the share.
    const share = decode_share_pkg(decrypted)
    // Start the node with the share.
    _start(share)
  }

  useEffect(() => {
    // Get the node's current status.
    const status = get_node_status(client, settings.data)
    // Set the node's status.
    setStatus(status)
  }, [ settings.data, client ])

  // Reset node when core configuration changes
  useEffect(() => {
    reset()
  }, [ settings.data.peers, settings.data.relays ])

  // Notify Android of node state changes for foreground service control
  // The foreground service is always enabled for reliable background signing
  useEffect(() => {
    // Only run in Android WebView
    if (typeof window === 'undefined' || !(window as any).NodeStateBridge) {
      return
    }

    const bridge = (window as any).NodeStateBridge

    if (status === 'online') {
      // Node is online - start persistent foreground service
      try {
        bridge.onNodeOnline()
        console.log('[useBifrost] Notified Android: node online - starting foreground service')
      } catch (e) {
        console.warn('[useBifrost] Failed to notify Android of node online:', e)
      }
    } else if (status === 'locked' || status === 'disabled' || status === 'init') {
      // Node is locked or not configured - stop foreground service
      try {
        bridge.onNodeOffline()
        console.log('[useBifrost] Notified Android: node offline - stopping foreground service')
      } catch (e) {
        console.warn('[useBifrost] Failed to notify Android of node offline:', e)
      }
    }
    // Note: 'offline' status (disconnected but unlocked) keeps service running for reconnection
  }, [ status ])

  // Auto-reconnect when connection drops
  useEffect(() => {
    // Only attempt reconnection if:
    // 1. Status is 'offline' (not 'locked' or 'init')
    // 2. We have a client (means we were previously connected)
    // 3. We haven't exceeded max reconnection attempts
    if (status !== 'offline' || client === null) {
      return
    }

    if (reconnectAttempts.current >= maxReconnectAttempts) {
      logger.add(`Max reconnection attempts (${maxReconnectAttempts}) reached`, 'error')
      return
    }

    // Clear any existing timer
    if (reconnectTimer.current) {
      clearTimeout(reconnectTimer.current)
    }

    // Calculate delay with exponential backoff
    const delay = baseReconnectDelay * Math.pow(2, reconnectAttempts.current)
    reconnectAttempts.current++

    logger.add(`Connection lost. Reconnecting in ${delay / 1000}s (attempt ${reconnectAttempts.current}/${maxReconnectAttempts})`, 'info')

    reconnectTimer.current = setTimeout(() => {
      reconnectTimer.current = null
      if (client !== null) {
        logger.add('Attempting to reconnect...', 'info')
        try {
          // Try to reconnect the existing client
          client.connect()
        } catch (e) {
          logger.add('Reconnection failed, will retry', 'error')
        }
      }
    }, delay)

    // Cleanup timer on unmount or status change
    return () => {
      if (reconnectTimer.current) {
        clearTimeout(reconnectTimer.current)
        reconnectTimer.current = null
      }
    }
  }, [ status, client ])

  return { clear, client, lock, peers, ping, reset, status, unlock }
}

function is_store_ready (
  store : SettingsData
) : store is FullSettingsData {
  return store.group !== null && store.share !== null && store.relays.length > 0
}

function get_node_status (
  client   : BifrostNode | null,
  settings : SettingsData
) : NodeStatus {
  if (!is_store_ready(settings)) {
    return 'disabled'
  } else if (client === null) {
    return 'locked'
  } else if (client.is_ready) {
    return 'online'
  } else {
    return 'offline'
  }
}
