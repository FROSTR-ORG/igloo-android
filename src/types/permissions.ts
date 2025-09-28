/**
 * Simple Permission Types
 *
 * Basic types for the simplified localStorage-based permission system
 */

// Simple permission rule matching the implementation in lib/permissions.ts
export interface SimplePermissionRule {
  appId: string
  type: string
  allowed: boolean
  timestamp: number
}
