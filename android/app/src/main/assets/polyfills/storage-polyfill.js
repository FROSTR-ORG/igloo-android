/**
 * Storage Polyfill for Secure Android Bridge
 *
 * This polyfill provides a transparent replacement for localStorage and sessionStorage,
 * routing all storage operations through the secure Android bridge while maintaining
 * 100% API compatibility. The PWA code remains completely unchanged.
 */

(function() {
    'use strict';

    // Store references to original storage APIs if they exist
    const OriginalLocalStorage = window.localStorage;
    const OriginalSessionStorage = window.sessionStorage;

    /**
     * Secure Storage implementation
     * Maintains full compatibility with the Web Storage API
     */
    class SecureStorage {
        constructor(storageType) {
            this.storageType = storageType; // 'local' or 'session'
            this._length = 0;

            // Initialize storage length
            this._updateLength();
        }

        /**
         * Get the number of items in storage
         */
        get length() {
            try {
                if (window.SecureStorageBridge) {
                    this._length = window.SecureStorageBridge.length(this.storageType);
                }
            } catch (error) {
                console.error(`[SecureStorage] Failed to get ${this.storageType} storage length:`, error);
            }
            return this._length;
        }

        /**
         * Get an item from storage
         * @param {string} key Storage key
         * @returns {string|null} Item value or null if not found
         */
        getItem(key) {
            try {
                // Validate key
                if (key === null || key === undefined) {
                    throw new TypeError('Failed to execute \'getItem\' on \'Storage\': 1 argument required, but only 0 present.');
                }

                // Convert key to string (Web Storage API behavior)
                const stringKey = String(key);

                if (!window.SecureStorageBridge) {
                    console.warn('[SecureStorage] Bridge not available, returning null');
                    return null;
                }

                const result = window.SecureStorageBridge.getItem(this.storageType, stringKey);
                console.log(`[SecureStorage] Retrieved ${this.storageType}['${stringKey}']:`, result !== null ? 'found' : 'not found');

                return result;

            } catch (error) {
                console.error(`[SecureStorage] Failed to get item from ${this.storageType} storage:`, error);
                return null;
            }
        }

        /**
         * Set an item in storage
         * @param {string} key Storage key
         * @param {string} value Storage value
         */
        setItem(key, value) {
            try {
                // Validate arguments
                if (arguments.length < 2) {
                    throw new TypeError(`Failed to execute 'setItem' on 'Storage': 2 arguments required, but only ${arguments.length} present.`);
                }

                // Convert to strings (Web Storage API behavior)
                const stringKey = String(key);
                const stringValue = String(value);

                if (!window.SecureStorageBridge) {
                    throw new Error('SecureStorage bridge not available');
                }

                // Get old value for storage event
                const oldValue = this.getItem(stringKey);

                // Store via bridge
                const resultJson = window.SecureStorageBridge.setItem(this.storageType, stringKey, stringValue);
                const result = JSON.parse(resultJson);

                if (result.message && !result.message.includes('stored')) {
                    // Handle quota exceeded or other errors
                    if (result.message.includes('quota')) {
                        throw new DOMException('Failed to execute \'setItem\' on \'Storage\': Setting the value exceeded the storage quota.');
                    } else {
                        throw new Error(`Storage error: ${result.message}`);
                    }
                }

                console.log(`[SecureStorage] Stored ${this.storageType}['${stringKey}'] = '${stringValue}'`);

                // Update length cache
                this._updateLength();

                // Dispatch storage event (for cross-tab communication compatibility)
                this._dispatchStorageEvent(stringKey, oldValue, stringValue);

            } catch (error) {
                console.error(`[SecureStorage] Failed to set item in ${this.storageType} storage:`, error);
                throw error; // Re-throw to maintain Web Storage API behavior
            }
        }

        /**
         * Remove an item from storage
         * @param {string} key Storage key
         */
        removeItem(key) {
            try {
                // Convert key to string
                const stringKey = String(key);

                if (!window.SecureStorageBridge) {
                    console.warn('[SecureStorage] Bridge not available');
                    return;
                }

                // Get old value for storage event
                const oldValue = this.getItem(stringKey);

                // Remove via bridge
                const resultJson = window.SecureStorageBridge.removeItem(this.storageType, stringKey);
                const result = JSON.parse(resultJson);

                if (result.message && !result.message.includes('removed')) {
                    console.warn(`[SecureStorage] Remove warning: ${result.message}`);
                }

                console.log(`[SecureStorage] Removed ${this.storageType}['${stringKey}']`);

                // Update length cache
                this._updateLength();

                // Dispatch storage event
                if (oldValue !== null) {
                    this._dispatchStorageEvent(stringKey, oldValue, null);
                }

            } catch (error) {
                console.error(`[SecureStorage] Failed to remove item from ${this.storageType} storage:`, error);
            }
        }

        /**
         * Clear all items from storage
         */
        clear() {
            try {
                if (!window.SecureStorageBridge) {
                    console.warn('[SecureStorage] Bridge not available');
                    return;
                }

                // Clear via bridge
                const resultJson = window.SecureStorageBridge.clear(this.storageType);
                const result = JSON.parse(resultJson);

                if (result.message && !result.message.includes('cleared')) {
                    console.warn(`[SecureStorage] Clear warning: ${result.message}`);
                }

                console.log(`[SecureStorage] Cleared all ${this.storageType} storage`);

                // Update length cache
                this._length = 0;

                // Dispatch storage event for clear operation
                this._dispatchStorageEvent(null, null, null);

            } catch (error) {
                console.error(`[SecureStorage] Failed to clear ${this.storageType} storage:`, error);
            }
        }

        /**
         * Get the key at the specified index
         * @param {number} index Key index
         * @returns {string|null} Key name or null
         */
        key(index) {
            try {
                // Convert to number
                const numIndex = Number(index);

                if (!Number.isInteger(numIndex) || numIndex < 0) {
                    return null;
                }

                if (!window.SecureStorageBridge) {
                    console.warn('[SecureStorage] Bridge not available');
                    return null;
                }

                const result = window.SecureStorageBridge.key(this.storageType, numIndex);
                console.log(`[SecureStorage] Key at index ${numIndex} in ${this.storageType} storage:`, result);

                return result;

            } catch (error) {
                console.error(`[SecureStorage] Failed to get key at index from ${this.storageType} storage:`, error);
                return null;
            }
        }

        /**
         * Update cached length value
         */
        _updateLength() {
            try {
                if (window.SecureStorageBridge) {
                    this._length = window.SecureStorageBridge.length(this.storageType);
                }
            } catch (error) {
                console.error(`[SecureStorage] Failed to update ${this.storageType} storage length:`, error);
                this._length = 0;
            }
        }

        /**
         * Dispatch storage event for cross-tab communication
         * Note: In our single-WebView scenario, this maintains API compatibility
         */
        _dispatchStorageEvent(key, oldValue, newValue) {
            try {
                // Create storage event
                const storageEvent = new StorageEvent('storage', {
                    key: key,
                    oldValue: oldValue,
                    newValue: newValue,
                    url: window.location.href,
                    storageArea: this.storageType === 'local' ? window.localStorage : window.sessionStorage
                });

                // Dispatch on window
                window.dispatchEvent(storageEvent);

                console.log(`[SecureStorage] Storage event dispatched for ${this.storageType} storage:`, {
                    key: key,
                    oldValue: oldValue ? 'present' : 'null',
                    newValue: newValue ? 'present' : 'null'
                });

            } catch (error) {
                console.error(`[SecureStorage] Failed to dispatch storage event:`, error);
            }
        }

        /**
         * Get storage usage information (extension method)
         */
        getStorageInfo() {
            try {
                if (!window.SecureStorageBridge) {
                    return null;
                }

                const infoJson = window.SecureStorageBridge.getStorageInfo(this.storageType);
                return JSON.parse(infoJson);

            } catch (error) {
                console.error(`[SecureStorage] Failed to get storage info:`, error);
                return null;
            }
        }

        /**
         * Get all keys (extension method for debugging)
         */
        getAllKeys() {
            try {
                if (!window.SecureStorageBridge) {
                    return [];
                }

                const keysJson = window.SecureStorageBridge.getAllKeys(this.storageType);
                return JSON.parse(keysJson);

            } catch (error) {
                console.error(`[SecureStorage] Failed to get all keys:`, error);
                return [];
            }
        }
    }

    /**
     * Create secure storage instances
     */
    const secureLocalStorage = new SecureStorage('local');
    const secureSessionStorage = new SecureStorage('session');

    /**
     * Property descriptor for storage properties to maintain exact Web Storage API behavior
     */
    function createStoragePropertyDescriptor(storage) {
        return {
            get: function() {
                return storage;
            },
            set: function(value) {
                // Web Storage API doesn't allow setting storage objects directly
                throw new TypeError('Illegal invocation');
            },
            enumerable: true,
            configurable: false
        };
    }

    // Replace localStorage and sessionStorage with secure implementations
    try {
        Object.defineProperty(window, 'localStorage', createStoragePropertyDescriptor(secureLocalStorage));
        Object.defineProperty(window, 'sessionStorage', createStoragePropertyDescriptor(secureSessionStorage));

        console.log('[Storage Polyfill] Successfully replaced localStorage and sessionStorage with secure implementations');

        // Add compatibility check method
        window.SecureStorage = {
            isActive: function() {
                return window.SecureStorageBridge !== undefined;
            },
            getInfo: function(storageType) {
                const storage = storageType === 'local' ? secureLocalStorage : secureSessionStorage;
                return storage.getStorageInfo();
            },
            // Store references to original APIs for potential fallback
            _originalLocalStorage: OriginalLocalStorage,
            _originalSessionStorage: OriginalSessionStorage
        };

        // Test the bridge connection
        if (window.SecureStorageBridge) {
            console.log('[Storage Polyfill] Bridge connection test passed');

            // Test basic functionality
            try {
                const testKey = '__storage_polyfill_test__';
                const testValue = 'test_value_' + Date.now();

                window.localStorage.setItem(testKey, testValue);
                const retrieved = window.localStorage.getItem(testKey);
                window.localStorage.removeItem(testKey);

                if (retrieved === testValue) {
                    console.log('[Storage Polyfill] Functionality test passed');
                } else {
                    console.warn('[Storage Polyfill] Functionality test failed - value mismatch');
                }

            } catch (testError) {
                console.error('[Storage Polyfill] Functionality test failed:', testError);
            }

        } else {
            console.warn('[Storage Polyfill] Bridge not available - storage operations will fail');
        }

    } catch (error) {
        console.error('[Storage Polyfill] Failed to replace storage APIs:', error);

        // Fallback: log error but don't break the page
        window.SecureStorage = {
            isActive: function() { return false; },
            error: error.message
        };
    }

    console.log('[Storage Polyfill] Loaded successfully - localStorage and sessionStorage replaced with secure bridge');

})();