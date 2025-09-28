/**
 * Simple Permission Manager
 *
 * Basic permission storage using localStorage for NIP-55 requests.
 * Supports simple allow/deny rules without complex synchronization.
 */

import type { NIP55Request } from '@/types/index.js'

interface SimplePermissionRule {
  appId: string
  type: string
  allowed: boolean
  timestamp: number
}

const STORAGE_KEY = 'nip55_permissions'

/**
 * Get all stored permission rules
 */
function getStoredRules(): SimplePermissionRule[] {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored ? JSON.parse(stored) : []
  } catch (error) {
    console.error('Failed to load permissions:', error)
    return []
  }
}

/**
 * Save permission rules to localStorage
 */
function saveRules(rules: SimplePermissionRule[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(rules))
  } catch (error) {
    console.error('Failed to save permissions:', error)
  }
}

/**
 * Create a permission key for a request
 */
function createPermissionKey(appId: string, type: string): string {
  return `${appId}:${type}`
}

/**
 * Check if a request has an existing permission rule
 */
export function checkPermission(request: NIP55Request): 'allowed' | 'denied' | 'prompt_required' {
  const rules = getStoredRules()
  const appId = request.host || 'unknown'
  const key = createPermissionKey(appId, request.type)

  const rule = rules.find(r => createPermissionKey(r.appId, r.type) === key)

  if (rule) {
    return rule.allowed ? 'allowed' : 'denied'
  }

  return 'prompt_required'
}

/**
 * Add a permission rule (when user chooses "remember")
 */
export function addPermissionRule(appId: string, type: string, allowed: boolean): void {
  const rules = getStoredRules()
  const key = createPermissionKey(appId, type)

  // Remove existing rule for this app+type
  const filteredRules = rules.filter(r => createPermissionKey(r.appId, r.type) !== key)

  // Add new rule
  filteredRules.push({
    appId,
    type,
    allowed,
    timestamp: Date.now()
  })

  saveRules(filteredRules)
  console.log(`Permission rule added: ${appId}:${type} = ${allowed}`)
}

/**
 * Remove a permission rule
 */
export function removePermissionRule(appId: string, type: string): void {
  const rules = getStoredRules()
  const key = createPermissionKey(appId, type)

  const filteredRules = rules.filter(r => createPermissionKey(r.appId, r.type) !== key)
  saveRules(filteredRules)
  console.log(`Permission rule removed: ${appId}:${type}`)
}

/**
 * Get all permission rules for display/management
 */
export function getAllPermissionRules(): SimplePermissionRule[] {
  return getStoredRules()
}

/**
 * Clear all permission rules
 */
export function clearAllPermissions(): void {
  localStorage.removeItem(STORAGE_KEY)
  console.log('All permissions cleared')
}

/**
 * Legacy compatibility object for existing code
 * TODO: Remove this when all references are updated
 */
export const unifiedPermissions = {
  checkPermission,
  addAutoApprovalRule: (rule: any) => {
    // Legacy compatibility - convert to simple rule
    addPermissionRule(rule.appId, rule.scope.actions[0], true)
  },
  removeRule: removePermissionRule,
  clearAll: clearAllPermissions
}