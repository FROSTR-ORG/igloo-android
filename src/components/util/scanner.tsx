import { useEffect, useRef, useState } from 'react'

import QrScanner from 'qr-scanner'

interface QRScannerProps {
  onResult: (result: string) => void
  onError?: (error: Error) => void
}

// Type definition for native Android QR scanner bridge
interface QRScannerBridge {
  scanQRCode: (callbackId: string) => void
}

// Global callback registry for native scanner
declare global {
  interface Window {
    QRScannerBridge?: QRScannerBridge
    QRScannerCallbacks?: Record<string, {
      resolve: (data: string) => void
      reject: (error: string) => void
    }>
  }
}

export function QRScanner({ onResult, onError }: QRScannerProps) {
  const videoRef   = useRef<HTMLVideoElement>(null)
  const scannerRef = useRef<QrScanner | null>(null)

  const [ error,   setError ] = useState<string | null>(null)
  const [ hasPerm, setPerm  ] = useState<boolean | null>(null)

  useEffect(() => {
    let scanner: QrScanner | null = null

    const initializeScanner = async () => {
      console.log('QR Scanner: Initializing scanner...')

      // Check for native Android QR scanner bridge (progressive enhancement)
      if (window.QRScannerBridge) {
        console.log('QR Scanner: Native Android QR scanner detected, using native scanner')
        try {
          // Initialize callback registry if not exists
          if (!window.QRScannerCallbacks) {
            window.QRScannerCallbacks = {}
          }

          // Generate unique callback ID
          const callbackId = `qr_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`

          // Register promise-like callbacks
          const scanPromise = new Promise<string>((resolve, reject) => {
            window.QRScannerCallbacks![callbackId] = { resolve, reject }
          })

          // Call native scanner
          console.log('QR Scanner: Calling native scanner with callback ID:', callbackId)
          window.QRScannerBridge.scanQRCode(callbackId)

          // Wait for result
          const data = await scanPromise
          console.log('QR Scanner: Native scanner returned data:', data)
          onResult(data)
          return
        } catch (err) {
          const error = err instanceof Error ? err : new Error(String(err))
          console.error('QR Scanner: Native scanner failed:', error)
          setError(error.message)
          if (onError) {
            onError(error)
          }
          return
        }
      }

      // Fallback to web-based QR scanner
      console.log('QR Scanner: No native scanner available, using web-based scanner')

      if (!videoRef.current) {
        console.error('QR Scanner: Video element not found')
        setError('Video element not found')
        return
      }

      console.log('QR Scanner: Video element found, creating scanner...')
      try {
        scanner = new QrScanner(
          videoRef.current,
          (result : QrScanner.ScanResult) => {
            console.log('QR Scanner: ===== QR CODE DETECTED =====')
            console.log('QR Scanner: Full result object:', result)
            console.log('QR Scanner: Data:', result.data)
            console.log('QR Scanner: Corner points:', result.cornerPoints)

            // Additional validation
            if (!result.data) {
              console.warn('QR Scanner: Warning - result.data is empty or null')
              return
            }

            console.log('QR Scanner: Calling onResult with data:', JSON.stringify(result.data))

            try {
              onResult(result.data)
              console.log('QR Scanner: onResult called successfully')
            } catch (err) {
              console.error('QR Scanner: Error in onResult callback:', err)
            }

            console.log('QR Scanner: Stopping scanner after scan')
            scanner?.stop()
          },
          {
            returnDetailedScanResult: true,
            highlightScanRegion: true,
            highlightCodeOutline: true,
            maxScansPerSecond: 5,
            preferredCamera: 'environment'
          }
        )

        // Start scanning
        console.log('QR Scanner: Starting scanner...')
        await scanner.start()
        console.log('QR Scanner: Scanner started successfully')
        setError(null)
        setPerm(true)
        scannerRef.current = scanner
      } catch (err) {
        const error = err instanceof Error ? err : new Error('Failed to initialize scanner')
        setError(error.message)
        if (onError) {
          onError(error)
        } else {
          console.error('Failed to start QR scanner:', error)
        }
      }
    }

    initializeScanner()

    return () => {
      if (scanner) {
        scanner.stop()
        scanner.destroy()
      }
    }
  }, [onResult, onError])

  // If native scanner is available, don't show video element
  if (window.QRScannerBridge) {
    return (
      <div className="scanner-container">
        <div className="scanner-native-placeholder">
          Opening native QR scanner...
        </div>
      </div>
    )
  }

  return (
    <div className="scanner-container">
      <video
        ref={videoRef}
        className="scanner-video"
        playsInline
        autoPlay
        muted
        onLoadedMetadata={() => {
          console.log('QR Scanner: Video metadata loaded')
          console.log('QR Scanner: Video dimensions:', videoRef.current?.videoWidth, 'x', videoRef.current?.videoHeight)
        }}
        onPlay={() => {
          console.log('QR Scanner: Video is playing')
        }}
      />
      {error && (
        <div className="scanner-error">
          {error}
          {hasPerm === false && (
            <div className="scanner-error-permission">
              Please grant camera permissions to use the QR scanner
            </div>
          )}
        </div>
      )}
    </div>
  )
} 