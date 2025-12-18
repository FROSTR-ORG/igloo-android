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
      // Check for native Android QR scanner bridge (progressive enhancement)
      if (window.QRScannerBridge) {
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
          window.QRScannerBridge.scanQRCode(callbackId)

          // Wait for result
          const data = await scanPromise
          onResult(data)
          return
        } catch (err) {
          const error = err instanceof Error ? err : new Error(String(err))
          setError(error.message)
          if (onError) {
            onError(error)
          }
          return
        }
      }

      // Fallback to web-based QR scanner
      if (!videoRef.current) {
        setError('Video element not found')
        return
      }

      try {
        scanner = new QrScanner(
          videoRef.current,
          (result : QrScanner.ScanResult) => {
            if (!result.data) return
            onResult(result.data)
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
        await scanner.start()
        setError(null)
        setPerm(true)
        scannerRef.current = scanner
      } catch (err) {
        const error = err instanceof Error ? err : new Error('Failed to initialize scanner')
        setError(error.message)
        if (onError) {
          onError(error)
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