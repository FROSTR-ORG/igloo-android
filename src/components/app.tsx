
import { Header } from '@/components/layout/header.js'
import { Tabs }   from '@/components/layout/tabs.js'
import { PromptManager } from '@/components/prompt/index.js'
import { useNIP55Handler } from '@/hooks/useNIP55Handler.js'

export function App () {
  // Initialize NIP-55 URL handling
  useNIP55Handler()

  return (
    <div className="app">
      <Header />
      <Tabs />
      <PromptManager />
    </div>
  )
}
