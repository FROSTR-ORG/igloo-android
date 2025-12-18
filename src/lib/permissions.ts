/**
 * NIP-55 Permission Storage System
 *
 * Clean implementation with event kind filtering support.
 * No legacy compatibility code.
 */

import type { PermissionRule, PermissionStorage, NIP55OperationType, PermissionStatus, BulkPermissionRequest } from '@/types/permissions.js'
import { STORAGE_KEYS } from '@/const.js'

const STORAGE_KEY = STORAGE_KEYS.PERMISSIONS
const STORAGE_VERSION = 2

/**
 * Initialize permission storage
 */
export function initPermissionStorage(): void {
  // Initialize new format if not exists
  const existing = localStorage.getItem(STORAGE_KEY)
  if (!existing) {
    const storage: PermissionStorage = {
      version: STORAGE_VERSION,
      permissions: []
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(storage))
  }
}

/**
 * Get all stored permissions
 */
function getStoredPermissions(): PermissionRule[] {
  try {
    const data = localStorage.getItem(STORAGE_KEY)
    if (!data) return []

    const storage: PermissionStorage = JSON.parse(data)
    return storage.permissions || []
  } catch {
    return []
  }
}

/**
 * Save permissions to storage
 */
function savePermissions(permissions: PermissionRule[]): void {
  try {
    const storage: PermissionStorage = {
      version: STORAGE_VERSION,
      permissions
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(storage))
  } catch {
    throw new Error('Permission storage failed')
  }
}

/**
 * Check if permission exists for a specific request
 * Implements kind-aware matching with wildcard fallback
 */
export function checkPermission(
  appId: string,
  type: NIP55OperationType,
  eventKind?: number
): PermissionStatus {
  const permissions = getStoredPermissions()

  // For sign_event, check kind-specific permission first
  if (type === 'sign_event' && eventKind !== undefined) {
    const kindSpecific = permissions.find(p =>
      p.appId === appId &&
      p.type === type &&
      p.kind === eventKind
    )

    if (kindSpecific) {
      return kindSpecific.allowed ? 'allowed' : 'denied'
    }

    // Fall back to wildcard permission
    const wildcard = permissions.find(p =>
      p.appId === appId &&
      p.type === type &&
      p.kind === undefined
    )

    if (wildcard) {
      return wildcard.allowed ? 'allowed' : 'denied'
    }
  } else {
    // For non-sign_event operations, simple lookup
    const permission = permissions.find(p =>
      p.appId === appId &&
      p.type === type &&
      p.kind === undefined
    )

    if (permission) {
      return permission.allowed ? 'allowed' : 'denied'
    }
  }

  return 'prompt_required'
}

/**
 * Add or update a single permission
 */
export function setPermission(
  appId: string,
  type: NIP55OperationType,
  allowed: boolean,
  kind?: number
): void {
  const permissions = getStoredPermissions()

  // Remove existing permission with same appId + type + kind
  const filtered = permissions.filter(p =>
    !(p.appId === appId && p.type === type && p.kind === kind)
  )

  // Add new permission
  const newPermission: PermissionRule = {
    appId,
    type,
    kind: kind !== undefined ? kind : undefined,
    allowed,
    timestamp: Date.now()
  }

  filtered.push(newPermission)
  savePermissions(filtered)
}

/**
 * Revoke a specific permission
 */
export function revokePermission(
  appId: string,
  type: NIP55OperationType,
  kind?: number
): void {
  const permissions = getStoredPermissions()
  const filtered = permissions.filter(p =>
    !(p.appId === appId && p.type === type && p.kind === kind)
  )

  savePermissions(filtered)
}

/**
 * Revoke all permissions for an app
 */
export function revokeAllForApp(appId: string): void {
  const permissions = getStoredPermissions()
  const filtered = permissions.filter(p => p.appId !== appId)

  savePermissions(filtered)
}

/**
 * Get all permissions (optionally filtered by app)
 */
export function listPermissions(appId?: string): PermissionRule[] {
  const permissions = getStoredPermissions()

  if (appId) {
    return permissions.filter(p => p.appId === appId)
  }

  return permissions
}

/**
 * Parse NIP-55 permissions array from get_public_key request
 */
export function parseNIP55Permissions(
  permissionsJson: string,
  appId: string
): BulkPermissionRequest {
  try {
    const parsed = JSON.parse(permissionsJson)

    if (!Array.isArray(parsed)) {
      throw new Error('Permissions must be an array')
    }

    const permissions = parsed.map(p => ({
      type: p.type as NIP55OperationType,
      kind: p.kind !== undefined ? Number(p.kind) : undefined
    }))

    return {
      appId,
      permissions
    }
  } catch {
    throw new Error('Invalid permissions format')
  }
}

/**
 * Bulk add permissions (atomic operation)
 */
export function bulkSetPermissions(
  appId: string,
  permissionsToAdd: Array<{ type: NIP55OperationType; kind?: number }>,
  allowed: boolean
): void {
  const existing = getStoredPermissions()
  const timestamp = Date.now()

  // Create new permission rules
  const newRules: PermissionRule[] = permissionsToAdd.map(p => ({
    appId,
    type: p.type,
    kind: p.kind !== undefined ? p.kind : undefined,
    allowed,
    timestamp
  }))

  // Remove duplicates from existing permissions
  const filtered = existing.filter(existingPerm =>
    !newRules.some(newPerm =>
      newPerm.appId === existingPerm.appId &&
      newPerm.type === existingPerm.type &&
      newPerm.kind === existingPerm.kind
    )
  )

  // Add new permissions
  const updated = [...filtered, ...newRules]
  savePermissions(updated)
}

/**
 * Get human-readable event kind label
 */
export function getEventKindLabel(kind: number): string {
  const labels: Record<number, string> = {
    0: 'Metadata',
    1: 'Text Note',
    3: 'Contacts',
    4: 'Direct Message',
    5: 'Event Deletion',
    6: 'Repost',
    7: 'Reaction',
    22242: 'Relay Authentication',
    23194: 'Wallet Info',
    23195: 'Wallet Request',
    24133: 'Nostr Connect',
    27235: 'NWC Request',
    30023: 'Long-form Content',
    30078: 'App Data'
  }

  return labels[kind] || `Event Kind ${kind}`
}
