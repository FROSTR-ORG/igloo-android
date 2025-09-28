import {
  createContext,
  useContext
} from 'react'

import type { ReactElement } from 'react'

import type {
  ProviderProps
}  from '@/types/index.js'

import type {
  Permission,
  PermissionAPI
} from '@/types/prompt.js'

const context = createContext<PermissionAPI | null>(null)

export const PermissionsProvider = ({ children }: ProviderProps): ReactElement => {

  // Helper to get nip55_permissions from storage
  const get_nip55_permissions = async () => {
    try {
      // Use localStorage directly (it's securely polyfilled by the Android bridge)
      const stored = localStorage.getItem('nip55_permissions')
      return stored ? JSON.parse(stored) : []
    } catch (error) {
      console.error('Failed to load nip55_permissions:', error)
      return []
    }
  }

  // Helper to save nip55_permissions to storage
  const save_nip55_permissions = async (permissions: any[]) => {
    try {
      const json = JSON.stringify(permissions)
      // Use localStorage directly (it's securely polyfilled by the Android bridge)
      localStorage.setItem('nip55_permissions', json)
    } catch (error) {
      console.error('Failed to save nip55_permissions:', error)
    }
  }

  const has_permission = async (host: string, type: string): Promise<boolean> => {
    const nip55Perms = await get_nip55_permissions()
    const permission = nip55Perms.find((p: any) =>
      p.appId === host && p.type === type && p.allowed === true
    )
    return permission !== undefined
  }

  const set_permission = async (host: string, type: string, allowed: boolean = true): Promise<void> => {
    const nip55Perms = await get_nip55_permissions()
    const existing_index = nip55Perms.findIndex((p: any) => p.appId === host && p.type === type)

    const permission = {
      appId: host,
      type: type,
      allowed: allowed,
      timestamp: Date.now()
    }

    if (existing_index >= 0) {
      nip55Perms[existing_index] = permission
    } else {
      nip55Perms.push(permission)
    }

    await save_nip55_permissions(nip55Perms)
  }

  const revoke_permission = async (host: string, type: string): Promise<void> => {
    const nip55Perms = await get_nip55_permissions()
    const updated_permissions = nip55Perms.filter((p: any) => !(p.appId === host && p.type === type))
    await save_nip55_permissions(updated_permissions)
  }

  const list_permissions = async (): Promise<Permission[]> => {
    try {
      const permissions = await get_nip55_permissions()
      if (!Array.isArray(permissions)) {
        return []
      }
      // Return permissions in unified format directly - no conversion needed
      return permissions
    } catch (error) {
      console.error('Failed to load permissions:', error)
      return []
    }
  }

  const api: PermissionAPI = {
    has_permission,
    set_permission,
    revoke_permission,
    list_permissions
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