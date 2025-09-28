import { useState, useEffect } from 'react'
import { getAllPermissionRules, removePermissionRule, clearAllPermissions } from '@/lib/permissions.js'

import type { ReactElement } from 'react'

interface SimplePermissionRule {
  appId: string
  type: string
  allowed: boolean
  timestamp: number
}

/**
 * Basic Permissions Management Component
 *
 * Displays stored permission rules and allows basic management
 */
export function PermissionsView(): ReactElement {
  const [rules, setRules] = useState<SimplePermissionRule[]>([])
  const [loading, setLoading] = useState(true)

  const loadRules = () => {
    try {
      const storedRules = getAllPermissionRules()
      setRules(storedRules)
    } catch (error) {
      console.error('Failed to load permissions:', error)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadRules()
  }, [])

  const handleRemoveRule = (appId: string, type: string) => {
    try {
      removePermissionRule(appId, type)
      loadRules() // Refresh the list
    } catch (error) {
      console.error('Failed to remove permission rule:', error)
    }
  }

  const handleClearAll = () => {
    if (window.confirm('Are you sure you want to clear all permissions? This cannot be undone.')) {
      try {
        clearAllPermissions()
        loadRules() // Refresh the list
      } catch (error) {
        console.error('Failed to clear permissions:', error)
      }
    }
  }

  const formatDate = (timestamp: number) => {
    return new Date(timestamp).toLocaleDateString()
  }

  if (loading) {
    return (
      <div className="permissions-view">
        <h2>Permissions</h2>
        <p>Loading permissions...</p>
      </div>
    )
  }

  return (
    <div className="permissions-view">
      <div className="permissions-header">
        <h2>App Permissions</h2>
        <p>Manage which apps can perform signing operations without prompting.</p>
      </div>

      {rules.length === 0 ? (
        <div className="no-permissions">
          <p>No permission rules have been set yet.</p>
          <p>Grant permissions to apps by checking "Remember my choice" when prompted.</p>
        </div>
      ) : (
        <div className="permissions-list">
          <div className="permissions-controls">
            <button
              className="clear-all-button"
              onClick={handleClearAll}
              type="button"
            >
              Clear All Permissions
            </button>
          </div>

          <div className="permissions-table">
            <div className="table-header">
              <span>App</span>
              <span>Action</span>
              <span>Status</span>
              <span>Date Added</span>
              <span>Remove</span>
            </div>

            {rules.map((rule, index) => (
              <div key={`${rule.appId}-${rule.type}-${index}`} className="table-row">
                <span className="app-id">{rule.appId}</span>
                <span className="action-type">{rule.type}</span>
                <span className={`status ${rule.allowed ? 'allowed' : 'denied'}`}>
                  {rule.allowed ? 'Allowed' : 'Denied'}
                </span>
                <span className="date">{formatDate(rule.timestamp)}</span>
                <button
                  className="remove-button"
                  onClick={() => handleRemoveRule(rule.appId, rule.type)}
                  type="button"
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}