import { spawn, ChildProcess } from 'child_process'
import fs from 'fs'
import path from 'path'

import { NostrRelay } from './relay.js'
import { BifrostNode } from '@frostr/bifrost'
import { decode_group_pkg, decode_share_pkg } from '@frostr/bifrost/encoder'

const RELAY_PORT = parseInt(process.env['RELAY_PORT'] ?? '8080')
const NGROK_DOMAIN = process.env['NGROK_DOMAIN'] ?? 'relay.ngrok.dev'
const KEYSET_PATH = path.join(process.cwd(), 'keyset.json')

let relay: NostrRelay | null = null
let ngrokProcess: ChildProcess | null = null
let bifrostNode: BifrostNode | null = null

async function startRelay(): Promise<NostrRelay> {
  console.log(`\n==== [ Starting Relay ] `.padEnd(60, '='))
  const r = new NostrRelay(RELAY_PORT)
  await r.start()
  console.log(`Relay running on ws://localhost:${RELAY_PORT}`)
  return r
}

function startNgrok(): Promise<ChildProcess> {
  return new Promise((resolve, reject) => {
    console.log(`\n==== [ Starting ngrok ] `.padEnd(60, '='))
    console.log(`Forwarding ${NGROK_DOMAIN} → localhost:${RELAY_PORT}`)

    const ngrok = spawn('ngrok', ['http', `--domain=${NGROK_DOMAIN}`, String(RELAY_PORT)], {
      stdio: ['ignore', 'pipe', 'pipe']
    })

    ngrok.stdout?.on('data', (data: Buffer) => {
      const output = data.toString()
      if (output.trim()) {
        console.log(`[ngrok] ${output.trim()}`)
      }
    })

    ngrok.stderr?.on('data', (data: Buffer) => {
      const output = data.toString()
      if (output.trim()) {
        console.error(`[ngrok] ${output.trim()}`)
      }
    })

    ngrok.on('error', (err) => {
      console.error('Failed to start ngrok:', err.message)
      reject(err)
    })

    ngrok.on('close', (code) => {
      if (code !== 0 && code !== null) {
        console.log(`ngrok exited with code ${code}`)
      }
    })

    // Give ngrok a moment to start, then resolve
    setTimeout(() => {
      resolve(ngrok)
    }, 2000)
  })
}

interface Keyset {
  group: string
  shares: { idx: number; encoded: string }[]
  threshold: number
  totalShares: number
  createdAt: string
}

function loadKeyset(): Keyset {
  if (!fs.existsSync(KEYSET_PATH)) {
    throw new Error(`Keyset file not found: ${KEYSET_PATH}\nRun 'npm run keygen' first to generate keys.`)
  }
  const content = fs.readFileSync(KEYSET_PATH, 'utf-8')
  return JSON.parse(content) as Keyset
}

async function startBifrostNode(relayUrl: string): Promise<BifrostNode> {
  console.log(`\n==== [ Starting Bifrost Node ] `.padEnd(60, '='))

  const keyset = loadKeyset()
  console.log(`Loaded keyset (${keyset.threshold}-of-${keyset.totalShares} threshold)`)

  // Decode the group package
  const group = decode_group_pkg(keyset.group)
  console.log(`Group pubkey: ${group.group_pk.slice(0, 16)}...`)

  // Use the first share
  const firstShare = keyset.shares[0]
  if (!firstShare) {
    throw new Error('No shares found in keyset')
  }

  const share = decode_share_pkg(firstShare.encoded)
  console.log(`Using share #${firstShare.idx}`)

  // Create the bifrost node
  const node = new BifrostNode(group, share, [relayUrl])

  // Set up event handlers
  node.on('ready', () => {
    console.log(`[bifrost] Node ready, connected to relay`)
  })

  node.on('closed', () => {
    console.log(`[bifrost] Connection closed`)
  })

  node.on('error', (err) => {
    console.error(`[bifrost] Error:`, err)
  })

  // Connect the node
  node.connect()

  // Wait for ready
  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error('Bifrost node connection timeout'))
    }, 10000)

    node.on('ready', () => {
      clearTimeout(timeout)
      resolve()
    })

    node.on('error', (err) => {
      clearTimeout(timeout)
      reject(err)
    })
  })

  console.log(`Bifrost node online`)
  return node
}

function cleanup() {
  console.log('\n\nShutting down...')

  if (bifrostNode) {
    console.log('Stopping bifrost node...')
    bifrostNode.close()
    bifrostNode = null
  }

  if (ngrokProcess) {
    console.log('Stopping ngrok...')
    ngrokProcess.kill('SIGTERM')
    ngrokProcess = null
  }

  if (relay) {
    console.log('Stopping relay...')
    relay.close()
    relay = null
  }

  console.log('Done.')
  process.exit(0)
}

// Handle shutdown signals
process.on('SIGINT', cleanup)
process.on('SIGTERM', cleanup)

// Main
async function main() {
  console.log('==== [ FROSTR Bench ] '.padEnd(60, '='))

  try {
    // Start the relay first
    relay = await startRelay()

    // Start ngrok tunnel
    ngrokProcess = await startNgrok()

    // Start bifrost node connected to local relay
    const localRelayUrl = `ws://localhost:${RELAY_PORT}`
    bifrostNode = await startBifrostNode(localRelayUrl)

    // Display final status
    console.log(`\n==== [ Bench Ready ] `.padEnd(60, '='))
    console.log(`Local relay:   ws://localhost:${RELAY_PORT}`)
    console.log(`Public relay:  wss://${NGROK_DOMAIN}`)
    console.log(`Bifrost node:  online (share #1)`)
    console.log(`Group pubkey:  ${decode_group_pkg(loadKeyset().group).group_pk}`)
    console.log(`\nPress Ctrl+C to stop.\n`)

  } catch (err) {
    console.error('Failed to start bench:', err)
    cleanup()
  }
}

main()
