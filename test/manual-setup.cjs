/**
 * Manual Setup Browser
 *
 * Opens a browser window for manual configuration of the Bifrost node
 * and NIP-55 setup. Keeps the browser open indefinitely for interaction.
 */

const { chromium } = require('playwright')

async function openManualSetupBrowser() {
  console.log('🌐 Opening browser for manual Bifrost node configuration...')
  console.log('📍 URL: http://localhost:3000')

  const browser = await chromium.launch({
    headless: false,     // Show the browser window
    slowMo: 100,         // Slight delay for visibility
    devtools: true,      // Open DevTools automatically
    args: [
      '--start-maximized',
      '--disable-web-security',
      '--disable-features=VizDisplayCompositor'
    ]
  })

  const context = await browser.newContext({
    viewport: null  // Use full screen
  })

  const page = await context.newPage()

  // Monitor console for important messages
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.includes('Bridge') || text.includes('client') || text.includes('status') ||
        text.includes('NIP55') || text.includes('locked') || text.includes('disabled') ||
        text.includes('online')) {
      console.log(`[BROWSER] ${text}`)
    }
  })

  // Monitor page errors
  page.on('pageerror', (error) => {
    console.error(`[PAGE ERROR] ${error.message}`)
  })

  try {
    console.log('📡 Navigating to PWA...')
    await page.goto('http://localhost:3000', {
      waitUntil: 'networkidle',
      timeout: 30000
    })

    console.log('✅ PWA loaded successfully!')
    console.log('')
    console.log('🔧 MANUAL SETUP INSTRUCTIONS:')
    console.log('=============================')
    console.log('1. 📱 The browser window is now open')
    console.log('2. 🔓 Configure/unlock your Bifrost node in the PWA')
    console.log('3. ⚙️  Set up any necessary settings or permissions')
    console.log('4. 🧪 Test the NIP-55 functionality when ready')
    console.log('5. 🐛 Open debug dashboard with Ctrl+Shift+D')
    console.log('6. 🧪 Open test runner with Ctrl+Shift+T')
    console.log('')
    console.log('🔍 Monitoring bridge status...')

    // Periodically check bridge status
    setInterval(async () => {
      try {
        const status = await page.evaluate(() => {
          return {
            hasNostr: typeof window?.nostr !== 'undefined',
            hasNip55: typeof window?.nostr?.nip55 !== 'undefined',
            timestamp: new Date().toLocaleTimeString()
          }
        })

        if (status.hasNip55) {
          console.log(`🎉 [${status.timestamp}] NIP-55 Bridge is ACTIVE! window.nostr.nip55 is available`)
        } else if (status.hasNostr) {
          console.log(`⏳ [${status.timestamp}] window.nostr exists but nip55 not yet available`)
        } else {
          console.log(`⏳ [${status.timestamp}] Waiting for bridge initialization...`)
        }
      } catch (error) {
        // Page might be navigating, ignore errors
      }
    }, 10000) // Check every 10 seconds

    console.log('')
    console.log('👀 Browser will stay open indefinitely...')
    console.log('   Press Ctrl+C in this terminal to close when done.')
    console.log('')

    // Wait indefinitely (until user stops the process)
    await new Promise(() => {})

  } catch (error) {
    console.error('❌ Setup failed:', error.message)
    await browser.close()
    process.exit(1)
  }
}

// Handle Ctrl+C gracefully
process.on('SIGINT', () => {
  console.log('\n👋 Closing browser...')
  process.exit(0)
})

if (require.main === module) {
  openManualSetupBrowser()
    .catch((error) => {
      console.error('💥 Failed to open setup browser:', error)
      process.exit(1)
    })
}

module.exports = { openManualSetupBrowser }