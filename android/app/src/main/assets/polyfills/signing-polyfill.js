/**
 * Signing Polyfill for Android WebView
 *
 * Provides secure Nostr signing capabilities through the Android UnifiedSigningBridge.
 * This polyfill creates a seamless interface for the PWA to access cryptographic
 * operations without needing to know about the underlying Android implementation.
 */

(function() {
    'use strict';

    console.log('[SigningPolyfill] Initializing secure signing interface');

    // Check if we're running in Android WebView with signing bridge
    if (typeof window.UnifiedSigningBridge === 'undefined') {
        console.log('[SigningPolyfill] UnifiedSigningBridge not available, signing disabled');
        return;
    }

    // Callback management
    let callbackCounter = 0;
    const pendingCallbacks = new Map();

    /**
     * Generate unique callback ID
     */
    function generateCallbackId() {
        return `signing_callback_${Date.now()}_${++callbackCounter}`;
    }

    /**
     * Promise-based wrapper for bridge calls
     */
    function callBridge(method, ...args) {
        return new Promise((resolve, reject) => {
            const callbackId = generateCallbackId();

            // Store callback handlers
            pendingCallbacks.set(callbackId, { resolve, reject });

            // Set timeout for callback
            setTimeout(() => {
                if (pendingCallbacks.has(callbackId)) {
                    pendingCallbacks.delete(callbackId);
                    reject(new Error('Signing request timeout'));
                }
            }, 30000); // 30 second timeout

            try {
                // Call the bridge method with callback ID
                const result = window.UnifiedSigningBridge[method](...args, callbackId);

                // Parse immediate response
                const response = JSON.parse(result);
                if (response.error) {
                    pendingCallbacks.delete(callbackId);
                    reject(new Error(response.error));
                }
                // Success responses will come via callback

            } catch (error) {
                pendingCallbacks.delete(callbackId);
                reject(error);
            }
        });
    }

    /**
     * Handle callback from Android bridge
     */
    function handleCallback(callbackId, result) {
        console.log('[SigningPolyfill] Received callback:', callbackId);

        const callback = pendingCallbacks.get(callbackId);
        if (!callback) {
            console.warn('[SigningPolyfill] Unknown callback ID:', callbackId);
            return;
        }

        pendingCallbacks.delete(callbackId);

        if (result.error) {
            callback.reject(new Error(result.error));
        } else {
            callback.resolve(result.signature || result.data);
        }
    }

    /**
     * Handle user prompt required notification
     */
    function onUserPromptRequired(request) {
        console.log('[SigningPolyfill] User prompt required for request:', request.id);

        // Dispatch custom event that the PWA can listen to
        const event = new CustomEvent('nostr:signing:prompt', {
            detail: {
                requestId: request.id,
                type: request.type,
                callingApp: request.callingApp,
                payload: request.payload,
                metadata: request.metadata
            }
        });

        window.dispatchEvent(event);
    }

    /**
     * Handle signing completion notification
     */
    function onSigningComplete(requestId, result) {
        console.log('[SigningPolyfill] Signing complete for request:', requestId);

        // Dispatch custom event
        const event = new CustomEvent('nostr:signing:complete', {
            detail: {
                requestId: requestId,
                result: result
            }
        });

        window.dispatchEvent(event);
    }

    // Expose callback handlers to Android bridge
    window.SigningBridge = {
        handleCallback: handleCallback,
        onUserPromptRequired: onUserPromptRequired,
        onSigningComplete: onSigningComplete
    };

    /**
     * Main Nostr signing interface
     */
    const NostrSigner = {
        /**
         * Sign a Nostr event
         */
        async signEvent(event) {
            console.log('[SigningPolyfill] Signing event:', event.kind);

            try {
                const eventJson = JSON.stringify(event);
                const signature = await callBridge('signEvent', eventJson);

                return {
                    ...event,
                    sig: signature
                };

            } catch (error) {
                console.error('[SigningPolyfill] Event signing failed:', error);
                throw error;
            }
        },

        /**
         * Get public key
         */
        async getPublicKey() {
            console.log('[SigningPolyfill] Getting public key');

            try {
                const pubkey = await callBridge('getPublicKey');
                return pubkey;

            } catch (error) {
                console.error('[SigningPolyfill] Failed to get public key:', error);
                throw error;
            }
        },

        /**
         * NIP-04 encryption
         */
        async nip04Encrypt(plaintext, recipientPubkey) {
            console.log('[SigningPolyfill] NIP-04 encrypting message');

            try {
                const ciphertext = await callBridge('nip04Encrypt', plaintext, recipientPubkey);
                return ciphertext;

            } catch (error) {
                console.error('[SigningPolyfill] NIP-04 encryption failed:', error);
                throw error;
            }
        },

        /**
         * NIP-04 decryption
         */
        async nip04Decrypt(ciphertext, senderPubkey) {
            console.log('[SigningPolyfill] NIP-04 decrypting message');

            try {
                const plaintext = await callBridge('nip04Decrypt', ciphertext, senderPubkey);
                return plaintext;

            } catch (error) {
                console.error('[SigningPolyfill] NIP-04 decryption failed:', error);
                throw error;
            }
        },

        /**
         * Approve a pending signing request
         */
        async approveRequest(requestId, signature = null) {
            try {
                const result = window.UnifiedSigningBridge.approveSigningRequest(
                    requestId, true, signature
                );
                return JSON.parse(result);

            } catch (error) {
                console.error('[SigningPolyfill] Failed to approve request:', error);
                throw error;
            }
        },

        /**
         * Deny a pending signing request
         */
        async denyRequest(requestId) {
            try {
                const result = window.UnifiedSigningBridge.approveSigningRequest(
                    requestId, false, null
                );
                return JSON.parse(result);

            } catch (error) {
                console.error('[SigningPolyfill] Failed to deny request:', error);
                throw error;
            }
        },

        /**
         * Get pending signing requests
         */
        async getPendingRequests() {
            try {
                const result = window.UnifiedSigningBridge.getPendingRequests();
                return JSON.parse(result);

            } catch (error) {
                console.error('[SigningPolyfill] Failed to get pending requests:', error);
                throw error;
            }
        },

        /**
         * Cancel a signing request
         */
        async cancelRequest(requestId) {
            try {
                const result = window.UnifiedSigningBridge.cancelSigningRequest(requestId);
                return JSON.parse(result);

            } catch (error) {
                console.error('[SigningPolyfill] Failed to cancel request:', error);
                throw error;
            }
        }
    };

    /**
     * Permission management interface
     */
    const NostrPermissions = {
        /**
         * Get permissions for an app
         */
        async getAppPermissions(appId) {
            try {
                const result = window.UnifiedSigningBridge.getAppPermissions(appId);
                return JSON.parse(result);

            } catch (error) {
                console.error('[SigningPolyfill] Failed to get app permissions:', error);
                throw error;
            }
        },

        /**
         * Update permission for an app
         */
        async updateAppPermission(appId, operationType, permission) {
            try {
                const result = window.UnifiedSigningBridge.updateAppPermission(
                    appId, operationType, permission
                );
                return JSON.parse(result);

            } catch (error) {
                console.error('[SigningPolyfill] Failed to update permission:', error);
                throw error;
            }
        }
    };

    // Expose signing interface globally
    Object.defineProperty(window, 'nostr', {
        value: NostrSigner,
        writable: false,
        configurable: false,
        enumerable: true
    });

    // Expose permissions interface
    Object.defineProperty(window, 'nostrPermissions', {
        value: NostrPermissions,
        writable: false,
        configurable: false,
        enumerable: true
    });

    // Also expose as window.android.nostr for compatibility
    if (!window.android) {
        window.android = {};
    }

    window.android.nostr = NostrSigner;
    window.android.nostrPermissions = NostrPermissions;

    // NIP-07 compatibility interface (standard window.nostr interface)
    const nip07Interface = {
        async getPublicKey() {
            return NostrSigner.getPublicKey();
        },

        async signEvent(event) {
            return NostrSigner.signEvent(event);
        },

        async getRelays() {
            // Could be enhanced to return configured relays from storage
            return {};
        },

        async nip04: {
            async encrypt(pubkey, plaintext) {
                return NostrSigner.nip04Encrypt(plaintext, pubkey);
            },

            async decrypt(pubkey, ciphertext) {
                return NostrSigner.nip04Decrypt(ciphertext, pubkey);
            }
        }
    };

    // Replace any existing window.nostr with our secure implementation
    if (window.nostr) {
        console.log('[SigningPolyfill] Replacing existing window.nostr interface');
    }

    Object.defineProperty(window, 'nostr', {
        value: {
            ...nip07Interface,
            ...NostrSigner,  // Include additional methods
            nip04: nip07Interface.nip04
        },
        writable: false,
        configurable: true,
        enumerable: true
    });

    // Dispatch ready event
    window.dispatchEvent(new CustomEvent('nostr:ready', {
        detail: {
            signer: 'android-secure',
            capabilities: [
                'sign_event',
                'get_public_key',
                'nip04_encrypt',
                'nip04_decrypt',
                'permission_management',
                'request_queuing'
            ]
        }
    }));

    console.log('[SigningPolyfill] Secure Nostr signing interface ready');

})();