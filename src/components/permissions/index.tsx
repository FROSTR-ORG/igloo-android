import { useState, useEffect }      from 'react'
import { usePermissions } from '@/context/permissions.js'
import { getEventKindLabel } from '@/lib/permissions.js'
import '@/styles/permissions.css'

import type { ReactElement } from 'react'
import type { Permission }   from '@/types/permissions.js'

/**
 * Simple Permissions Management Component
 *
 * Displays and manages saved permissions (allowed/denied) for NIP-55 operations.
 */
export function PermissionsView(): ReactElement {
  const permissions_api = usePermissions()
  const [ permissions, set_permissions ] = useState<Permission[]>([])
  const [ loading, set_loading ] = useState(true)
  const [ app_filter, set_app_filter ] = useState('')

  const load_permissions = async () => {
    try {
      set_loading(true)
      const all_perms = await permissions_api.list_permissions()
      set_permissions(all_perms)
    } catch {
      // Failed to load permissions
    } finally {
      set_loading(false)
    }
  }

  useEffect(() => {
    load_permissions()
  }, [])

  const handle_revoke_permission = async (permission: Permission) => {
    try {
      // IMPORTANT: Pass the kind parameter to properly match kind-specific permissions
      await permissions_api.revoke_permission(permission.appId, permission.type, permission.kind)
      await load_permissions() // Refresh the list
    } catch {
      // Failed to remove permission
    }
  }

  const handle_clear_all_permissions = async () => {
    if (window.confirm('Clear all saved permissions? Apps will prompt again for future requests.')) {
      try {
        // Remove all permissions one by one with proper kind parameter
        for (const permission of permissions) {
          await permissions_api.revoke_permission(permission.appId, permission.type, permission.kind)
        }
        await load_permissions()
      } catch {
        // Failed to clear permissions
      }
    }
  }

  const format_date = (timestamp: number) => {
    const now = Date.now()
    const diff = now - timestamp
    const minutes = Math.floor(diff / 60000)
    const hours = Math.floor(diff / 3600000)
    const days = Math.floor(diff / 86400000)

    if (minutes < 1) return 'just now'
    if (minutes < 60) return `${minutes}m ago`
    if (hours < 24) return `${hours}h ago`
    if (days < 7) return `${days}d ago`

    return new Date(timestamp).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric'
    })
  }

  const format_operation_type = (type: string) => {
    return type.replace(/_/g, ' ').toUpperCase()
  }

  const format_app_host = (appId: string) => {
    return appId
  }

  // Filter permissions based on app ID prefix match
  const filtered_permissions = permissions.filter(p => {
    if (app_filter === '') return true
    return p.appId.toLowerCase().startsWith(app_filter.toLowerCase())
  })

  if (loading) {
    return (
      <div className="permissions-view">
        <h2>Saved Permissions</h2>
        <p>Loading permissions...</p>
      </div>
    )
  }

  return (
    <div className="permissions-view">
      <div className="permissions-header">
        <h2>Saved Permissions</h2>
        <p>Apps with saved permissions will be listed here.</p>
      </div>

      {permissions.length === 0 ? (
        <div className="no-permissions">
          <h3>No Saved Permissions</h3>
        </div>
      ) : (
        <div className="permissions-list">
          <div className="permissions-controls">
            <div className="controls-left">
              <input
                type="text"
                value={app_filter}
                onChange={(e) => set_app_filter(e.target.value)}
                placeholder="Filter by app ID..."
                className="app-filter-input"
              />
            </div>
            <div className="controls-right">
              <button
                className="clear-all-button"
                onClick={handle_clear_all_permissions}
                type="button"
                disabled={permissions.length === 0}
              >
                Clear All
              </button>
            </div>
          </div>

          <div className="permissions-list-items">
            {filtered_permissions.map((permission, index) => (
              <div key={`${permission.appId}-${permission.type}-${permission.kind}-${index}`} className="permission-card">
                <div className="permission-main">
                  <div className="permission-info">
                    <div className="permission-app">{format_app_host(permission.appId)}</div>
                    <div className="permission-details">
                      <span className="permission-op">{format_operation_type(permission.type)}</span>
                      {permission.kind !== undefined && (
                        <span className="permission-kind">{getEventKindLabel(permission.kind)}</span>
                      )}
                      <span className="permission-time" title={new Date(permission.timestamp).toISOString()}>
                        {format_date(permission.timestamp)}
                      </span>
                    </div>
                  </div>
                  <div className="permission-actions">
                    <span className={`permission-badge ${permission.allowed ? 'allowed' : 'denied'}`}>
                      {permission.allowed ? 'Allowed' : 'Denied'}
                    </span>
                    <button
                      className="permission-remove"
                      onClick={() => handle_revoke_permission(permission)}
                      type="button"
                      title="Remove saved permission"
                    >
                      ×
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>

          <div className="permissions-footer">
            <p className="footer-note">
              <strong>Note:</strong> Removing permissions returns apps to interactive prompt mode.
              No data is permanently deleted.
            </p>
          </div>
        </div>
      )}
    </div>
  )
}