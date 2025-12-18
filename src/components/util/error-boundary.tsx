import { Component } from 'react'
import type { ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

/**
 * Error Boundary Component
 *
 * Catches JavaScript errors anywhere in the child component tree,
 * logs the error, and displays a fallback UI.
 */
export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    // Log error to console for debugging (kept intentionally for error tracking)
    console.error('Igloo Error Boundary caught an error:', error, errorInfo)
  }

  handleReload = (): void => {
    window.location.reload()
  }

  handleReset = (): void => {
    this.setState({ hasError: false, error: null })
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          height: '100vh',
          padding: '20px',
          textAlign: 'center',
          fontFamily: 'system-ui, -apple-system, sans-serif',
          backgroundColor: '#1a1a1a',
          color: '#ffffff'
        }}>
          <h1 style={{ fontSize: '24px', marginBottom: '16px' }}>
            Something went wrong
          </h1>
          <p style={{
            color: '#999',
            marginBottom: '24px',
            maxWidth: '400px'
          }}>
            The application encountered an unexpected error.
            Your data is safe - try reloading the page.
          </p>
          {this.state.error && (
            <pre style={{
              backgroundColor: '#2a2a2a',
              padding: '12px 16px',
              borderRadius: '8px',
              fontSize: '12px',
              color: '#ff6b6b',
              marginBottom: '24px',
              maxWidth: '100%',
              overflow: 'auto',
              textAlign: 'left'
            }}>
              {this.state.error.message}
            </pre>
          )}
          <div style={{ display: 'flex', gap: '12px' }}>
            <button
              onClick={this.handleReload}
              style={{
                padding: '12px 24px',
                fontSize: '14px',
                backgroundColor: '#4a9eff',
                color: 'white',
                border: 'none',
                borderRadius: '8px',
                cursor: 'pointer'
              }}
            >
              Reload Page
            </button>
            <button
              onClick={this.handleReset}
              style={{
                padding: '12px 24px',
                fontSize: '14px',
                backgroundColor: '#333',
                color: 'white',
                border: '1px solid #555',
                borderRadius: '8px',
                cursor: 'pointer'
              }}
            >
              Try Again
            </button>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}
