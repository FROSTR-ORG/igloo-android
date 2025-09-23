import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App }        from '@/components/app.js'

import { ConsoleProvider }  from '@/context/console.js'
import { NodeProvider }     from '@/context/node.js'
import { SettingsProvider } from '@/context/settings.js'
import { PromptProvider }   from '@/context/prompt.js'

import './styles/global.css'
import './styles/layout.css'
import './styles/node.css'
import './styles/console.css'
import './styles/sessions.css'
import './styles/settings.css'
import './styles/scanner.css'
import './styles/prompt.css'
import './styles/banner.css'

// PWA Install debugging and engagement tracking
let deferredPrompt: any
let userHasInteracted = false
let pageViewStartTime = Date.now()

// Track user engagement for install criteria
window.addEventListener('click', () => {
  if (!userHasInteracted) {
    userHasInteracted = true
    console.log('PWA: User has interacted with the page')
  }
}, { once: true })

window.addEventListener('touchstart', () => {
  if (!userHasInteracted) {
    userHasInteracted = true
    console.log('PWA: User has interacted with the page (touch)')
  }
}, { once: true })

// Track 30 second view time requirement
setTimeout(() => {
  const viewTime = Date.now() - pageViewStartTime
  console.log(`PWA: User has viewed page for ${Math.round(viewTime / 1000)} seconds`)
  if (viewTime >= 30000) {
    console.log('PWA: 30+ second engagement requirement met')
  }
}, 30000)

window.addEventListener('beforeinstallprompt', (e) => {
  console.log('PWA: beforeinstallprompt event fired')
  e.preventDefault()
  deferredPrompt = e
  console.log('PWA: Install prompt is available')
})

window.addEventListener('appinstalled', () => {
  console.log('PWA: App was installed')
  deferredPrompt = null
})

// Check if app is already installed
if (window.matchMedia('(display-mode: standalone)').matches) {
  console.log('PWA: App is running in standalone mode')
}

// Register service worker
if ('serviceWorker' in navigator) {
  window.addEventListener('load', async () => {
    try {
      // Configure the service worker options.
      const options = { scope: '/' }
      // Register the service worker.
      const worker  = await navigator.serviceWorker.register('/sw.js', options)
      // If the worker is not active, throw an error.
      if (!worker.active) throw new Error('[ app ] worker returned null')
      // Log the worker registered with scope.
      console.log('[ app ] worker registered with scope:', worker.scope)
    } catch (error) {
      // Log the worker registration failed.
      console.error('[ app ] worker registration failed:', error)
    }
  })
}

// Fetch the root container.
const container = document.getElementById('root')

// If the root container is not found, throw an error.
if (!container) throw new Error('[ app ] root container not found')

// Create the react root element.
const root = createRoot(container)

// Render the app.
root.render(
  <StrictMode>
    <SettingsProvider>
      <ConsoleProvider>
        <NodeProvider>
          <PromptProvider>
            <App />
          </PromptProvider>
        </NodeProvider>
      </ConsoleProvider>
    </SettingsProvider>
  </StrictMode>
)
