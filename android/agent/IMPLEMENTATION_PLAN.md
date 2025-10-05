# NIP-55 Permissions System - Implementation Plan

**Goal**: Build a clean, production-ready permission system with event kind filtering and bulk permission support for Amethyst integration.

**Approach**: Clean slate implementation - no legacy code, no tech debt, no migration compatibility.

---

## Phase 1: Core Data Structure & Storage (1-2 hours)

### Task 1.1: Create Type Definitions
**File**: `src/types/permissions.ts` (NEW FILE)

```typescript
/**
 * NIP-55 Permission Rule with event kind filtering support
 *
 * @property appId - Package name of the calling app (e.g., "com.vitorpamplona.amethyst")
 * @property type - NIP-55 operation type (e.g., "sign_event", "nip04_encrypt")
 * @property kind - Optional event kind filter (undefined = wildcard, applies to all kinds)
 * @property allowed - Whether permission is granted (true) or denied (false)
 * @property timestamp - Unix timestamp when permission was granted
 */
export interface PermissionRule {
  appId: string
  type: NIP55OperationType
  kind: number | undefined  // Required field for clarity (not optional)
  allowed: boolean
  timestamp: number
}

/**
 * NIP-55 operation types
 */
export type NIP55OperationType =
  | 'get_public_key'
  | 'sign_event'
  | 'nip04_encrypt'
  | 'nip04_decrypt'
  | 'nip44_encrypt'
  | 'nip44_decrypt'
  | 'decrypt_zap_event'

/**
 * Permission check result
 */
export type PermissionStatus = 'allowed' | 'denied' | 'prompt_required'

/**
 * Permission storage structure with versioning
 */
export interface PermissionStorage {
  version: number  // Schema version for future migrations
  permissions: PermissionRule[]
}

/**
 * Bulk permission request from get_public_key
 */
export interface BulkPermissionRequest {
  appId: string
  appName?: string  // Optional human-readable name
  permissions: Array<{
    type: NIP55OperationType
    kind?: number
  }>
}
```

**Acceptance Criteria**:
- [ ] Type definitions compile without errors
- [ ] All NIP-55 operations covered in type union
- [ ] JSDoc comments explain each field clearly

---

### Task 1.2: Build Permission Storage System
**File**: `src/lib/permissions.ts` (COMPLETE REWRITE)

```typescript
/**
 * NIP-55 Permission Storage System
 *
 * Clean implementation with event kind filtering support.
 * No legacy compatibility code.
 */

import type { PermissionRule, PermissionStorage, NIP55OperationType, PermissionStatus, BulkPermissionRequest } from '@/types/permissions.js'

const STORAGE_KEY = 'nip55_permissions_v2'
const STORAGE_VERSION = 2

/**
 * Initialize permission storage (clear old format if exists)
 */
export function initPermissionStorage(): void {
  // Clear old permission format
  const oldKey = 'nip55_permissions'
  if (localStorage.getItem(oldKey)) {
    console.warn('Clearing old permission format - users will need to re-grant permissions')
    localStorage.removeItem(oldKey)
  }

  // Initialize new format if not exists
  const existing = localStorage.getItem(STORAGE_KEY)
  if (!existing) {
    const storage: PermissionStorage = {
      version: STORAGE_VERSION,
      permissions: []
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(storage))
    console.log('Initialized NIP-55 permission storage v2')
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
  } catch (error) {
    console.error('Failed to read permissions:', error)
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
  } catch (error) {
    console.error('Failed to save permissions:', error)
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

  console.log(`Permission ${allowed ? 'granted' : 'denied'}: ${appId}:${type}${kind ? `:${kind}` : ''}`)
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
  console.log(`Permission revoked: ${appId}:${type}${kind ? `:${kind}` : ''}`)
}

/**
 * Revoke all permissions for an app
 */
export function revokeAllForApp(appId: string): void {
  const permissions = getStoredPermissions()
  const filtered = permissions.filter(p => p.appId !== appId)

  savePermissions(filtered)
  console.log(`All permissions revoked for: ${appId}`)
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
  } catch (error) {
    console.error('Failed to parse NIP-55 permissions:', error)
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

  console.log(`Bulk ${allowed ? 'granted' : 'denied'} ${newRules.length} permissions for ${appId}`)
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
```

**Acceptance Criteria**:
- [ ] All functions implemented and typed correctly
- [ ] Kind-aware matching works with wildcard fallback
- [ ] Bulk operations are atomic (all succeed or all fail)
- [ ] Old permission storage cleared on init
- [ ] Comprehensive logging for debugging

---

### Task 1.3: Update Prompt Types
**File**: `src/types/prompt.ts`

**Changes**:
- Add `kind?: number` to `Permission` interface
- Update `PermissionAPI` interface with new methods

```typescript
export interface Permission {
  appId: string
  type: string
  kind?: number  // NEW
  allowed: boolean
  timestamp: number
}

export interface PermissionAPI {
  has_permission: (host: string, type: string, kind?: number) => Promise<boolean>
  set_permission: (host: string, type: string, allowed: boolean, kind?: number) => Promise<void>
  revoke_permission: (host: string, type: string, kind?: number) => Promise<void>
  list_permissions: (host?: string) => Promise<Permission[]>
  bulk_set_permissions: (rules: Permission[]) => Promise<void>  // NEW
  revoke_all_for_app: (host: string) => Promise<void>  // NEW
}
```

**Acceptance Criteria**:
- [ ] Type definitions updated
- [ ] No TypeScript errors

---

## Phase 2: Permission Checking Integration (2-3 hours)

### Task 2.1: Update PWA Permission Checking
**File**: `src/lib/signer.ts`

**Changes**:
- Extract event kind from `request.event?.kind`
- Pass kind to `checkPermission()`
- Update imports

```typescript
// Update check_permission function
async function check_permission(request: NIP55Request): Promise<'allowed' | 'prompt_required' | 'denied'> {
  try {
    // Extract event kind if this is a sign_event request
    let eventKind: number | undefined
    if (request.type === 'sign_event' && request.event?.kind !== undefined) {
      eventKind = request.event.kind
    }

    // Use new permission system
    const status = checkPermission(request.host, request.type, eventKind)

    console.log(`Permission check: ${request.host}:${request.type}${eventKind ? `:${eventKind}` : ''} = ${status}`)

    return status
  } catch (error) {
    console.error('Failed to check permission:', error)
    return 'prompt_required'
  }
}
```

**Acceptance Criteria**:
- [ ] Event kind extracted correctly from sign_event requests
- [ ] Permission checking uses new system
- [ ] Logging shows kind in output

---

### Task 2.2: Update Permission Context
**File**: `src/context/permissions.tsx` (MAJOR REFACTOR)

**Changes**:
- Replace all permission logic with new system
- Add kind parameters
- Implement bulk operations

```typescript
import { checkPermission, setPermission, revokePermission, listPermissions, bulkSetPermissions, revokeAllForApp, initPermissionStorage } from '@/lib/permissions.js'
import type { PermissionRule } from '@/types/permissions.js'

// Initialize on module load
initPermissionStorage()

export function PermissionsProvider({ children }: { children: React.ReactNode }) {
  // Implement context provider with new API
  const has_permission = async (host: string, type: string, kind?: number): Promise<boolean> => {
    const status = checkPermission(host, type as any, kind)
    return status === 'allowed'
  }

  const set_permission = async (host: string, type: string, allowed: boolean, kind?: number): Promise<void> => {
    setPermission(host, type as any, allowed, kind)
  }

  const revoke_permission = async (host: string, type: string, kind?: number): Promise<void> => {
    revokePermission(host, type as any, kind)
  }

  const list_permissions = async (host?: string): Promise<PermissionRule[]> => {
    return listPermissions(host)
  }

  const bulk_set_permissions = async (rules: PermissionRule[]): Promise<void> => {
    // Implement bulk set logic
  }

  const revoke_all_for_app = async (host: string): Promise<void> => {
    revokeAllForApp(host)
  }

  // ... rest of context implementation
}
```

**Acceptance Criteria**:
- [ ] All context methods use new permission system
- [ ] Kind parameter threaded through all methods
- [ ] Bulk operations implemented

---

### Task 2.3: Update Prompt Context
**File**: `src/context/prompt.tsx`

**Changes**:
- Extract kind from request when saving permission
- Pass kind to `setPermission()`

```typescript
// In approve() function
if (remember && request.host) {
  try {
    // Extract event kind for sign_event requests
    let eventKind: number | undefined
    if (request.type === 'sign_event' && request.event?.kind !== undefined) {
      eventKind = request.event.kind
    }

    setPermission(request.host, request.type, true, eventKind)
    console.log(`Permission saved: ${request.host}:${request.type}${eventKind ? `:${eventKind}` : ''}`)
  } catch (error) {
    console.error('Failed to create permission rule:', error)
  }
}
```

**Acceptance Criteria**:
- [ ] Event kind extracted from sign_event requests
- [ ] Kind passed to setPermission()
- [ ] Logging confirms kind is captured

---

### Task 2.4: Update Android Permission Checking
**File**: `android/app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt`

**Changes**:
- Extract event kind from params
- Update `checkPermission()` to be kind-aware

```kotlin
// Update Permission data class
data class Permission(
    val appId: String,
    val type: String,
    val kind: Int? = null,  // NEW
    val allowed: Boolean,
    val timestamp: Long
)

// Update checkPermission() function
private fun checkPermission(request: NIP55Request): String {
    return try {
        val storageBridge = StorageBridge(this)
        val permissionsJson = storageBridge.getItem("local", "nip55_permissions_v2")
            ?: return "prompt_required"

        val permissionListType = object : com.google.gson.reflect.TypeToken<PermissionStorage>() {}.type
        val storage: PermissionStorage = gson.fromJson(permissionsJson, permissionListType)
        val permissions = storage.permissions

        // Extract event kind for sign_event requests
        val eventKind: Int? = if (request.type == "sign_event") {
            request.params["event"]?.let { eventJson ->
                try {
                    val event = gson.fromJson(eventJson, Map::class.java)
                    (event["kind"] as? Double)?.toInt()
                } catch (e: Exception) {
                    null
                }
            }
        } else null

        // Kind-aware matching
        if (request.type == "sign_event" && eventKind != null) {
            // Check kind-specific permission first
            val kindSpecific = permissions.find { p ->
                p.appId == request.callingApp &&
                p.type == request.type &&
                p.kind == eventKind
            }

            if (kindSpecific != null) {
                return if (kindSpecific.allowed) "allowed" else "denied"
            }

            // Fall back to wildcard
            val wildcard = permissions.find { p ->
                p.appId == request.callingApp &&
                p.type == request.type &&
                p.kind == null
            }

            if (wildcard != null) {
                return if (wildcard.allowed) "allowed" else "denied"
            }
        } else {
            // Simple lookup for non-sign_event
            val permission = permissions.find { p ->
                p.appId == request.callingApp &&
                p.type == request.type &&
                p.kind == null
            }

            if (permission != null) {
                return if (permission.allowed) "allowed" else "denied"
            }
        }

        "prompt_required"
    } catch (e: Exception) {
        Log.e(TAG, "Failed to check permission", e)
        "prompt_required"
    }
}

data class PermissionStorage(
    val version: Int,
    val permissions: List<Permission>
)
```

**Acceptance Criteria**:
- [ ] Permission data class updated with kind field
- [ ] Event kind extracted from sign_event params
- [ ] Kind-aware matching implemented with wildcard fallback
- [ ] Reads from new storage key `nip55_permissions_v2`

---

### Task 2.5: Update Content Resolver Permission Checking
**File**: `android/app/src/main/kotlin/com/frostr/igloo/IglooContentProvider.kt`

**Changes**: Same kind-aware matching logic as InvisibleNIP55Handler

**Acceptance Criteria**:
- [ ] Kind extraction implemented
- [ ] Permission checking uses kind-aware matching
- [ ] Reads from new storage key

---

## Phase 3: Bulk Permissions & get_public_key Flow (3-4 hours)

### Task 3.1: Parse Permissions in Android
**File**: `android/app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt`

**Changes**:
- Parse permissions JSON array in `parseNIP55RequestParams()`
- Forward to MainActivity

```kotlin
"get_public_key" -> {
    val permissionsJson = intent.getStringExtra("permissions")
    if (permissionsJson != null) {
        params["permissions"] = permissionsJson
        params["requires_bulk_approval"] = "true"
        Log.d(TAG, "get_public_key includes permissions for bulk approval")
    }
}
```

**Acceptance Criteria**:
- [ ] Permissions JSON extracted
- [ ] Flag set to trigger bulk approval flow
- [ ] Logged for debugging

---

### Task 3.2: Create Bulk Permission Prompt Component
**File**: `src/components/prompt/bulk-permission-prompt.tsx` (NEW FILE)

```typescript
import { useState } from 'react'
import type { BulkPermissionRequest } from '@/types/permissions.js'
import { bulkSetPermissions, getEventKindLabel } from '@/lib/permissions.js'

interface BulkPermissionPromptProps {
  request: BulkPermissionRequest
  onApprove: () => void
  onDeny: () => void
}

export function BulkPermissionPrompt({ request, onApprove, onDeny }: BulkPermissionPromptProps) {
  const [selectedPermissions, setSelectedPermissions] = useState<Set<number>>(
    new Set(request.permissions.map((_, i) => i))
  )

  const handleApproveAll = async () => {
    await bulkSetPermissions(
      request.appId,
      request.permissions,
      true
    )
    onApprove()
  }

  const handleApproveSelected = async () => {
    const selected = request.permissions.filter((_, i) => selectedPermissions.has(i))
    await bulkSetPermissions(
      request.appId,
      selected,
      true
    )
    onApprove()
  }

  const handleDeny = () => {
    onDeny()
  }

  const togglePermission = (index: number) => {
    const newSet = new Set(selectedPermissions)
    if (newSet.has(index)) {
      newSet.delete(index)
    } else {
      newSet.add(index)
    }
    setSelectedPermissions(newSet)
  }

  return (
    <div className="bulk-permission-prompt">
      <div className="prompt-header">
        <h2>Permission Request</h2>
        <p className="app-name">{request.appName || request.appId}</p>
        <p className="description">
          This app is requesting the following permissions:
        </p>
      </div>

      <div className="permissions-list">
        {request.permissions.map((perm, index) => (
          <div key={index} className="permission-item">
            <label>
              <input
                type="checkbox"
                checked={selectedPermissions.has(index)}
                onChange={() => togglePermission(index)}
              />
              <span className="permission-label">
                {formatPermissionLabel(perm.type, perm.kind)}
              </span>
            </label>
          </div>
        ))}
      </div>

      <div className="prompt-actions">
        <button onClick={handleDeny} className="btn-deny">
          Deny All
        </button>
        <button
          onClick={handleApproveSelected}
          className="btn-approve-selected"
          disabled={selectedPermissions.size === 0}
        >
          Approve Selected ({selectedPermissions.size})
        </button>
        <button onClick={handleApproveAll} className="btn-approve-all">
          Approve All
        </button>
      </div>
    </div>
  )
}

function formatPermissionLabel(type: string, kind?: number): string {
  const typeLabels: Record<string, string> = {
    'get_public_key': 'Read your public key',
    'sign_event': 'Sign events',
    'nip04_encrypt': 'Encrypt messages (NIP-04)',
    'nip04_decrypt': 'Decrypt messages (NIP-04)',
    'nip44_encrypt': 'Encrypt messages (NIP-44)',
    'nip44_decrypt': 'Decrypt messages (NIP-44)',
    'decrypt_zap_event': 'Decrypt zap receipts'
  }

  const baseLabel = typeLabels[type] || type

  if (type === 'sign_event' && kind !== undefined) {
    return `${baseLabel}: ${getEventKindLabel(kind)}`
  }

  return baseLabel
}
```

**Acceptance Criteria**:
- [ ] Full-screen modal with distinct design
- [ ] Shows all requested permissions
- [ ] Event kinds shown with human-readable labels
- [ ] Checkbox selection works
- [ ] All three actions work (approve all, approve selected, deny)

---

### Task 3.3: Create Bulk Permission Prompt Styles
**File**: `src/styles/bulk-permission-prompt.css` (NEW FILE)

```css
.bulk-permission-prompt {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--bg-primary);
  z-index: 9999;
  display: flex;
  flex-direction: column;
  padding: 2rem;
}

.prompt-header {
  text-align: center;
  margin-bottom: 2rem;
}

.prompt-header h2 {
  font-size: 1.5rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.app-name {
  font-size: 1rem;
  color: var(--text-secondary);
  margin-bottom: 1rem;
}

.description {
  font-size: 0.9rem;
  color: var(--text-secondary);
}

.permissions-list {
  flex: 1;
  overflow-y: auto;
  margin-bottom: 2rem;
}

.permission-item {
  padding: 1rem;
  border-bottom: 1px solid var(--border-color);
}

.permission-item label {
  display: flex;
  align-items: center;
  gap: 1rem;
  cursor: pointer;
}

.permission-item input[type="checkbox"] {
  width: 1.25rem;
  height: 1.25rem;
}

.permission-label {
  font-size: 1rem;
  flex: 1;
}

.prompt-actions {
  display: flex;
  gap: 1rem;
  justify-content: center;
}

.prompt-actions button {
  padding: 0.75rem 1.5rem;
  font-size: 1rem;
  border: none;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-deny {
  background: var(--color-danger);
  color: white;
}

.btn-approve-selected {
  background: var(--color-primary);
  color: white;
}

.btn-approve-selected:disabled {
  background: var(--bg-secondary);
  color: var(--text-disabled);
  cursor: not-allowed;
}

.btn-approve-all {
  background: var(--color-success);
  color: white;
}
```

**Acceptance Criteria**:
- [ ] Full-screen modal styling
- [ ] Clean, readable layout
- [ ] Responsive design
- [ ] Disabled state for approve selected button

---

### Task 3.4: Wire Up Bulk Permission Flow
**File**: `android/app/src/main/kotlin/com/frostr/igloo/MainActivity.kt`

**Changes**:
- Detect bulk permission approval required flag
- Forward permissions to PWA
- Show bulk permission prompt
- Handle approval/denial

```kotlin
// In NIP-55 request handling
if (extras.getString("requires_bulk_approval") == "true") {
    val permissionsJson = extras.getString("permissions")

    // Forward to PWA for bulk approval
    webView.evaluateJavascript("""
        window.showBulkPermissionPrompt(
            '${extras.getString("nip55_request_calling_app")}',
            $permissionsJson
        ).then(approved => {
            if (approved) {
                // Return public key
                Android.resolveBulkPermissionApproval('${requestId}', true);
            } else {
                Android.resolveBulkPermissionApproval('${requestId}', false);
            }
        });
    """, null)
}
```

**Acceptance Criteria**:
- [ ] Bulk approval flow triggered correctly
- [ ] Permissions forwarded to PWA
- [ ] Approval/denial handled
- [ ] Public key returned after approval

---

## Phase 4: Permissions UI Component (2-3 hours)

### Task 4.1: Create Permissions Settings Component
**File**: `src/components/settings/permissions.tsx` (NEW FILE)

```typescript
import { useEffect, useState } from 'react'
import { listPermissions, revokePermission, revokeAllForApp, getEventKindLabel } from '@/lib/permissions.js'
import type { PermissionRule } from '@/types/permissions.js'

export function PermissionsSettings() {
  const [permissions, setPermissions] = useState<PermissionRule[]>([])
  const [filter, setFilter] = useState<string>('')

  useEffect(() => {
    loadPermissions()
  }, [])

  const loadPermissions = () => {
    const perms = listPermissions()
    setPermissions(perms)
  }

  const handleRevoke = (perm: PermissionRule) => {
    revokePermission(perm.appId, perm.type, perm.kind)
    loadPermissions()
  }

  const handleRevokeAll = (appId: string) => {
    if (confirm(`Revoke all permissions for ${appId}?`)) {
      revokeAllForApp(appId)
      loadPermissions()
    }
  }

  // Group permissions by app
  const groupedPerms = permissions.reduce((acc, perm) => {
    if (!acc[perm.appId]) {
      acc[perm.appId] = []
    }
    acc[perm.appId].push(perm)
    return acc
  }, {} as Record<string, PermissionRule[]>)

  const filteredApps = Object.keys(groupedPerms).filter(appId =>
    appId.toLowerCase().includes(filter.toLowerCase())
  )

  return (
    <div className="permissions-settings">
      <h2>NIP-55 Permissions</h2>

      <input
        type="text"
        placeholder="Filter by app..."
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        className="filter-input"
      />

      {filteredApps.length === 0 && (
        <p className="no-permissions">No permissions granted yet</p>
      )}

      {filteredApps.map(appId => (
        <div key={appId} className="app-permissions">
          <div className="app-header">
            <h3>{appId}</h3>
            <button onClick={() => handleRevokeAll(appId)} className="revoke-all">
              Revoke All
            </button>
          </div>

          <div className="permissions-list">
            {groupedPerms[appId].map((perm, i) => (
              <div key={i} className="permission-row">
                <div className="permission-info">
                  <span className="permission-type">{perm.type}</span>
                  {perm.kind !== undefined && (
                    <span className="permission-kind">
                      {getEventKindLabel(perm.kind)}
                    </span>
                  )}
                  <span className="permission-timestamp">
                    {new Date(perm.timestamp).toLocaleDateString()}
                  </span>
                </div>
                <button onClick={() => handleRevoke(perm)} className="revoke-btn">
                  Revoke
                </button>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}
```

**Acceptance Criteria**:
- [ ] Lists all permissions grouped by app
- [ ] Shows event kind labels for sign_event permissions
- [ ] Filter by app name works
- [ ] Revoke individual permission works
- [ ] Revoke all for app works
- [ ] Updates list after revocation

---

### Task 4.2: Create Permissions Settings Styles
**File**: `src/styles/permissions.css` (NEW FILE)

```css
.permissions-settings {
  max-width: 800px;
  margin: 0 auto;
  padding: 2rem;
}

.permissions-settings h2 {
  margin-bottom: 1.5rem;
  font-size: 1.75rem;
}

.filter-input {
  width: 100%;
  padding: 0.75rem;
  margin-bottom: 1.5rem;
  font-size: 1rem;
  border: 1px solid var(--border-color);
  border-radius: 0.5rem;
  background: var(--bg-secondary);
}

.no-permissions {
  text-align: center;
  color: var(--text-secondary);
  padding: 2rem;
}

.app-permissions {
  background: var(--bg-secondary);
  border-radius: 0.5rem;
  padding: 1.5rem;
  margin-bottom: 1rem;
}

.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  padding-bottom: 1rem;
  border-bottom: 2px solid var(--border-color);
}

.app-header h3 {
  font-size: 1.25rem;
  font-weight: 600;
}

.revoke-all {
  padding: 0.5rem 1rem;
  background: var(--color-danger);
  color: white;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  font-size: 0.875rem;
}

.permissions-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.permission-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem;
  background: var(--bg-primary);
  border-radius: 0.375rem;
}

.permission-info {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.permission-type {
  font-weight: 600;
  font-size: 1rem;
}

.permission-kind {
  font-size: 0.875rem;
  color: var(--color-primary);
}

.permission-timestamp {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.revoke-btn {
  padding: 0.5rem 1rem;
  background: var(--bg-tertiary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: 0.375rem;
  cursor: pointer;
  font-size: 0.875rem;
}

.revoke-btn:hover {
  background: var(--color-danger);
  color: white;
  border-color: var(--color-danger);
}
```

**Acceptance Criteria**:
- [ ] Clean card-based layout
- [ ] Grouped by app with clear sections
- [ ] Hover states work
- [ ] Responsive design
- [ ] Matches existing app styling

---

### Task 4.3: Add Permissions to Settings Menu
**File**: `src/components/settings/index.tsx`

**Changes**:
- Import PermissionsSettings
- Add to settings view

```typescript
import { PermissionsSettings } from './permissions.js'

export function SettingsView () {
  return (
    <>
      <GroupConfigField       />
      <ShareConfigField       />
      <PeerConfigField        />
      <RelayConfigField       />
      <PermissionsSettings    />  {/* NEW */}
      <ResetStoreField        />
    </>
  )
}
```

**Acceptance Criteria**:
- [ ] Permissions settings appear in settings menu
- [ ] Navigation works
- [ ] No TypeScript errors

---

## Phase 5: Testing & Polish (1-2 hours)

### Task 5.1: End-to-End Testing

**Test Cases**:

1. **Kind-specific Permission**
   - [ ] Grant sign_event permission for kind 22242 only
   - [ ] Verify kind 22242 events auto-sign
   - [ ] Verify other event kinds prompt for permission

2. **Wildcard Permission**
   - [ ] Grant sign_event permission without kind
   - [ ] Verify all event kinds auto-sign

3. **Bulk Permission Flow**
   - [ ] Trigger get_public_key with permissions array from Amethyst
   - [ ] Verify bulk permission prompt appears
   - [ ] Select some permissions and approve
   - [ ] Verify only selected permissions saved
   - [ ] Verify public key returned

4. **Permission Revocation**
   - [ ] Revoke individual permission
   - [ ] Verify prompts appear again
   - [ ] Revoke all for app
   - [ ] Verify all permissions removed

5. **Content Resolver Auto-Signing**
   - [ ] Grant permissions via get_public_key
   - [ ] Test Content Resolver requests auto-approve
   - [ ] Verify kind filtering works

6. **Storage Migration**
   - [ ] Start with old permissions
   - [ ] Load app with new system
   - [ ] Verify old permissions cleared
   - [ ] Verify fresh start

**Acceptance Criteria**:
- [ ] All test cases pass
- [ ] No console errors
- [ ] Performance is acceptable
- [ ] UI is responsive

---

### Task 5.2: Edge Case Testing

**Test Cases**:
- [ ] Invalid permissions JSON - handled gracefully
- [ ] Bulk prompt with 20+ permissions - scrolls correctly
- [ ] Multiple apps with same package name - handled
- [ ] Permission storage corruption - recovers
- [ ] Rapid approve/deny clicks - no race conditions

**Acceptance Criteria**:
- [ ] All edge cases handled
- [ ] Error messages are clear
- [ ] No crashes

---

### Task 5.3: Code Review & Cleanup

**Checklist**:
- [ ] Remove all old permission code
- [ ] Remove debug console.logs (keep important ones)
- [ ] Add JSDoc comments to all public functions
- [ ] Ensure TypeScript strict mode passes
- [ ] No unused imports
- [ ] Consistent code style

**Acceptance Criteria**:
- [ ] Code is clean and readable
- [ ] No tech debt left behind
- [ ] Documentation is complete

---

## Progress Tracking

**Phase 1**: ⬜ Not Started | 🔄 In Progress | ✅ Complete
**Phase 2**: ⬜ Not Started | 🔄 In Progress | ✅ Complete
**Phase 3**: ⬜ Not Started | 🔄 In Progress | ✅ Complete
**Phase 4**: ⬜ Not Started | 🔄 In Progress | ✅ Complete
**Phase 5**: ⬜ Not Started | 🔄 In Progress | ✅ Complete

---

## Estimated Timeline

- **Phase 1**: 1-2 hours
- **Phase 2**: 2-3 hours
- **Phase 3**: 3-4 hours
- **Phase 4**: 2-3 hours
- **Phase 5**: 1-2 hours

**Total**: 9-14 hours

---

## Notes

- This is a clean implementation with no legacy code
- Users will need to re-grant permissions (acceptable in alpha)
- Focus on correctness and clean architecture over backward compatibility
- Test thoroughly with real Amethyst integration
