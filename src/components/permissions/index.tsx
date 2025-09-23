import { useEffect, useState } from 'react'
import { useSettings } from '@/context/settings.js'

import type {
  PermissionPolicy,
  PermActionRecord,
  PermEventRecord
} from '@/types.js'

export function PermissionsView() {
  const settings = useSettings()

  const [permissions, setPermissions] = useState<PermissionPolicy[]>([])
  const [changes, setChanges] = useState<boolean>(false)
  const [saved, setSaved] = useState<boolean>(false)

  // Update permissions in the store
  const update = () => {
    settings.update({ perms: permissions })
    setChanges(false)
    setSaved(true)
    setTimeout(() => setSaved(false), 1500)
  }

  // Discard changes by resetting local state from store
  const cancel = () => {
    setPermissions(settings.data.perms)
    setChanges(false)
  }

  // Remove an action permission
  const removeActionPermission = (policyIdx: number, actionIdx: number) => {
    const updatedPermissions = [...permissions]
    updatedPermissions[policyIdx].action.splice(actionIdx, 1)
    setPermissions(updatedPermissions)
    setChanges(true)
  }

  // Remove an event permission
  const removeEventPermission = (policyIdx: number, eventIdx: number) => {
    const updatedPermissions = [...permissions]
    updatedPermissions[policyIdx].event.splice(eventIdx, 1)
    setPermissions(updatedPermissions)
    setChanges(true)
  }

  // Format timestamp for display
  const formatTimestamp = (timestamp: number) => {
    return new Date(timestamp * 1000).toLocaleString()
  }

  useEffect(() => {
    setPermissions(settings.data.perms ?? [])
  }, [settings.data.perms])

  // Get all action permissions across all policies
  const getAllActionPermissions = () => {
    const actions: Array<{permission: PermActionRecord, policyIdx: number, actionIdx: number}> = []
    permissions.forEach((policy, policyIdx) => {
      policy.action.forEach((action, actionIdx) => {
        actions.push({ permission: action, policyIdx, actionIdx })
      })
    })
    return actions
  }

  // Get all event permissions across all policies
  const getAllEventPermissions = () => {
    const events: Array<{permission: PermEventRecord, policyIdx: number, eventIdx: number}> = []
    permissions.forEach((policy, policyIdx) => {
      policy.event.forEach((event, eventIdx) => {
        events.push({ permission: event, policyIdx, eventIdx })
      })
    })
    return events
  }

  const actionPermissions = getAllActionPermissions()
  const eventPermissions = getAllEventPermissions()

  return (
    <div className="container">
      <h2 className="section-header">Permissions Management</h2>
      <p className="description">
        View and manage application permissions. These permissions control which actions and events
        external applications can perform or access through your signing device.
      </p>

      {/* Actions Table */}
      <div className="settings-section">
        <h3>Action Permissions</h3>
        <p className="description">
          Action permissions control which operations external applications can perform.
        </p>

        {actionPermissions.length === 0 ? (
          <p className="text-muted">No action permissions configured.</p>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th>Host</th>
                  <th>Action</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th className="action-cell">Actions</th>
                </tr>
              </thead>
              <tbody>
                {actionPermissions.map(({ permission, policyIdx, actionIdx }, idx) => (
                  <tr key={idx}>
                    <td className="overflow-cell">{permission.host}</td>
                    <td>{permission.action}</td>
                    <td>
                      <span className={`status-indicator ${permission.accept ? 'online' : 'offline'}`}>
                        {permission.accept ? 'Allowed' : 'Denied'}
                      </span>
                    </td>
                    <td>{formatTimestamp(permission.created_at)}</td>
                    <td className="action-cell">
                      <button
                        onClick={() => removeActionPermission(policyIdx, actionIdx)}
                        className="button button-remove"
                        title="Remove permission"
                      >
                        ×
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Events Table */}
      <div className="settings-section">
        <h3>Event Permissions</h3>
        <p className="description">
          Event permissions control which event types external applications can access.
        </p>

        {eventPermissions.length === 0 ? (
          <p className="text-muted">No event permissions configured.</p>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th>Host</th>
                  <th>Event Kind</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th className="action-cell">Actions</th>
                </tr>
              </thead>
              <tbody>
                {eventPermissions.map(({ permission, policyIdx, eventIdx }, idx) => (
                  <tr key={idx}>
                    <td className="overflow-cell">{permission.host}</td>
                    <td>{permission.kind}</td>
                    <td>
                      <span className={`status-indicator ${permission.accept ? 'online' : 'offline'}`}>
                        {permission.accept ? 'Allowed' : 'Denied'}
                      </span>
                    </td>
                    <td>{formatTimestamp(permission.created_at)}</td>
                    <td className="action-cell">
                      <button
                        onClick={() => removeEventPermission(policyIdx, eventIdx)}
                        className="button button-remove"
                        title="Remove permission"
                      >
                        ×
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Save/Cancel buttons */}
      {changes && (
        <div className="action-buttons">
          <button
            onClick={update}
            className={`button button-primary action-button ${saved ? 'saved-button' : ''}`}
          >
            {saved ? 'Saved' : 'Save Changes'}
          </button>

          <button
            onClick={cancel}
            className="button"
          >
            Cancel
          </button>
        </div>
      )}
    </div>
  )
}