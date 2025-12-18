import { useEffect, useState } from 'react'
import { useSettings }         from '@/context/settings.js'
import { QRScanner }           from '@/components/util/scanner.js'

import {
  decode_group_pkg,
  encode_group_pkg
} from '@frostr/bifrost/encoder'

import type { GroupPackage } from '@frostr/bifrost'

export function GroupConfigField() {
  const store = useSettings()

  const [ input, setInput ] = useState<string>('')
  const [ error, setError ] = useState<string | null>(null)
  const [ show, setShow   ] = useState<boolean>(false)
  const [ saved, setSaved ] = useState<boolean>(false)

  const [ isScanning, setIsScanning ] = useState<boolean>(false)

  /**
   * Handle the update of the store.
   */
  const update = (input : string) => {
    // If an error exists, do not update the group.
    if (error !== null) return
    // If the input is empty,
    if (input === '') {
      store.update({ group : null })
    } else {
      try {
        // Parse the input into a group package.
        const group = decode_group_pkg(input)
        // If the group package is invalid, return.
        if (group === null) return
        // Update the group in the store.
        store.update({ group })
      } catch {
        setError('failed to decode package data')
      }
    }
    // Set the saved state, and reset it after a short delay.
    setSaved(true)
    setTimeout(() => setSaved(false), 1500)
  }

  useEffect(() => {
    try {
      if (store.data.group !== null) {
        setInput(get_group_str(store.data.group))
      } else {
        setInput('')
      }
    } catch {
      setInput('')
    }
  }, [ store.data.group ])

  /**
   * Handle the validation of the input when it changes.
   */
  useEffect(() => {
    // If the input is empty, clear the error.
    if (input === '') {
      setError(null)
    } else if (!input.startsWith('bfgroup')) {
      // If the input does not start with "bfgroup", set an error.
      setError('input must start with "bfgroup"')
    } else if (!is_group_string(input)) {
      // If the input contains invalid characters, set an error.
      setError('input contains invalid characters')
    } else {
      // Parse the input into a group package.
      const pkg = get_group_pkg(input)
      // If the group package is valid, clear the error.
      if (pkg !== null) {
        setError(null)
      } else {
        // If the credential package is invalid, set an error.
        setError('failed to decode package data')
      }
    }
  }, [ input ])

  return (
    <div className="container">
      <h2 className="section-header">Group Package</h2>
      <p className="description">Paste your encoded group string (starts with bfgroup). It contains information about your signing group.</p>
      <div className="content-container">
        <div className="input-with-button">
          <input
            type={show ? "text" : "password"}
            value={input}
            onChange={e => setInput(e.target.value.trim())}
            placeholder="bfgroup1..."
          />
          <div className="input-actions">
            <button 
              className="button"
              onClick={() => setShow(!show)}
            >
              {show ? 'hide' : 'show'}
            </button>
            <button
              className="button"
              onClick={() => setIsScanning(!isScanning)}
            >
              {isScanning ? 'stop scan' : 'scan'}
            </button>
            <button
              className={`button action-button ${saved ? 'saved-button' : ''}`} 
              onClick={() => update(input)}
              disabled={!is_group_changed(input, store.data.group) || error !== null}
            >
              {saved ? 'Saved' : 'Save'}
            </button>
          </div>
        </div>
        
        {isScanning && (
          <QRScanner
            onResult={(result: string) => {
              setInput(result.trim())
              setIsScanning(false)
            }}
            onError={() => {
              setIsScanning(false)
            }}
          />
        )}
        
        {input !== '' && error === null && show && (
          <pre className="code-display">
            {get_group_json(input) ?? 'invalid group package'}
          </pre>
        )}
        <div className="notification-container">
          {error && <p className="error-text">{error}</p>}
        </div>
      </div>
    </div>
  )
}

/**
 * Check if the input is a valid group string.
 */
function is_group_string(input : string) {
  return /^bfgroup1[023456789acdefghjklmnpqrstuvwxyz]+$/.test(input)
}

/**
 * Check if the input has changed and is valid.
 */
function is_group_changed (
  input : string,
  group : GroupPackage | null
) {
  if (group === null) {
    return input !== ''
  } else {
    // Encode the existing group to a string.
    const group_str = get_group_str(group)
    // Determine if the group input has changed and is valid.
    return input !== group_str
  }
}

/**
 * Get the group string from the package.
 */
function get_group_str (group : GroupPackage) {
  try {
    return (group !== null)
      ? encode_group_pkg(group)
      : ''
  } catch {
    return ''
  }
}

/**
 * Get the group package from the input.
 */
function get_group_pkg (input : string) {
  try {
    return (input !== '')
      ? decode_group_pkg(input)
      : null
  } catch {
    return null
  }
}

/**
 * Get the group JSON from the input.
 */
function get_group_json(input : string) {
  try {
    const group = decode_group_pkg(input)
    return JSON.stringify(group, null, 2)
  } catch (err) {
    return null
  }
}

