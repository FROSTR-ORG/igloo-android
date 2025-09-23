/// <reference lib="webworker" />

import { create_logger } from './util/logger.js'

// Declare the service worker global scope
declare const self : ServiceWorkerGlobalScope

const log = create_logger('sw')

// Register service worker
self.addEventListener('install', async (_event) => {
  log.info('installing ...')
  // Skip waiting for the service worker to become active.
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  // Log the activate event.
  console.log('[ sw ] activating ...')
  // Skip waiting for the service worker to become active.
  self.skipWaiting()
})

self.addEventListener('message', (event) => {
  log.info('received message:', event)
})

// Fetch event handler - required for PWA installability
self.addEventListener('fetch', (event) => {
  // Let browser handle all fetch requests normally
  // This handler exists to meet PWA installability requirements
  event.respondWith(fetch(event.request))
})

// Note: NIP-55 handling is now done entirely in the main app
// Service worker NIP-55 handling was causing infinite loops and permission issues
// The main app useNIP55Handler hook handles all NIP-55 requests directly
