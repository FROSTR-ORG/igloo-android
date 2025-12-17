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
  const [ show_filter, set_show_filter ] = useState<'all' | 'allowed' | 'denied'>('all')

  const load_permissions = async () => {
    try {
      set_loading(true)
      const all_perms = await permissions_api.list_permissions()
      set_permissions(all_perms)
    } catch (error) {
      console.error('Failed to load permissions:', error)
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
      const kindStr = permission.kind !== undefined ? `:${permission.kind}` : ''
      console.log(`Removed permission: ${permission.appId}:${permission.type}${kindStr}`)
    } catch (error) {
      console.error('Failed to remove permission:', error)
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
        console.log('All permissions cleared')
      } catch (error) {
        console.error('Failed to clear permissions:', error)
      }
    }
  }

  const format_date = (timestamp: number) => {
    return new Date(timestamp).toLocaleDateString('en-US', {
      year   : 'numeric',
      month  : 'short',
      day    : 'numeric',
      hour   : '2-digit',
      minute : '2-digit'
    })
  }

  const format_operation_type = (type: string) => {
    return type.replace(/_/g, ' ').toUpperCase()
  }

  const format_app_host = (appId: string) => {
    return appId
  }

  // Filter permissions based on selected filter
  const filtered_permissions = permissions.filter(p => {
    if (show_filter === 'all') return true
    if (show_filter === 'allowed') return p.allowed === true
    if (show_filter === 'denied') return p.allowed === false
    return true
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
        <p>Apps with saved permissions won't prompt you again - they'll use your previous choice.</p>
        <p className="content-resolver-info">
          <strong>Content Resolver:</strong> Enables background operations for compatible Nostr clients.
        </p>
      </div>

      {permissions.length === 0 ? (
        <div className="no-permissions">
          <h3>No Saved Permissions</h3>
          <p>When you check "Remember my choice" during signing prompts, apps will be listed here.</p>
          <p>Saved permissions enable seamless background operations via Content Resolver.</p>
        </div>
      ) : (
        <div className="permissions-list">
          <div className="permissions-controls">
            <div className="controls-left">
              <span className="permission-count">
                {filtered_permissions.length} permission{filtered_permissions.length !== 1 ? 's' : ''}
                {show_filter !== 'all' && ` (${show_filter})`}
              </span>
              <select
                value={show_filter}
                onChange={(e) => set_show_filter(e.target.value as any)}
                style={{ marginLeft: '12px', padding: '4px' }}
              >
                <option value="all">All</option>
                <option value="allowed">Allowed</option>
                <option value="denied">Denied</option>
              </select>
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

          <div className="permissions-table">
            <div className="table-header">
              <span>App / Host</span>
              <span>Operation</span>
              <span>Status</span>
              <span>Date Saved</span>
              <span>Actions</span>
            </div>

            {filtered_permissions.map((permission, index) => (
              <div key={`${permission.appId}-${permission.type}-${permission.kind}-${index}`} className="table-row">
                <span className="app-host" title={format_app_host(permission.appId)}>
                  {format_app_host(permission.appId)}
                </span>
                <span className="operation-type">
                  {format_operation_type(permission.type)}
                  {permission.kind !== undefined && (
                    <span className="event-kind" title={`Event Kind ${permission.kind}`}>
                      {getEventKindLabel(permission.kind)}
                    </span>
                  )}
                </span>
                <span className={`permission-status ${permission.allowed ? 'allowed' : 'denied'}`}>
                  {permission.allowed ? '✅ Allowed' : '❌ Denied'}
                </span>
                <span className="date" title={new Date(permission.timestamp).toISOString()}>
                  {format_date(permission.timestamp)}
                </span>
                <div className="actions">
                  <button
                    className="revoke-button"
                    onClick={() => handle_revoke_permission(permission)}
                    type="button"
                    title="Remove saved permission - app will prompt again"
                  >
                    Remove
                  </button>
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