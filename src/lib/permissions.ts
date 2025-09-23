// Permission utility functions for NIP-55 request handling

import type {
  NIP55Request,
  PermissionPolicy,
  PermActionRecord,
  PermEventRecord
} from '@/types.js'

/**
 * Check if an existing permission exists for a given request
 */
export function findExistingPermission(
  request: NIP55Request,
  permissions: PermissionPolicy[]
): PermActionRecord | PermEventRecord | null {
  for (const policy of permissions) {
    if (request.type === 'sign_event') {
      const eventRecord = policy.event.find((e: PermEventRecord) =>
        e.host === request.host && e.kind === request.event?.kind
      )
      if (eventRecord) return eventRecord
    } else {
      const actionRecord = policy.action.find((a: PermActionRecord) =>
        a.host === request.host && a.action === request.type
      )
      if (actionRecord) return actionRecord
    }
  }
  return null
}

/**
 * Add a new permission record to the permissions array
 */
export function addPermissionRecord(
  request: NIP55Request,
  accept: boolean,
  permissions: PermissionPolicy[]
): PermissionPolicy[] {
  const timestamp = Math.floor(Date.now() / 1000)
  const updatedPermissions = [...permissions]

  // Find existing policy for this host or create new one
  let policy = updatedPermissions.find(p =>
    p.action.some(a => a.host === request.host) ||
    p.event.some(e => e.host === request.host)
  )

  if (!policy) {
    policy = { action: [], event: [] }
    updatedPermissions.push(policy)
  }

  // Add the new permission
  if (request.type === 'sign_event') {
    // Remove any existing permission for this host/kind combination
    policy.event = policy.event.filter((e: PermEventRecord) =>
      !(e.host === request.host && e.kind === request.event?.kind)
    )

    policy.event.push({
      host: request.host,
      kind: request.event?.kind || 0,
      type: 'event',
      accept,
      created_at: timestamp
    })
  } else {
    // Remove any existing permission for this host/action combination
    policy.action = policy.action.filter((a: PermActionRecord) =>
      !(a.host === request.host && a.action === request.type)
    )

    policy.action.push({
      host: request.host,
      action: request.type,
      type: 'action',
      accept,
      created_at: timestamp
    })
  }

  return updatedPermissions
}

/**
 * Clean up duplicate permissions (remove older duplicates)
 */
export function deduplicatePermissions(permissions: PermissionPolicy[]): PermissionPolicy[] {
  return permissions.map(policy => ({
    action: deduplicateActionRecords(policy.action),
    event: deduplicateEventRecords(policy.event)
  })).filter(policy => policy.action.length > 0 || policy.event.length > 0)
}

function deduplicateActionRecords(actions: PermActionRecord[]): PermActionRecord[] {
  const seen = new Map<string, PermActionRecord>()

  for (const action of actions) {
    const key = `${action.host}:${action.action}`
    const existing = seen.get(key)

    if (!existing || action.created_at > existing.created_at) {
      seen.set(key, action)
    }
  }

  return Array.from(seen.values())
}

function deduplicateEventRecords(events: PermEventRecord[]): PermEventRecord[] {
  const seen = new Map<string, PermEventRecord>()

  for (const event of events) {
    const key = `${event.host}:${event.kind}`
    const existing = seen.get(key)

    if (!existing || event.created_at > existing.created_at) {
      seen.set(key, event)
    }
  }

  return Array.from(seen.values())
}