import { Header }      from '@/components/layout/header.js'
import { Tabs }        from '@/components/layout/tabs.js'
import { NIP55Bridge } from '@/components/nip55-bridge.js'

/**
 * Main application component
 *
 * Renders the core UI and initializes the NIP-55 signing bridge
 */
export function App () {
  return (
    <div className="app">
      <Header />
      <Tabs />
      <NIP55Bridge />
    </div>
  )
}