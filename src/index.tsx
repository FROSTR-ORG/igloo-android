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
