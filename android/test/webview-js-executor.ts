#!/usr/bin/env bun

/**
 * WebView JavaScript Executor
 *
 * A TypeScript tool to execute JavaScript in the Android WebView using Chrome DevTools Protocol.
 * This tool connects to the WebView via WebSocket and can run arbitrary JavaScript
 * code, making it perfect for testing and debugging the PWA.
 *
 * Usage:
 *   bun webview-js-executor.ts "console.log('Hello from WebView')"
 *   bun webview-js-executor.ts "window.location.href"
 *   bun webview-js-executor.ts "!!window.nostr && !!window.nostr.nip55"
 *
 * Requirements:
 *   - Android app must be running with WebView debugging enabled
 *   - Port forwarding must be set up: adb forward tcp:9222 localabstract:webview_devtools_remote_PID
 */

interface DevToolsTarget {
    description: string
    devtoolsFrontendUrl: string
    faviconUrl: string
    id: string
    title: string
    type: string
    url: string
    webSocketDebuggerUrl: string
}

interface DevToolsRequest {
    id: number
    method: string
    params: {
        expression: string
        awaitPromise?: boolean
        returnByValue?: boolean
        generatePreview?: boolean
    }
}

interface DevToolsResponse {
    id: number
    result?: {
        result: {
            type: string
            subtype?: string
            value?: any
            description?: string
        }
        exceptionDetails?: {
            text: string
            lineNumber: number
        }
    }
    error?: {
        message: string
    }
}

interface ExecutionOptions {
    awaitPromise?: boolean
    timeout?: number
    returnByValue?: boolean
}

class WebViewJSExecutor {
    private readonly devToolsPort = 9222
    private readonly targetPageTitle = 'Igloo PWA'
    private requestId = 1

    /**
     * Find the target page (Igloo PWA) from available debugging targets
     */
    async findTargetPage(): Promise<DevToolsTarget> {
        const response = await fetch(`http://localhost:${this.devToolsPort}/json`)

        if (!response.ok) {
            throw new Error(`Failed to connect to DevTools: ${response.status}. Make sure port forwarding is set up.`)
        }

        const pages: DevToolsTarget[] = await response.json()
        const targetPage = pages.find(page =>
            page.title === this.targetPageTitle && page.type === 'page'
        )

        if (!targetPage) {
            throw new Error(`Target page "${this.targetPageTitle}" not found. Available pages: ${pages.map(p => p.title).join(', ')}`)
        }

        return targetPage
    }

    /**
     * Execute JavaScript in the WebView
     */
    async executeJS(jsCode: string, options: ExecutionOptions = {}): Promise<any> {
        const { awaitPromise = false, timeout = 10000, returnByValue = true } = options

        // Find the target page
        const targetPage = await this.findTargetPage()
        console.log(`🎯 Connected to: ${targetPage.title} (${targetPage.url})`)

        return new Promise((resolve, reject) => {
            const wsUrl = targetPage.webSocketDebuggerUrl
            const ws = new WebSocket(wsUrl)

            const timeoutId = setTimeout(() => {
                ws.close()
                reject(new Error(`JavaScript execution timeout after ${timeout}ms`))
            }, timeout)

            ws.onopen = () => {
                console.log('🔗 WebSocket connected to WebView')

                const message: DevToolsRequest = {
                    id: this.requestId++,
                    method: 'Runtime.evaluate',
                    params: {
                        expression: jsCode,
                        awaitPromise,
                        returnByValue,
                        generatePreview: true
                    }
                }

                console.log('📤 Executing JavaScript:', jsCode.length > 100 ? jsCode.substring(0, 100) + '...' : jsCode)
                ws.send(JSON.stringify(message))
            }

            ws.onmessage = (event) => {
                try {
                    const response: DevToolsResponse = JSON.parse(event.data.toString())

                    // Ignore console messages and other events, only handle our response
                    if (response.id === (this.requestId - 1)) {
                        clearTimeout(timeoutId)
                        ws.close()

                        if (response.error) {
                            reject(new Error(`DevTools error: ${response.error.message}`))
                            return
                        }

                        const result = response.result
                        if (result?.exceptionDetails) {
                            reject(new Error(`JavaScript error: ${result.exceptionDetails.text} at line ${result.exceptionDetails.lineNumber}`))
                            return
                        }

                        resolve(result?.result)
                    }
                } catch (err) {
                    clearTimeout(timeoutId)
                    ws.close()
                    reject(new Error(`Failed to parse WebSocket response: ${err instanceof Error ? err.message : String(err)}`))
                }
            }

            ws.onerror = (event) => {
                clearTimeout(timeoutId)
                reject(new Error(`WebSocket error: ${event}`))
            }

            ws.onclose = (event) => {
                clearTimeout(timeoutId)
                if (event.code !== 1000) {
                    reject(new Error(`WebSocket closed unexpectedly: ${event.code} ${event.reason}`))
                }
            }
        })
    }

    /**
     * Format and display the result
     */
    displayResult(result: any): void {
        console.log('\n📋 Result:')
        console.log('─'.repeat(50))

        if (result?.type === 'undefined') {
            console.log('undefined')
        } else if (result?.type === 'object' && result?.subtype === 'null') {
            console.log('null')
        } else if (result?.value !== undefined) {
            if (typeof result.value === 'string') {
                console.log(`"${result.value}"`)
            } else {
                console.log(result.value)
            }
        } else if (result?.description) {
            console.log(result.description)
        } else {
            console.log('(No result description available)')
        }

        // Show type information
        if (result?.type) {
            console.log(`\nType: ${result.type}${result.subtype ? ` (${result.subtype})` : ''}`)
        }

        console.log('─'.repeat(50))
    }
}

// CLI Usage
async function main(): Promise<void> {
    const args = process.argv.slice(2)

    if (args.length === 0) {
        console.log(`
🚀 WebView JavaScript Executor (TypeScript)

Usage:
  bun webview-js-executor.ts "JavaScript code to execute"

Examples:
  bun webview-js-executor.ts "window.location.href"
  bun webview-js-executor.ts "JSON.stringify({nostr: !!window.nostr, nip55: !!window.nostr?.nip55})"
  bun webview-js-executor.ts "await window.nostr.nip55({type: 'get_public_key', id: 'test'})" --async

Options:
  --async    Wait for promises to resolve
  --timeout  Set timeout in milliseconds (default: 10000)

Prerequisites:
  1. Android app must be running with Igloo PWA loaded
  2. Set up port forwarding: adb forward tcp:9222 localabstract:webview_devtools_remote_PID
  3. Find PID with: adb shell ps | grep com.frostr.igloo
`)
        process.exit(1)
    }

    const jsCode = args[0]
    const isAsync = args.includes('--async')
    const timeoutIndex = args.indexOf('--timeout')
    const timeout = timeoutIndex > -1 ? parseInt(args[timeoutIndex + 1]) || 10000 : 10000

    const executor = new WebViewJSExecutor()

    try {
        console.log('🔍 Looking for WebView debugging targets...')
        const result = await executor.executeJS(jsCode, {
            awaitPromise: isAsync,
            timeout
        })

        executor.displayResult(result)
        console.log('\n✅ JavaScript executed successfully')

    } catch (error) {
        console.error('\n❌ Error:', error instanceof Error ? error.message : String(error))

        // Provide helpful troubleshooting tips
        if (error instanceof Error && error.message.includes('Failed to connect to DevTools')) {
            console.error(`
💡 Troubleshooting:
  1. Make sure the Android app is running
  2. Set up port forwarding: adb forward tcp:9222 localabstract:webview_devtools_remote_PID
  3. Find PID with: adb shell ps | grep com.frostr.igloo
  4. Check available targets: curl -s http://localhost:9222/json
`)
        }

        process.exit(1)
    }
}

// Export for programmatic use
export { WebViewJSExecutor }

// Run CLI if called directly
if (import.meta.main) {
    main()
}