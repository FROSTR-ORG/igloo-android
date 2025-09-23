import { useEffect, useRef, useState } from 'react'

import QrScanner from 'qr-scanner'

interface QRScannerProps {
  onResult: (result: string) => void
  onError?: (error: Error) => void
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