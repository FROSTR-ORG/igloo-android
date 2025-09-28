import { useEffect, useState } from 'react'

import { BifrostNode }      from '@frostr/bifrost'
import { decrypt_content }  from '@/lib/enclave.js'
import { decode_share_pkg } from '@frostr/bifrost/encoder'
import { convert_pubkey }   from '@frostr/bifrost/util'
import { useWebConsole }    from '@/context/console.js'
import { useSettings }      from '@/context/settings.js'

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
    })

    _client.on('closed', () => {
      setStatus('offline')
    })

    _client.on('error', () => {
      setStatus('offline')
    })

    _client.on('/ping/handler/ret', () => {
      console.log('ping/handler/ret', _client.peers)
      setPeers(_client.peers.slice())
    })

    _client.on('/ping/sender/ret', () => {
      console.log('ping/sender/ret', _client.peers)
      setPeers(_client.peers.slice())
    })

    _client.on('*', (event, data) => {
      // Skip message events.
      if (event === 'message') return
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
    sessionStorage.removeItem('igloo_session_password')
    if (!is_store_ready(settings.data)) {
      setStatus('init')
    } else {
      setStatus('locked')
    }
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
    sessionStorage.setItem('igloo_session_password', password)
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

  return { clear, client, peers, ping, reset, status, unlock }
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
    console.log('disabled')
    return 'disabled'
  } else if (client === null) {
    console.log('locked')
    return 'locked'
  } else if (client.is_ready) {
    console.log('online')
    return 'online'
  } else {
    console.log('offline')
    return 'offline'
  }
}
