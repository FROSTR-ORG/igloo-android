// Storage polyfill - Direct Android SecureStorageBridge integration
(function() {
    console.log('[Polyfill] Initializing Storage API...');

    // Delete existing storage objects for clean replacement
    try {
        delete window.localStorage;
        delete window.sessionStorage;
    } catch (e) {
        // Expected - properties may not be configurable
    }

    // Create localStorage implementation
    var localStorageImpl = {
        getItem: function(key) {
            try {
                // Bridge returns raw string value or null
                var value = window.StorageBridge.getItem('local', key);
                return value; // Returns string or null directly
            } catch (e) {
                console.error('[Polyfill] localStorage.getItem error:', e);
                return null;
            }
        },
        setItem: function(key, value) {
            try {
                // Bridge expects (storageType, key, value)
                window.StorageBridge.setItem('local', key, String(value));
            } catch (e) {
                console.error('[Polyfill] localStorage.setItem error:', e);
                throw e;
            }
        },
        removeItem: function(key) {
            try {
                window.StorageBridge.removeItem('local', key);
            } catch (e) {
                console.error('[Polyfill] localStorage.removeItem error:', e);
            }
        },
        clear: function() {
            try {
                window.StorageBridge.clear('local');
            } catch (e) {
                console.error('[Polyfill] localStorage.clear error:', e);
            }
        },
        key: function(index) {
            try {
                return window.StorageBridge.key('local', index);
            } catch (e) {
                console.error('[Polyfill] localStorage.key error:', e);
                return null;
            }
        },
        get length() {
            try {
                return window.StorageBridge.length('local') || 0;
            } catch (e) {
                console.error('[Polyfill] localStorage.length error:', e);
                return 0;
            }
        }
    };

    // Create sessionStorage implementation
    var sessionStorageImpl = {
        getItem: function(key) {
            try {
                var value = window.StorageBridge.getItem('session', key);
                return value; // Returns string or null directly
            } catch (e) {
                console.error('[Polyfill] sessionStorage.getItem error:', e);
                return null;
            }
        },
        setItem: function(key, value) {
            try {
                window.StorageBridge.setItem('session', key, String(value));
            } catch (e) {
                console.error('[Polyfill] sessionStorage.setItem error:', e);
                throw e;
            }
        },
        removeItem: function(key) {
            try {
                window.StorageBridge.removeItem('session', key);
            } catch (e) {
                console.error('[Polyfill] sessionStorage.removeItem error:', e);
            }
        },
        clear: function() {
            try {
                window.StorageBridge.clear('session');
            } catch (e) {
                console.error('[Polyfill] sessionStorage.clear error:', e);
            }
        },
        key: function(index) {
            try {
                return window.StorageBridge.key('session', index);
            } catch (e) {
                console.error('[Polyfill] sessionStorage.key error:', e);
                return null;
            }
        },
        get length() {
            try {
                return window.StorageBridge.length('session') || 0;
            } catch (e) {
                console.error('[Polyfill] sessionStorage.length error:', e);
                return 0;
            }
        }
    };

    // Install as non-configurable properties
    Object.defineProperty(window, 'localStorage', {
        value: localStorageImpl,
        writable: false,
        configurable: false
    });

    Object.defineProperty(window, 'sessionStorage', {
        value: sessionStorageImpl,
        writable: false,
        configurable: false
    });

    console.log('[Polyfill] Storage API initialized successfully');
})();
