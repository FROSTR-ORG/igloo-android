/**
 * NIP-55 Permission System Type Definitions
 *
 * Clean implementation with event kind filtering support.
 * No legacy compatibility code.
 */

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

/**
 * Legacy type alias for compatibility
 * @deprecated Use PermissionRule instead
 */
export type Permission = PermissionRule

/**
 * Permission management API
 */
export interface PermissionAPI {
  has_permission        : (host: string, type: string, kind?: number) => Promise<boolean>
  set_permission        : (host: string, type: string, allowed: boolean, kind?: number) => Promise<void>
  revoke_permission     : (host: string, type: string, kind?: number) => Promise<void>
  list_permissions      : (host?: string) => Promise<Permission[]>
  bulk_set_permissions  : (rules: Permission[]) => Promise<void>
  revoke_all_for_app    : (host: string) => Promise<void>
}
