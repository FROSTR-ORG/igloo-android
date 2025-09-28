import { useState, useEffect } from 'react'
import { usePrompt } from '@/context/prompt.js'

import type { ReactElement } from 'react'

export function DevConsole(): ReactElement {
  const prompt = usePrompt()
  const [isVisible, setIsVisible] = useState(false)
  const [activeTab, setActiveTab] = useState<'logs' | 'storage' | 'nip55'>('logs')
  const [logs, setLogs] = useState<Array<{
    timestamp: string
    level: string
    message: string
  }>>([])
  const [storageData, setStorageData] = useState<Record<string, any>>({})
  const [testHost, setTestHost] = useState('example.com')
  const [testRequestType, setTestRequestType] = useState('sign_event')

  // Capture console logs
  useEffect(() => {
    const originalConsole = {
      log: console.log,
      error: console.error,
      warn: console.warn,
      info: console.info
    }

    const addLog = (level: string, ...args: any[]) => {
      setLogs(prev => [...prev, {
        timestamp: new Date().toISOString(),
        level,
        message: args.map(arg =>
          typeof arg === 'object' ? JSON.stringify(arg, null, 2) : String(arg)
        ).join(' ')
      }])
    }

    console.log = (...args) => {
      originalConsole.log(...args)
      addLog('log', ...args)
    }
    console.error = (...args) => {
      originalConsole.error(...args)
      addLog('error', ...args)
    }
    console.warn = (...args) => {
      originalConsole.warn(...args)
      addLog('warn', ...args)
    }
    console.info = (...args) => {
      originalConsole.info(...args)
      addLog('info', ...args)
    }

    return () => {
      console.log = originalConsole.log
      console.error = originalConsole.error
      console.warn = originalConsole.warn
      console.info = originalConsole.info
    }
  }, [])

  const refreshStorage = async () => {
    try {
      const data: Record<string, any> = {}

      // Get localStorage items
      const localStorageData: Record<string, any> = {}
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key) {
          const value = localStorage.getItem(key)
          try {
            localStorageData[key] = {
              formatted: JSON.stringify(JSON.parse(value || ''), null, 2),
              isJson: true
            }
          } catch {
            localStorageData[key] = value
          }
        }
      }
      data.localStorage = localStorageData

      // Get sessionStorage items
      const sessionStorageData: Record<string, any> = {}
      for (let i = 0; i < sessionStorage.length; i++) {
        const key = sessionStorage.key(i)
        if (key) {
          const value = sessionStorage.getItem(key)
          try {
            sessionStorageData[key] = {
              formatted: JSON.stringify(JSON.parse(value || ''), null, 2),
              isJson: true
            }
          } catch {
            sessionStorageData[key] = value
          }
        }
      }
      data.sessionStorage = sessionStorageData

      setStorageData(data)
    } catch (error) {
      console.error('Failed to refresh storage data:', error)
    }
  }

  useEffect(() => {
    if (isVisible && activeTab === 'storage') {
      refreshStorage()
    }
  }, [isVisible, activeTab])

  const copyLogs = async () => {
    try {
      const logText = logs.map(log =>
        `[${log.timestamp}] [${log.level.toUpperCase()}] ${log.message}`
      ).join('\n')
      await navigator.clipboard.writeText(logText)
      console.log('Logs copied to clipboard')
    } catch (error) {
      console.error('Failed to copy logs:', error)
    }
  }

  const clearLogs = () => {
    setLogs([])
    console.log('Logs cleared')
  }

  const copyLogEntry = async (log: { timestamp: string, level: string, message: string }) => {
    const logText = `[${log.timestamp}] [${log.level.toUpperCase()}] ${log.message}`
    try {
      await navigator.clipboard.writeText(logText)
      console.log('Log entry copied to clipboard')
    } catch (error) {
      console.error('Failed to copy log entry:', error)
      // Fallback: create a text area and copy
      try {
        const textArea = document.createElement('textarea')
        textArea.value = logText
        document.body.appendChild(textArea)
        textArea.select()
        document.execCommand('copy')
        document.body.removeChild(textArea)
        console.log('Log entry copied to clipboard (fallback)')
      } catch (fallbackError) {
        console.error('Fallback copy failed:', fallbackError)
      }
    }
  }

  const triggerTestPrompt = () => {
    if (!prompt) {
      console.error('Prompt context not available')
      return
    }

    const request = {
      id: `test-${Date.now()}`,
      type: testRequestType,
      host: testHost,
      timestamp: Date.now(),
      data: {
        event: testRequestType === 'sign_event' ? {
          kind: 1,
          content: 'Test message',
          tags: [],
          created_at: Math.floor(Date.now() / 1000)
        } : undefined
      }
    }

    console.log('Triggering test prompt:', request)

    window.dispatchEvent(new CustomEvent('nip55-prompt-request', {
      detail: { request }
    }))
  }

  const showStoredPermissions = async () => {
    try {
      const perms = localStorage.getItem('nip55_permissions')
      console.log('Stored permissions:', perms ? JSON.parse(perms) : 'None')
    } catch (error) {
      console.error('Failed to retrieve permissions:', error)
    }
  }

  const getLogColor = (level: string) => {
    switch (level) {
      case 'error': return '#ff6b6b'
      case 'warn': return '#ffa726'
      case 'info': return '#42a5f5'
      case 'debug': return '#9e9e9e'
      default: return '#ccc'
    }
  }

  // Render NIP-55 tab with Android WebView safety
  const renderNIP55Tab = () => {
    // Simple safety check for Android WebView
    if (!prompt) {
      return (
        <div style={{
          padding: '20px',
          color: '#f44336',
          backgroundColor: '#1a1a1a',
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
          textAlign: 'center'
        }}>
          <div style={{ fontSize: '24px', marginBottom: '16px' }}>⚠️</div>
          <div style={{ marginBottom: '8px', fontWeight: 'bold' }}>NIP-55 Debug Unavailable</div>
          <div style={{ fontSize: '12px', color: '#ccc', lineHeight: '1.4' }}>
            Context not available in Android WebView.<br/>
            Try refreshing the app or use the web version.
          </div>
        </div>
      )
    }

    return (
      <div style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column'
      }}>
        <div style={{
          padding: '8px 12px',
          backgroundColor: '#2a2a2a',
          borderBottom: '1px solid #444',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center'
        }}>
          <span style={{ fontSize: '12px' }}>NIP-55 Debug</span>
          <button
            onClick={showStoredPermissions}
            style={{
              padding: '4px 8px',
              fontSize: '12px',
              backgroundColor: '#555',
              color: 'white',
              border: 'none',
              borderRadius: '4px',
              cursor: 'pointer'
            }}
          >
            Check Permissions
          </button>
        </div>
        <div style={{
          flex: 1,
          overflow: 'auto',
          padding: '12px'
        }}>
          <div style={{
            marginBottom: '16px',
            padding: '12px',
            backgroundColor: '#2a2a2a',
            borderRadius: '6px'
          }}>
            <div style={{
              color: '#42a5f5',
              fontWeight: 'bold',
              marginBottom: '12px',
              fontSize: '14px'
            }}>
              Test Configuration
            </div>

            <div style={{ marginBottom: '12px' }}>
              <div style={{
                color: '#ffa726',
                fontSize: '12px',
                marginBottom: '4px'
              }}>
                Test Host (App ID)
              </div>
              <input
                type="text"
                value={testHost}
                onChange={(e) => setTestHost(e.target.value)}
                placeholder="example.com"
                style={{
                  width: '100%',
                  padding: '6px 8px',
                  fontSize: '12px',
                  backgroundColor: '#1a1a1a',
                  color: 'white',
                  border: '1px solid #444',
                  borderRadius: '4px'
                }}
              />
            </div>

            <div style={{ marginBottom: '12px' }}>
              <div style={{
                color: '#ffa726',
                fontSize: '12px',
                marginBottom: '4px'
              }}>
                Request Type
              </div>
              <select
                value={testRequestType}
                onChange={(e) => setTestRequestType(e.target.value)}
                style={{
                  width: '100%',
                  padding: '6px 8px',
                  fontSize: '12px',
                  backgroundColor: '#1a1a1a',
                  color: 'white',
                  border: '1px solid #444',
                  borderRadius: '4px'
                }}
              >
                <option value="sign_event">sign_event</option>
                <option value="get_public_key">get_public_key</option>
                <option value="nip04_encrypt">nip04_encrypt</option>
                <option value="nip04_decrypt">nip04_decrypt</option>
              </select>
            </div>

            <button
              onClick={triggerTestPrompt}
              style={{
                padding: '8px 16px',
                fontSize: '12px',
                backgroundColor: '#4caf50',
                color: 'white',
                border: 'none',
                borderRadius: '4px',
                cursor: 'pointer',
                width: '100%'
              }}
            >
              Trigger Prompt
            </button>
          </div>

          <div style={{
            marginBottom: '16px',
            padding: '12px',
            backgroundColor: '#2a2a2a',
            borderRadius: '6px'
          }}>
            <div style={{
              color: '#42a5f5',
              fontWeight: 'bold',
              marginBottom: '8px',
              fontSize: '14px'
            }}>
              Bridge Status
            </div>
            <div style={{ fontSize: '11px', color: '#ccc' }}>
              NIP-55 Bridge: {(() => {
                try {
                  return (window as any).NIP55_BRIDGE_READY ? 'Ready ✅' : 'Not Ready ❌'
                } catch {
                  return 'Unknown'
                }
              })()}
            </div>
          </div>

          <div style={{
            marginTop: '12px',
            padding: '12px',
            backgroundColor: '#2a2a2a',
            borderRadius: '6px'
          }}>
            <div style={{
              color: '#42a5f5',
              fontWeight: 'bold',
              marginBottom: '8px',
              fontSize: '14px'
            }}>
              Instructions
            </div>
            <div style={{ fontSize: '11px', color: '#ccc', lineHeight: '1.6' }}>
              1. Set host and request type<br/>
              2. Click "Trigger Prompt" to test<br/>
              3. Check "Remember" and approve/deny<br/>
              4. Use "Check Permissions" to verify<br/>
              5. Test again to see auto-handling
            </div>
          </div>
        </div>
      </div>
    )
  }

  if (!isVisible) {
    return (
      <button
        onClick={() => setIsVisible(true)}
        style={{
          position: 'fixed',
          bottom: '20px',
          right: '20px',
          width: '60px',
          height: '60px',
          backgroundColor: '#333',
          color: 'white',
          border: 'none',
          borderRadius: '50%',
          cursor: 'pointer',
          zIndex: 9999,
          fontSize: '24px',
          boxShadow: '0 4px 12px rgba(0,0,0,0.3)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center'
        }}
      >
        🔧
      </button>
    )
  }

  return (
    <div style={{
      position: 'fixed',
      top: '0',
      left: '0',
      right: '0',
      bottom: '0',
      backgroundColor: '#1a1a1a',
      display: 'flex',
      flexDirection: 'column',
      zIndex: 9999,
      fontFamily: 'monospace',
      color: 'white'
    }}>
      {/* Header */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        padding: '12px',
        backgroundColor: '#333',
        borderBottom: '1px solid #444'
      }}>
        <span style={{ fontSize: '16px', fontWeight: 'bold' }}>
          Developer Console
        </span>
        <button
          onClick={() => setIsVisible(false)}
          style={{
            backgroundColor: 'transparent',
            color: 'white',
            border: 'none',
            fontSize: '18px',
            cursor: 'pointer',
            padding: '4px'
          }}
        >
          ✕
        </button>
      </div>

      {/* Tabs */}
      <div style={{
        display: 'flex',
        backgroundColor: '#2a2a2a',
        borderBottom: '1px solid #444'
      }}>
        <button
          onClick={() => setActiveTab('logs')}
          style={{
            flex: 1,
            padding: '12px',
            fontSize: '14px',
            backgroundColor: activeTab === 'logs' ? '#444' : 'transparent',
            color: 'white',
            border: 'none',
            cursor: 'pointer'
          }}
        >
          📝 Logs ({logs.length})
        </button>
        <button
          onClick={() => {
            setActiveTab('storage')
            refreshStorage()
          }}
          style={{
            flex: 1,
            padding: '12px',
            fontSize: '14px',
            backgroundColor: activeTab === 'storage' ? '#444' : 'transparent',
            color: 'white',
            border: 'none',
            cursor: 'pointer'
          }}
        >
          📦 Storage
        </button>
        <button
          onClick={() => setActiveTab('nip55')}
          style={{
            flex: 1,
            padding: '12px',
            fontSize: '14px',
            backgroundColor: activeTab === 'nip55' ? '#444' : 'transparent',
            color: 'white',
            border: 'none',
            cursor: 'pointer'
          }}
        >
          🔑 NIP-55
        </button>
      </div>

      {/* Content */}
      <div style={{ flex: 1, overflow: 'hidden' }}>
        {activeTab === 'logs' ? (
          <div style={{
            height: '100%',
            display: 'flex',
            flexDirection: 'column'
          }}>
            <div style={{
              padding: '8px 12px',
              backgroundColor: '#2a2a2a',
              borderBottom: '1px solid #444',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center'
            }}>
              <span style={{ fontSize: '12px' }}>Console Output</span>
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  onClick={copyLogs}
                  style={{
                    padding: '4px 8px',
                    fontSize: '12px',
                    backgroundColor: '#4a4a4a',
                    color: 'white',
                    border: 'none',
                    borderRadius: '4px',
                    cursor: 'pointer'
                  }}
                >
                  Copy
                </button>
                <button
                  onClick={clearLogs}
                  style={{
                    padding: '4px 8px',
                    fontSize: '12px',
                    backgroundColor: '#555',
                    color: 'white',
                    border: 'none',
                    borderRadius: '4px',
                    cursor: 'pointer'
                  }}
                >
                  Clear
                </button>
              </div>
            </div>
            <div style={{
              flex: 1,
              overflow: 'auto',
              padding: '8px'
            }}>
              {logs.map((log, index) => (
                <div
                  key={index}
                  onClick={() => copyLogEntry(log)}
                  style={{
                    padding: '6px 8px',
                    margin: '2px 0',
                    fontSize: '11px',
                    backgroundColor: '#2a2a2a',
                    borderRadius: '4px',
                    borderLeft: `3px solid ${getLogColor(log.level)}`,
                    cursor: 'pointer'
                  }}
                  title="Click to copy log entry"
                >
                  <div style={{ opacity: 0.7, fontSize: '10px' }}>
                    [{log.timestamp}] {log.level.toUpperCase()}
                  </div>
                  <div style={{ wordBreak: 'break-word', marginTop: '2px' }}>
                    {log.message}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ) : activeTab === 'storage' ? (
          <div style={{
            height: '100%',
            display: 'flex',
            flexDirection: 'column'
          }}>
            <div style={{
              padding: '8px 12px',
              backgroundColor: '#2a2a2a',
              borderBottom: '1px solid #444',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center'
            }}>
              <span style={{ fontSize: '12px' }}>Storage Inspector</span>
              <button
                onClick={refreshStorage}
                style={{
                  padding: '4px 8px',
                  fontSize: '12px',
                  backgroundColor: '#555',
                  color: 'white',
                  border: 'none',
                  borderRadius: '4px',
                  cursor: 'pointer'
                }}
              >
                Refresh
              </button>
            </div>
            <div style={{
              flex: 1,
              overflow: 'auto',
              padding: '8px'
            }}>
              {Object.entries(storageData).map(([section, sectionData]) => (
                <div key={section} style={{
                  margin: '8px 0',
                  padding: '12px',
                  backgroundColor: '#2a2a2a',
                  borderRadius: '6px'
                }}>
                  <div style={{
                    color: '#42a5f5',
                    fontWeight: 'bold',
                    marginBottom: '8px',
                    fontSize: '14px',
                    textTransform: 'capitalize'
                  }}>
                    {section.replace(/([A-Z])/g, ' $1').trim()} {
                      typeof sectionData === 'object' && sectionData !== null && !Array.isArray(sectionData)
                        ? `(${Object.keys(sectionData).length} items)`
                        : ''
                    }
                  </div>
                  {typeof sectionData === 'object' && sectionData !== null && !Array.isArray(sectionData) ? (
                    Object.entries(sectionData).map(([key, value]) => (
                      <div key={key} style={{ marginBottom: '12px' }}>
                        <div style={{
                          color: '#ffa726',
                          fontSize: '12px',
                          fontWeight: 'bold',
                          marginBottom: '4px'
                        }}>
                          {key}
                        </div>
                        <div style={{
                          color: '#ccc',
                          fontSize: '11px',
                          wordBreak: (value as any)?.isJson ? 'normal' : 'break-all',
                          lineHeight: '1.4',
                          maxHeight: '150px',
                          overflow: 'auto',
                          backgroundColor: '#1a1a1a',
                          padding: '6px',
                          borderRadius: '4px',
                          marginLeft: '12px',
                          fontFamily: (value as any)?.isJson ? 'monospace' : 'inherit',
                          whiteSpace: (value as any)?.isJson ? 'pre' : 'normal'
                        }}>
                          {(value as any)?.isJson && (
                            <div style={{
                              fontSize: '10px',
                              color: '#4caf50',
                              marginBottom: '4px',
                              fontWeight: 'bold'
                            }}>
                              📄 JSON
                            </div>
                          )}
                          {(value as any)?.formatted || (
                            typeof value === 'string' ? (
                              value || '(empty string)'
                            ) : value === null ? (
                              '(null)'
                            ) : value === undefined ? (
                              '(undefined)'
                            ) : (
                              JSON.stringify(value, null, 2)
                            )
                          )}
                        </div>
                      </div>
                    ))
                  ) : (
                    <div style={{
                      color: '#ccc',
                      fontSize: '11px',
                      wordBreak: (sectionData as any)?.isJson ? 'normal' : 'break-all',
                      lineHeight: '1.4',
                      maxHeight: '150px',
                      overflow: 'auto',
                      backgroundColor: '#1a1a1a',
                      padding: '8px',
                      borderRadius: '4px',
                      fontFamily: (sectionData as any)?.isJson ? 'monospace' : 'inherit',
                      whiteSpace: (sectionData as any)?.isJson ? 'pre' : 'normal'
                    }}>
                      {(sectionData as any)?.isJson && (
                        <div style={{
                          fontSize: '10px',
                          color: '#4caf50',
                          marginBottom: '4px',
                          fontWeight: 'bold'
                        }}>
                          📄 JSON
                        </div>
                      )}
                      {(sectionData as any)?.formatted || (
                        typeof sectionData === 'string' ? (
                          sectionData || '(empty string)'
                        ) : sectionData === null ? (
                          '(null)'
                        ) : sectionData === undefined ? (
                          '(undefined)'
                        ) : (
                          JSON.stringify(sectionData, null, 2)
                        )
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        ) : (
          renderNIP55Tab()
        )}
      </div>
    </div>
  )
}