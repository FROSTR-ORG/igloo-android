/**
 * Detailed Error Capture for NIP-55 Testing
 *
 * Captures detailed error information from the PWA console
 */

const { chromium } = require('playwright')

async function captureDetailedErrors() {
  console.log('🔍 Starting detailed error capture...')

  const browser = await chromium.launch({
    headless: true,
    slowMo: 100
  })

  const context = await browser.newContext()
  const page = await context.newPage()

  // Detailed console capture
  const allMessages = []
  const errorDetails = []
  const traceMessages = []

  page.on('console', (msg) => {
    const message = {
      type: msg.type(),
      text: msg.text(),
      timestamp: Date.now()
    }

    allMessages.push(message)

    if (msg.type() === 'error' || msg.text().includes('ERROR')) {
      errorDetails.push(message)
      console.log(`[ERROR DETAIL] ${msg.text()}`)
    }

    if (msg.text().includes('TRACE-') || msg.text().includes('RequestTracer')) {
      traceMessages.push(message)
    }
  })

  page.on('pageerror', (error) => {
    const errorDetail = {
      type: 'pageerror',
      message: error.message,
      stack: error.stack,
      timestamp: Date.now()
    }
    errorDetails.push(errorDetail)
    console.error(`[PAGE ERROR] ${error.message}`)
  })

  try {
    console.log('📡 Loading PWA...')
    await page.goto('http://localhost:3000', { waitUntil: 'networkidle', timeout: 30000 })

    console.log('⏱️  Waiting for full initialization...')
    await page.waitForTimeout(5000)

    // Open test runner
    console.log('🧪 Opening test runner...')
    await page.keyboard.press('Control+Shift+T')
    await page.waitForTimeout(1000)

    // Run basic test
    console.log('🔄 Running basic test...')
    const runButton = await page.locator('.run-scenario-btn').first()
    if (await runButton.count() > 0) {
      await runButton.click()
      await page.waitForTimeout(5000)

      // Capture test results
      const results = await page.locator('.test-results')
      if (await results.count() > 0) {
        const resultsText = await results.innerText()
        console.log('📊 Test Results Captured:', resultsText)
      }

      // Capture error details from requests
      const errorMessages = await page.locator('.error-message')
      const errorCount = await errorMessages.count()
      console.log(`🚨 Found ${errorCount} error messages in UI`)

      for (let i = 0; i < errorCount; i++) {
        const errorText = await errorMessages.nth(i).innerText()
        console.log(`  Error ${i + 1}: ${errorText}`)
      }
    }

  } catch (error) {
    console.error('Test execution error:', error.message)
  }

  await browser.close()

  // Analysis
  console.log('\n📊 DETAILED ERROR ANALYSIS:')
  console.log('============================')
  console.log(`Total messages: ${allMessages.length}`)
  console.log(`Error messages: ${errorDetails.length}`)
  console.log(`Trace messages: ${traceMessages.length}`)

  if (errorDetails.length > 0) {
    console.log('\n🚨 ERROR DETAILS:')
    errorDetails.forEach((error, i) => {
      console.log(`${i + 1}. [${error.type}] ${error.text || error.message}`)
      if (error.stack) {
        console.log(`   Stack: ${error.stack.split('\n')[0]}`)
      }
    })
  }

  // Look for specific permission errors
  const permissionErrors = allMessages.filter(msg =>
    msg.text.includes('Permission denied') ||
    msg.text.includes('auto-approval') ||
    msg.text.includes('PERMISSION_DENIED')
  )

  if (permissionErrors.length > 0) {
    console.log('\n🔐 PERMISSION ERRORS:')
    permissionErrors.forEach((error, i) => {
      console.log(`${i + 1}. ${error.text}`)
    })
  }

  return {
    totalMessages: allMessages.length,
    errorCount: errorDetails.length,
    permissionErrors: permissionErrors.length,
    errors: errorDetails.map(e => e.text || e.message)
  }
}

if (require.main === module) {
  captureDetailedErrors()
    .then(results => {
      console.log(`\n🎯 SUMMARY: ${results.errorCount} errors, ${results.permissionErrors} permission issues`)
      process.exit(results.errorCount > 0 ? 1 : 0)
    })
    .catch(error => {
      console.error('Capture failed:', error)
      process.exit(1)
    })
}

module.exports = { captureDetailedErrors }