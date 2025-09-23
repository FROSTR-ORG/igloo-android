import { useEffect } from 'react'
import { usePrompt } from '@/context/prompt.js'
import { useSettings } from '@/context/settings.js'
import { checkForNIP55Request, registerProtocolHandler, parseNIP55URL } from '@/lib/nip55.js'

import type { NIP55Request, PermActionRecord, PermEventRecord } from '@/types.js'

/**
 * Hook to handle NIP-55 requests automatically
 */
export function useNIP55Handler() {
  const prompt = usePrompt()
  const settings = useSettings()

  // Check if permission already exists for this request
  const hasExistingPermission = (request: NIP55Request) => {
    const permissions = settings.data.perms
    for (const policy of permissions) {
      if (request.type === 'sign_event') {
        const eventRecord = policy.event.find((e: PermEventRecord) =>
          e.host === request.host && e.kind === request.event?.kind
        )
        if (eventRecord) return eventRecord
      } else {
        const actionRecord = policy.action.find((a: PermActionRecord) =>
          a.host === request.host && a.action === request.type
        )
        if (actionRecord) return actionRecord
      }
    }
    return null
  }

  // Handle auto-approval/denial based on saved permissions
  const handleAutomaticPermission = async (request: NIP55Request, permission: PermActionRecord | PermEventRecord) => {
    try {
      if (permission.accept) {
        // Auto-approve
        console.log('Auto-approving request based on saved permission')
        prompt.showPrompt(request)
        await prompt.approve(false) // Don't re-save the permission
      } else {
        // Auto-deny
        console.log('Auto-denying request based on saved permission')
        prompt.showPrompt(request)
        await prompt.deny(false) // Don't re-save the permission
      }
    } catch (error) {
      console.error('Failed to handle automatic permission:', error)
      // Fall back to showing the prompt
      prompt.showPrompt(request)
    }
  }

  // Handle NIP-55 request from external sources (e.g., Android JavaScript injection)
  const handleDirectNIP55Request = (nip55Url: string) => {
    console.log('handleDirectNIP55Request called with:', nip55Url)

    const request = parseNIP55URL(nip55Url)
    if (request) {
      console.log('Parsed direct NIP-55 request:', request)

      // Check if we have an existing permission
      const existingPermission = hasExistingPermission(request)
      if (existingPermission) {
        // Handle automatically based on saved permission
        handleAutomaticPermission(request, existingPermission)
      } else {
        // Show prompt for new request
        console.log('Showing prompt for direct request')
        prompt.showPrompt(request)
      }
    } else {
      console.error('Failed to parse direct NIP-55 URL:', nip55Url)
    }
  }

  useEffect(() => {
    // Register protocol handler on first load
    registerProtocolHandler()

    // Expose global function for Android JavaScript injection
    ;(window as any).handleNIP55Request = handleDirectNIP55Request
    ;(window as any).showPrompt = prompt.showPrompt
    console.log('Global NIP-55 handlers exposed for Android')

    // Check for NIP-55 request in current URL
    const request = checkForNIP55Request()
    if (request) {
      console.log('NIP-55 request detected:', request)

      // Check if we have an existing permission
      const existingPermission = hasExistingPermission(request)
      if (existingPermission) {
        // Handle automatically based on saved permission
        handleAutomaticPermission(request, existingPermission)
      } else {
        // Show prompt for new request
        console.log('Showing prompt for new request')
        console.log('Request details:', request)

        // Visual debugging for prompt showing
        const debugDiv = document.createElement('div')
        debugDiv.style.cssText = 'position:fixed;top:60px;left:0;background:blue;color:white;padding:10px;z-index:9999;font-size:12px;'
        debugDiv.textContent = `PROMPT: Calling showPrompt for ${request.type}`
        document.body.appendChild(debugDiv)
        setTimeout(() => debugDiv.remove(), 5000)

        console.log('About to call prompt.showPrompt...')
        prompt.showPrompt(request)
        console.log('Called prompt.showPrompt')
      }

      // Clean up URL after processing (optional)
      const url = new URL(window.location.href)
      url.searchParams.delete('nip55')
      if (url.hash.startsWith('#nostrsigner:') || url.hash.startsWith('#web+nostrsigner:')) {
        url.hash = ''
      }
      if (url.toString() !== window.location.href) {
        window.history.replaceState({}, '', url.toString())
      }
    }

    // Note: Service worker message handling removed to prevent infinite loops
    // All NIP-55 handling is now done directly via URL detection

    // Cleanup global functions on unmount
    return () => {
      delete (window as any).handleNIP55Request
      delete (window as any).showPrompt
      console.log('Global NIP-55 handlers cleaned up')
    }
  }, []) // Empty dependency array - only run on mount

  // Listen for URL changes (for SPAs)
  useEffect(() => {
    const handlePopState = () => {
      const request = checkForNIP55Request()
      if (request) {
        const existingPermission = hasExistingPermission(request)
        if (existingPermission) {
          handleAutomaticPermission(request, existingPermission)
        } else {
          prompt.showPrompt(request)
        }
      }
    }

    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [settings.data.perms]) // Re-check when permissions change

  // Listen for focus events (when app regains focus, like from external link)
  useEffect(() => {
    const handleFocus = () => {
      // Small delay to ensure URL is updated
      setTimeout(() => {
        const request = checkForNIP55Request()
        if (request && !prompt.state.isOpen) {
          const existingPermission = hasExistingPermission(request)
          if (existingPermission) {
            handleAutomaticPermission(request, existingPermission)
          } else {
            prompt.showPrompt(request)
          }
        }
      }, 100)
    }

    window.addEventListener('focus', handleFocus)
    return () => window.removeEventListener('focus', handleFocus)
  }, [prompt.state.isOpen, settings.data.perms])
}