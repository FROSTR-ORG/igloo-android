// NIP-55 URL parsing and handling utilities

import type { NIP55Request, NIP55RequestType } from '@/types.js'

/**
 * Parse a NIP-55 URL and extract the request data
 */
export function parseNIP55URL(url: string): NIP55Request | null {
  console.log('Parsing NIP-55 URL:', url)
  try {
    // Handle both nostrsigner: and web+nostrsigner: schemes
    let cleanUrl = url
    if (url.startsWith('nostrsigner:')) {
      cleanUrl = url.replace('nostrsigner:', '')
    } else if (url.startsWith('web+nostrsigner:')) {
      cleanUrl = url.replace('web+nostrsigner:', '')
    } else {
      console.log('URL does not start with nostrsigner scheme')
      return null
    }
    console.log('Clean URL after scheme removal:', cleanUrl)

    // Split path and query parameters
    const [pathAndData, queryString] = cleanUrl.split('?', 2)
    const params = new URLSearchParams(queryString || '')

    // Extract action from path (e.g., "//getPublicKey" -> "getPublicKey")
    // or fallback to type parameter
    let type = params.get('type') as NIP55RequestType

    if (!type && pathAndData) {
      // Extract action from path: "//getPublicKey" -> "get_public_key"
      const pathParts = pathAndData.split('/')
      const action = pathParts[pathParts.length - 1]

      // Convert camelCase to snake_case for NIP-55 compatibility
      if (action === 'getPublicKey') type = 'get_public_key'
      else if (action === 'signEvent') type = 'sign_event'
      else if (action === 'connect') type = 'get_public_key' // Connect is typically a get_public_key request
      else type = action as NIP55RequestType
    }

    console.log('Extracted type:', type)
    console.log('Path and data:', pathAndData)
    console.log('Query string:', queryString)
    console.log('Params:', Object.fromEntries(params.entries()))

    if (!type) {
      console.log('No type found, returning null')
      return null
    }

    // For sign_event and decrypt_zap_event, data might be in the path before query params
    // Remove leading slash if present and treat remaining as data
    let data = ''
    if (pathAndData) {
      // Remove leading slash if present
      const cleanPath = pathAndData.startsWith('/') ? pathAndData.substring(1) : pathAndData
      // For sign_event/decrypt_zap_event, if it starts with JSON bracket, treat as data
      if (cleanPath && (cleanPath.startsWith('{') || !cleanPath.includes('//'))) {
        data = cleanPath
      }
    }

    console.log('Extracted data:', data)

    const host = extractHost(url) || 'unknown'
    console.log('Host:', host)

    // Base request object
    const baseRequest = {
      type,
      host,
      id: params.get('id') || undefined,
      callbackUrl: params.get('callbackUrl') || undefined,
      current_user: params.get('current_user') || undefined
    }

    // Parse specific request types
    switch (type) {
      case 'get_public_key':
        return {
          ...baseRequest,
          type: 'get_public_key'
        }

      case 'sign_event':
        try {
          console.log('Raw event data:', data)
          const decodedData = data ? decodeURIComponent(data) : ''
          console.log('Decoded event data:', decodedData)
          const eventData = decodedData ? JSON.parse(decodedData) : null
          console.log('Parsed event data:', eventData)

          if (!eventData) throw new Error('No event data provided')

          return {
            ...baseRequest,
            type: 'sign_event',
            event: eventData
          }
        } catch (error) {
          console.error('Failed to parse event data:', error)
          console.error('Raw data was:', data)
          return null
        }

      case 'nip04_encrypt':
      case 'nip44_encrypt':
        const plaintext = data ? decodeURIComponent(data) : ''
        const encryptPubkey = params.get('pubkey')
        if (!encryptPubkey) return null

        return {
          ...baseRequest,
          type,
          plaintext,
          pubkey: encryptPubkey
        }

      case 'nip04_decrypt':
      case 'nip44_decrypt':
        const ciphertext = data ? decodeURIComponent(data) : ''
        const decryptPubkey = params.get('pubkey')
        if (!decryptPubkey) return null

        return {
          ...baseRequest,
          type,
          ciphertext,
          pubkey: decryptPubkey
        }

      case 'decrypt_zap_event':
        try {
          const zapEventData = data ? JSON.parse(decodeURIComponent(data)) : null
          if (!zapEventData) throw new Error('No zap event data provided')

          return {
            ...baseRequest,
            type: 'decrypt_zap_event',
            event: zapEventData
          }
        } catch (error) {
          console.error('Failed to parse zap event data:', error)
          return null
        }

      default:
        console.warn('Unknown NIP-55 request type:', type)
        return null
    }
  } catch (error) {
    console.error('Failed to parse NIP-55 URL:', error)
    return null
  }
}

/**
 * Extract host information from various URL formats
 */
function extractHost(url: string): string | null {
  try {
    // Try to extract from referrer if available
    if (document.referrer) {
      const referrerUrl = new URL(document.referrer)
      return referrerUrl.hostname
    }

    // Check for known patterns in the URL itself
    // This is a fallback and may not always be reliable
    const patterns = [
      /(?:from|host|origin)=([^&]+)/,
      /\/\/([^\/]+)/
    ]

    for (const pattern of patterns) {
      const match = url.match(pattern)
      if (match && match[1]) {
        try {
          return new URL(`https://${match[1]}`).hostname
        } catch {
          return match[1]
        }
      }
    }

    return null
  } catch (error) {
    return null
  }
}

/**
 * Check if the current page was loaded via a NIP-55 URL
 */
export function checkForNIP55Request(): NIP55Request | null {
  console.log('Checking for NIP-55 request in URL:', window.location.href)

  // Temporary visual debugging - remove after testing
  if (window.location.search.includes('nip55') || window.location.href.includes('nostrsigner')) {
    const debugDiv = document.createElement('div')
    debugDiv.style.cssText = 'position:fixed;top:0;left:0;background:red;color:white;padding:10px;z-index:9999;font-size:12px;'
    debugDiv.textContent = `DEBUG: URL detected - ${window.location.href}`
    document.body.appendChild(debugDiv)
    setTimeout(() => debugDiv.remove(), 5000)
  }

  // Check URL parameters for NIP-55 request
  const urlParams = new URLSearchParams(window.location.search)
  const nip55Param = urlParams.get('nip55')

  if (nip55Param) {
    console.log('Found nip55 parameter:', nip55Param)
    const decodedUrl = decodeURIComponent(nip55Param)
    console.log('Decoded URL:', decodedUrl)

    // URL is now the full URI, so parse directly
    const result = parseNIP55URL(decodedUrl)
    console.log('Parsed result:', result)

    // Visual debugging for parsed result
    if (result) {
      const debugDiv = document.createElement('div')
      debugDiv.style.cssText = 'position:fixed;top:30px;left:0;background:green;color:white;padding:10px;z-index:9999;font-size:12px;'
      debugDiv.textContent = `SUCCESS: Parsed type=${result.type} host=${result.host}`
      document.body.appendChild(debugDiv)
      setTimeout(() => debugDiv.remove(), 5000)
    } else {
      const debugDiv = document.createElement('div')
      debugDiv.style.cssText = 'position:fixed;top:30px;left:0;background:orange;color:white;padding:10px;z-index:9999;font-size:12px;'
      debugDiv.textContent = `FAILED: Could not parse NIP-55 request`
      document.body.appendChild(debugDiv)
      setTimeout(() => debugDiv.remove(), 5000)
    }

    return result
  }

  // Check if URL hash contains NIP-55 data (alternative approach)
  const hash = window.location.hash.substring(1)
  if (hash.startsWith('nostrsigner:') || hash.startsWith('web+nostrsigner:')) {
    return parseNIP55URL(hash)
  }

  // Check the full URL if it starts with our scheme
  const fullUrl = window.location.href
  if (fullUrl.includes('nostrsigner:') || fullUrl.includes('web+nostrsigner:')) {
    const schemeIndex = Math.max(
      fullUrl.indexOf('nostrsigner:'),
      fullUrl.indexOf('web+nostrsigner:')
    )
    if (schemeIndex >= 0) {
      const schemeUrl = fullUrl.substring(schemeIndex)
      return parseNIP55URL(schemeUrl)
    }
  }

  return null
}

/**
 * Register protocol handler (for browsers that support it)
 */
export function registerProtocolHandler() {
  if ('navigator' in window && 'registerProtocolHandler' in navigator) {
    try {
      const baseUrl = window.location.origin
      const handlerUrl = `${baseUrl}/?nip55=%s`

      // Note: This requires user interaction to trigger
      navigator.registerProtocolHandler('web+nostrsigner', handlerUrl, 'Igloo PWA Signer')
      console.log('Protocol handler registered successfully')
    } catch (error) {
      console.warn('Failed to register protocol handler:', error)
    }
  }
}

/**
 * Handle NIP-55 request directly (for Android JavaScript injection fallback)
 */
export function handleNIP55Request(nip55Url: string) {
  console.log('handleNIP55Request called with:', nip55Url)

  const request = parseNIP55URL(nip55Url)
  if (request) {
    console.log('Parsed NIP-55 request:', request)

    // Trigger the same logic as if the URL was detected normally
    // This should integrate with the existing prompt system
    if (window.showPrompt && typeof window.showPrompt === 'function') {
      console.log('Calling showPrompt with request')
      window.showPrompt(request)
    } else {
      console.log('showPrompt not available, falling back to URL reload')
      // Fallback: reload with the NIP-55 URL parameter
      const encodedUrl = encodeURIComponent(nip55Url)
      window.location.href = `/?nip55=${encodedUrl}`
    }
  } else {
    console.error('Failed to parse NIP-55 URL:', nip55Url)
  }
}

/**
 * Create a test NIP-55 URL for development
 */
export function createTestNIP55URL(request: Partial<NIP55Request>): string {
  const baseUrl = 'nostrsigner:'

  const params = new URLSearchParams()
  if (request.type) params.set('type', request.type)
  if (request.id) params.set('id', request.id)
  if (request.callbackUrl) params.set('callbackUrl', request.callbackUrl)
  if (request.current_user) params.set('current_user', request.current_user)

  // Add type-specific parameters
  if (request.type === 'nip04_encrypt' || request.type === 'nip44_encrypt') {
    const encReq = request as any
    if (encReq.pubkey) params.set('pubkey', encReq.pubkey)
    const data = encReq.plaintext ? encodeURIComponent(encReq.plaintext) : ''
    return `${baseUrl}${data}?${params.toString()}`
  }

  if (request.type === 'nip04_decrypt' || request.type === 'nip44_decrypt') {
    const decReq = request as any
    if (decReq.pubkey) params.set('pubkey', decReq.pubkey)
    const data = decReq.ciphertext ? encodeURIComponent(decReq.ciphertext) : ''
    return `${baseUrl}${data}?${params.toString()}`
  }

  if (request.type === 'sign_event' || request.type === 'decrypt_zap_event') {
    const eventReq = request as any
    const data = eventReq.event ? encodeURIComponent(JSON.stringify(eventReq.event)) : ''
    return `${baseUrl}${data}?${params.toString()}`
  }

  return `${baseUrl}?${params.toString()}`
}