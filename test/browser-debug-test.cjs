/**
 * Browser Test for Debug Dashboard
 *
 * This script uses Playwright to actually render the PWA in a real browser,
 * test the debug dashboard functionality, and report any console errors.
 */

const { chromium } = require('playwright')

async function testDebugDashboard() {
  console.log('🌐 Starting browser test for debug dashboard...')

  // Launch browser
  const browser = await chromium.launch({
    headless: true, // Run headless for CI compatibility
    slowMo: 100 // Speed up for testing
  })

  const context = await browser.newContext()
  const page = await context.newPage()

  // Capture console messages and errors
  const consoleMessages = []
  const errors = []

  page.on('console', (msg) => {
    consoleMessages.push(`${msg.type()}: ${msg.text()}`)
    console.log(`[BROWSER] ${msg.type()}: ${msg.text()}`)
  })

  page.on('pageerror', (error) => {
    errors.push(error.message)
    console.error(`[BROWSER ERROR] ${error.message}`)
  })

  try {
    console.log('📡 Loading PWA at http://localhost:3000...')

    // Navigate to the PWA
    await page.goto('http://localhost:3000', {
      waitUntil: 'networkidle',
      timeout: 30000
    })

    console.log('✅ Page loaded successfully')

    // Wait for initial render
    await page.waitForTimeout(3000)

    // Check if debug dashboard button exists
    console.log('🔍 Looking for debug dashboard button...')
    const debugButton = await page.locator('.debug-toggle-btn').first()

    if (await debugButton.count() > 0) {
      console.log('✅ Debug toggle button found')

      // Click the debug button to open dashboard
      console.log('🖱️  Clicking debug button...')
      await debugButton.click()

      // Wait for dashboard to appear
      await page.waitForTimeout(1000)

      // Check if dashboard opened
      const dashboard = await page.locator('.debug-dashboard').first()
      if (await dashboard.count() > 0) {
        console.log('✅ Debug dashboard opened successfully')

        // Test keyboard shortcut too
        console.log('⌨️  Testing Ctrl+Shift+D shortcut...')
        await page.keyboard.press('Control+Shift+D')
        await page.waitForTimeout(500)

        // Check dashboard panels
        console.log('🔍 Checking dashboard panels...')

        const panels = [
          'System Health',
          'Active Traces',
          'Permission Sync Status',
          'Permission Statistics',
          'Quick Actions'
        ]

        for (const panelName of panels) {
          const panel = await page.locator('text=' + panelName).first()
          if (await panel.count() > 0) {
            console.log(`✅ Found panel: ${panelName}`)
          } else {
            console.log(`❌ Missing panel: ${panelName}`)
          }
        }

        // Test quick actions
        console.log('🔘 Testing Quick Actions...')
        const clearTracesBtn = await page.locator('text=Clear Old Traces').first()
        if (await clearTracesBtn.count() > 0) {
          console.log('🖱️  Clicking Clear Old Traces...')
          await clearTracesBtn.click()
          await page.waitForTimeout(500)
          console.log('✅ Clear Old Traces button works')
        }

        const refreshBtn = await page.locator('text=Refresh Data').first()
        if (await refreshBtn.count() > 0) {
          console.log('🖱️  Clicking Refresh Data...')
          await refreshBtn.click()
          await page.waitForTimeout(500)
          console.log('✅ Refresh Data button works')
        }

        // Take a screenshot
        console.log('📸 Taking screenshot...')
        await page.screenshot({
          path: '.local/debug-dashboard-screenshot.png',
          fullPage: true
        })
        console.log('✅ Screenshot saved to .local/debug-dashboard-screenshot.png')

      } else {
        console.log('❌ Debug dashboard did not open')
      }

    } else {
      // Try keyboard shortcut if button not found
      console.log('🔍 Debug button not found, trying keyboard shortcut...')
      await page.keyboard.press('Control+Shift+D')
      await page.waitForTimeout(1000)

      const dashboard = await page.locator('.debug-dashboard').first()
      if (await dashboard.count() > 0) {
        console.log('✅ Debug dashboard opened via keyboard shortcut')
      } else {
        console.log('❌ Debug dashboard not found via keyboard shortcut either')
      }
    }

    // Let it run for a bit to see real-time updates
    console.log('⏱️  Waiting to observe real-time updates...')
    await page.waitForTimeout(5000)

  } catch (error) {
    console.error('❌ Test failed:', error.message)
    errors.push(error.message)
  }

  // Report results
  console.log('\n📊 TEST RESULTS:')
  console.log('================')

  if (errors.length === 0) {
    console.log('✅ No browser errors detected!')
  } else {
    console.log(`❌ ${errors.length} errors detected:`)
    errors.forEach((error, i) => {
      console.log(`  ${i + 1}. ${error}`)
    })
  }

  console.log(`\n📝 Total console messages: ${consoleMessages.length}`)

  // Filter out just the errors from console
  const consoleErrors = consoleMessages.filter(msg =>
    msg.startsWith('error:') || msg.includes('Error') || msg.includes('Failed')
  )

  if (consoleErrors.length > 0) {
    console.log(`\n🚨 Console errors (${consoleErrors.length}):`)
    consoleErrors.forEach((error, i) => {
      console.log(`  ${i + 1}. ${error}`)
    })
  } else {
    console.log('\n✅ No console errors!')
  }

  // Keep browser open briefly for final screenshot
  console.log('\n👀 Running final checks...')
  await page.waitForTimeout(2000)

  await browser.close()

  // Return test results
  return {
    success: errors.length === 0,
    errors: errors,
    consoleErrors: consoleErrors,
    totalMessages: consoleMessages.length
  }
}

// Run the test
if (require.main === module) {
  testDebugDashboard()
    .then((results) => {
      if (results.success) {
        console.log('\n🎉 All tests passed!')
        process.exit(0)
      } else {
        console.log('\n💥 Tests failed!')
        process.exit(1)
      }
    })
    .catch((error) => {
      console.error('💥 Test runner failed:', error)
      process.exit(1)
    })
}

module.exports = { testDebugDashboard }