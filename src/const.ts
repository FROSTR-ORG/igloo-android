/**
 * Application constants
 *
 * Centralized storage keys and configuration values used throughout the PWA.
 * Using constants prevents typos and makes it easy to refactor storage keys.
 */

// Storage keys
export const STORAGE_KEYS = {
  /** Main settings storage key (localStorage) */
  SETTINGS: 'igloo-pwa',

  /** Session password storage key (sessionStorage) */
  SESSION_PASSWORD: 'igloo_session_password',

  /** NIP-55 permissions storage key (localStorage) */
  PERMISSIONS: 'nip55_permissions_v2',
} as const

// Application metadata
export const APP = {
  /** Application name */
  NAME: 'Igloo',

  /** Application version - update on release */
  VERSION: '0.1.0',
} as const

// Console logging
/** Maximum number of log entries to retain */
export const LOG_LIMIT = 100
