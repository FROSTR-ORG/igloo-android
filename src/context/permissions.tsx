import {
  createContext,
  useContext,
  useEffect
} from 'react'

import type { ReactElement } from 'react'

import type {
  ProviderProps
}  from '@/types/index.js'

import type {
  Permission,
  NIP55OperationType,
  PermissionRule
} from '@/types/permissions.js'

import type {
  PermissionAPI
} from '@/types/prompt.js'

import {
  initPermissionStorage,
  checkPermission,
  setPermission,
  revokePermission,
  revokeAllForApp,
  listPermissions,
  bulkSetPermissions
} from '@/lib/permissions.js'

const context = createContext<PermissionAPI | null>(null)

export const PermissionsProvider = ({ children }: ProviderProps): ReactElement => {

  // Initialize permission storage on mount
  useEffect(() => {
    initPermissionStorage()
  }, [])

  const has_permission = async (host: string, type: string, kind?: number): Promise<boolean> => {
    const status = checkPermission(host, type as NIP55OperationType, kind)
    return status === 'allowed'
  }

  const set_permission = async (host: string, type: string, allowed: boolean, kind?: number): Promise<void> => {
    setPermission(host, type as NIP55OperationType, allowed, kind)
  }

  const revoke_permission = async (host: string, type: string, kind?: number): Promise<void> => {
    revokePermission(host, type as NIP55OperationType, kind)
  }

  const list_permissions_impl = async (host?: string): Promise<Permission[]> => {
    const perms = listPermissions(host)
    // Convert PermissionRule to Permission (compatible types)
    return perms as Permission[]
  }

  const bulk_set_permissions = async (rules: Permission[]): Promise<void> => {
    // Convert Permission[] to the format bulkSetPermissions expects
    if (rules.length === 0) return

    // Group by appId and allowed status for efficient bulk operations
    const grouped = rules.reduce((acc, rule) => {
      const key = `${rule.appId}:${rule.allowed}`
      if (!acc[key]) {
        acc[key] = { appId: rule.appId, allowed: rule.allowed, perms: [] }
      }
      acc[key].perms.push({ type: rule.type as NIP55OperationType, kind: rule.kind })
      return acc
    }, {} as Record<string, { appId: string; allowed: boolean; perms: Array<{ type: NIP55OperationType; kind?: number }> }>)

    // Execute bulk operations for each group
    for (const group of Object.values(grouped)) {
      bulkSetPermissions(group.appId, group.perms, group.allowed)
    }
  }

  const revoke_all_for_app = async (host: string): Promise<void> => {
    revokeAllForApp(host)
  }

  const api: PermissionAPI = {
    has_permission,
    set_permission,
    revoke_permission,
    list_permissions: list_permissions_impl,
    bulk_set_permissions,
    revoke_all_for_app
  }

  return (
    <context.Provider value={api}>
      {children}
    </context.Provider>
  )
}

export const usePermissions = (): PermissionAPI => {
  const ctx = useContext(context)
  if (ctx === null) {
    throw new Error('usePermissions must be used within a PermissionsProvider')
  }
  return ctx
}
