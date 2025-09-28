/**
 * Browser Test for NIP-55 Functionality
 *
 * This script tests the actual NIP-55 signing functionality within the PWA
 * using the integrated test runner component.
 */

const { chromium } = require('playwright')

async function testNIP55Functionality() {
  console.log('🔐 Starting NIP-55 functionality browser test...')

  const browser = await chromium.launch({
    headless: false, // Show browser so we can see the test runner
    slowMo: 500 // Slow down for visibility
  })

  const context = await browser.newContext()
  const page = await context.newPage()

  // Capture console messages
  const consoleMessages = []
  const errors = []

  page.on('console', (msg) => {
    consoleMessages.push(`${msg.type()}: ${msg.text()}`)
    if (msg.type() === 'error') {
      console.log(`[BROWSER ERROR] ${msg.text()}`)
    }
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
    await page.waitForTimeout(3000)

    // Look for NIP-55 test runner button (🧪)
    console.log('🔍 Looking for NIP-55 test runner button...')
    const testRunnerButton = await page.locator('.nip55-test-toggle-btn').first()

    if (await testRunnerButton.count() > 0) {
      console.log('✅ NIP-55 test runner button found')

      // Click to open test runner
      console.log('🖱️  Opening NIP-55 test runner...')
      await testRunnerButton.click()
      await page.waitForTimeout(1000)

      // Check if test runner opened
      const testRunner = await page.locator('.nip55-test-runner').first()
      if (await testRunner.count() > 0) {
        console.log('✅ NIP-55 test runner opened successfully')

        // Test basic signing flow
        console.log('🧪 Running Basic Signing Flow test...')
        const basicTestBtn = await page.locator('text=Run Test').first()

        if (await basicTestBtn.count() > 0) {
          await basicTestBtn.click()
          console.log('🔄 Basic signing flow test started...')

          // Wait for test completion (look for results)
          await page.waitForTimeout(5000)

          // Check for test results
          const testResults = await page.locator('.test-results').first()
          if (await testResults.count() > 0) {
            console.log('✅ Test results displayed')

            // Try to extract results
            const resultsText = await testResults.innerText()
            console.log('📊 Test Results:', resultsText)
          }

          // Check for active requests
          const activeRequests = await page.locator('.active-requests').first()
          if (await activeRequests.count() > 0) {
            const requestsText = await activeRequests.innerText()
            console.log('🔄 Active Requests:', requestsText.split('\n')[0]) // Just the header
          }
        }

        // Test keyboard shortcut too
        console.log('⌨️  Testing Ctrl+Shift+T shortcut...')
        await page.keyboard.press('Control+Shift+T')
        await page.waitForTimeout(500)

        // Open debug dashboard to see traces
        console.log('🐛 Opening debug dashboard to see traces...')
        await page.keyboard.press('Control+Shift+D')
        await page.waitForTimeout(1000)

        // Take screenshot showing both test runner and debug dashboard
        console.log('📸 Taking comprehensive screenshot...')
        await page.screenshot({
          path: '.local/nip55-functionality-test.png',
          fullPage: true
        })
        console.log('✅ Screenshot saved to .local/nip55-functionality-test.png')

        // Test permission scenario
        console.log('🔐 Testing Permission Testing scenario...')
        const permissionTestBtn = await page.locator('.scenario-card').nth(1).locator('button')
        if (await permissionTestBtn.count() > 0) {
          await permissionTestBtn.click()
          console.log('🔄 Permission testing started...')
          await page.waitForTimeout(3000)
        }

      } else {
        console.log('❌ NIP-55 test runner did not open')
      }

    } else {
      console.log('❌ NIP-55 test runner button not found')
      console.log('🔍 Trying keyboard shortcut instead...')
      await page.keyboard.press('Control+Shift+T')
      await page.waitForTimeout(1000)

      const testRunner = await page.locator('.nip55-test-runner').first()
      if (await testRunner.count() > 0) {
        console.log('✅ NIP-55 test runner opened via keyboard shortcut')
      } else {
        console.log('❌ NIP-55 test runner not accessible')
      }
    }

    // Let tests run and observe
    console.log('⏱️  Observing test execution for 10 seconds...')
    await page.waitForTimeout(10000)

    // Check for trace activity in console
    const traceMessages = consoleMessages.filter(msg =>
      msg.includes('RequestTracer') || msg.includes('TRACE-')
    )
    console.log(`📊 Trace messages captured: ${traceMessages.length}`)

    // Check for NIP-55 specific activity
    const nip55Messages = consoleMessages.filter(msg =>
      msg.includes('NIP55') || msg.includes('sign_event') || msg.includes('get_public_key')
    )
    console.log(`🔐 NIP-55 related messages: ${nip55Messages.length}`)

  } catch (error) {
    console.error('❌ Test failed:', error.message)
    errors.push(error.message)
  }

  // Report results
  console.log('\n📊 NIP-55 FUNCTIONALITY TEST RESULTS:')
  console.log('=====================================')

  if (errors.length === 0) {
    console.log('✅ No critical errors detected!')
  } else {
    console.log(`❌ ${errors.length} errors detected:`)
    errors.forEach((error, i) => {
      console.log(`  ${i + 1}. ${error}`)
    })
  }

  console.log(`\n📝 Total console messages: ${consoleMessages.length}`)

  // Analyze console messages for NIP-55 functionality
  const nip55Activity = consoleMessages.filter(msg =>
    msg.includes('sign_event') ||
    msg.includes('get_public_key') ||
    msg.includes('PERMISSION') ||
    msg.includes('NIP55Bridge') ||
    msg.includes('UnifiedPermissions')
  )

  if (nip55Activity.length > 0) {
    console.log(`\n🔐 NIP-55 Activity Detected (${nip55Activity.length} messages):`)
    nip55Activity.slice(0, 5).forEach((msg, i) => {
      console.log(`  ${i + 1}. ${msg.substring(0, 100)}...`)
    })
    if (nip55Activity.length > 5) {
      console.log(`  ... and ${nip55Activity.length - 5} more messages`)
    }
  }

  // Keep browser open for inspection
  console.log('\n👀 Browser will stay open for 30 seconds for manual inspection...')
  console.log('   You can interact with the test runner and debug dashboard!')
  await page.waitForTimeout(30000)

  await browser.close()

  return {
    success: errors.length === 0,
    errors: errors,
    totalMessages: consoleMessages.length,
    nip55Activity: nip55Activity.length
  }
}

// Run the test
if (require.main === module) {
  testNIP55Functionality()
    .then((results) => {
      console.log('\n🎯 FINAL RESULTS:')
      console.log(`✅ Success: ${results.success}`)
      console.log(`📝 Messages: ${results.totalMessages}`)
      console.log(`🔐 NIP-55 Activity: ${results.nip55Activity}`)

      if (results.success) {
        console.log('\n🎉 NIP-55 functionality tests completed successfully!')
        process.exit(0)
      } else {
        console.log('\n💥 Some issues detected - check logs above')
        process.exit(1)
      }
    })
    .catch((error) => {
      console.error('💥 Test runner failed:', error)
      process.exit(1)
    })
}

module.exports = { testNIP55Functionality }