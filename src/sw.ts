/// <reference lib="webworker" />

import { create_logger } from './util/logger.js'

// Declare the service worker global scope
declare const self : ServiceWorkerGlobalScope

const log = create_logger('sw')

// Register service worker
self.addEventListener('install', async (_event) => {
  log.info('installing ...')
  log.info('🟢 Service Worker installed at:', new Date().toISOString())
  // Skip waiting for the service worker to become active.
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  // Log the activate event.
  console.log('[ sw ] activating ...')
  log.info('🔄 Service Worker activated at:', new Date().toISOString())
  // Skip waiting for the service worker to become active.
  self.skipWaiting()
})

self.addEventListener('message', (event) => {
  log.info('received message:', event)

  if (event.data?.type === 'PERSISTENCE_TEST') {
    log.info('🧪 Service Worker persistence test received at:', new Date().toISOString())
    // Send response back to main thread
    event.ports[0]?.postMessage({
      type: 'PERSISTENCE_RESPONSE',
      timestamp: new Date().toISOString(),
      message: 'Service Worker is alive'
    })
  }
})

// Periodic heartbeat to test persistence
setInterval(() => {
  log.info('💓 Service Worker heartbeat:', new Date().toISOString())
}, 10000) // Every 10 seconds

// Fetch event handler - required for PWA installability
self.addEventListener('fetch', (event) => {
  // Let browser handle all fetch requests normally
  // This handler exists to meet PWA installability requirements
  event.respondWith(fetch(event.request))
})

// Note: NIP-55 handling is now done entirely in the main app
// Service worker NIP-55 handling was causing infinite loops and permission issues
// The main app useNIP55Handler hook handles all NIP-55 requests directly
