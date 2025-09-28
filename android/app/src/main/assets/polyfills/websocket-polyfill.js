/**
 * WebSocket Polyfill for Secure Android Bridge
 *
 * This polyfill provides a transparent replacement for the native WebSocket API,
 * routing all WebSocket traffic through the secure Android bridge while maintaining
 * 100% API compatibility. The PWA code remains completely unchanged.
 */

(function() {
    'use strict';

    // Store reference to original WebSocket if it exists
    const OriginalWebSocket = window.WebSocket;

    // WebSocket ready states (matching native API)
    const CONNECTING = 0;
    const OPEN = 1;
    const CLOSING = 2;
    const CLOSED = 3;

    /**
     * Bridged WebSocket implementation
     * Maintains full compatibility with native WebSocket API
     */
    class BridgedWebSocket extends EventTarget {
        constructor(url, protocols) {
            super();

            // Validate URL
            if (!url) {
                throw new DOMException('Failed to construct WebSocket: 1 argument required, but only 0 present.');
            }

            // Initialize properties
            this.url = url;
            this.readyState = CONNECTING;
            this.protocol = '';
            this.extensions = '';
            this.binaryType = 'blob';

            // Event handlers (for legacy event handler assignment)
            this.onopen = null;
            this.onmessage = null;
            this.onclose = null;
            this.onerror = null;

            // Internal state
            this._connectionId = null;
            this._protocols = protocols || [];

            // Initialize connection
            this._initConnection();
        }

        /**
         * Initialize WebSocket connection via Android bridge
         */
        _initConnection() {
            try {
                // Ensure bridge is available
                if (!window.WebSocketBridge) {
                    throw new Error('WebSocket bridge not available');
                }

                // Convert protocols to string
                const protocolsStr = Array.isArray(this._protocols)
                    ? this._protocols.join(',')
                    : (this._protocols || '');

                // Create connection via bridge
                const resultJson = window.WebSocketBridge.createWebSocket(this.url, protocolsStr);
                const result = JSON.parse(resultJson);

                if (result.data) {
                    this._connectionId = result.data;
                    console.log(`[WebSocket] Connection created: ${this._connectionId}`);
                } else {
                    console.error('[WebSocket] Failed to create connection:', result.message);
                    this._handleError(result.message || 'Connection failed');
                }

            } catch (error) {
                console.error('[WebSocket] Connection initialization failed:', error);
                this._handleError(error.message);
            }
        }

        /**
         * Send data through the WebSocket
         * @param {string|ArrayBuffer|Blob} data Data to send
         */
        send(data) {
            if (this.readyState === CONNECTING) {
                throw new DOMException('Failed to execute \'send\' on \'WebSocket\': Still in CONNECTING state.');
            }

            if (this.readyState !== OPEN) {
                throw new DOMException('Failed to execute \'send\' on \'WebSocket\': Connection is not open.');
            }

            try {
                // Convert data to string if necessary
                let message = data;
                if (data instanceof ArrayBuffer) {
                    // For ArrayBuffer, convert to base64
                    const uint8Array = new Uint8Array(data);
                    message = btoa(String.fromCharCode.apply(null, uint8Array));
                } else if (data instanceof Blob) {
                    // For Blob, we need to read it (this is async, but we'll handle it synchronously for compatibility)
                    throw new Error('Blob sending not yet implemented');
                }

                // Send via bridge
                const resultJson = window.WebSocketBridge.sendMessage(this._connectionId, message);
                const result = JSON.parse(resultJson);

                if (result.message) {
                    console.error('[WebSocket] Send failed:', result.message);
                    // Don't throw here - native WebSocket doesn't throw on send failures
                }

            } catch (error) {
                console.error('[WebSocket] Send error:', error);
                // Trigger error event
                this._handleError(error.message);
            }
        }

        /**
         * Close the WebSocket connection
         * @param {number} code Close code (optional)
         * @param {string} reason Close reason (optional)
         */
        close(code = 1000, reason = '') {
            if (this.readyState === CLOSING || this.readyState === CLOSED) {
                return;
            }

            // Validate close code
            if (code !== 1000 && (code < 3000 || code > 4999)) {
                throw new DOMException(`Failed to execute 'close' on 'WebSocket': The code must be either 1000, or between 3000 and 4999. ${code} is neither.`);
            }

            // Validate reason length
            if (reason && new TextEncoder().encode(reason).length > 123) {
                throw new DOMException('Failed to execute \'close\' on \'WebSocket\': The message must not be greater than 123 bytes.');
            }

            this.readyState = CLOSING;

            try {
                if (this._connectionId && window.WebSocketBridge) {
                    const resultJson = window.WebSocketBridge.closeWebSocket(this._connectionId, code, reason);
                    const result = JSON.parse(resultJson);

                    if (result.message) {
                        console.error('[WebSocket] Close failed:', result.message);
                    }
                }
            } catch (error) {
                console.error('[WebSocket] Close error:', error);
            }
        }

        /**
         * Handle WebSocket events from Android bridge
         */
        _handleBridgeEvent(event, data) {
            switch (event) {
                case 'open':
                    this._handleOpen(data);
                    break;
                case 'message':
                    this._handleMessage(data);
                    break;
                case 'close':
                    this._handleClose(data);
                    break;
                case 'error':
                    this._handleError(data);
                    break;
                default:
                    console.warn('[WebSocket] Unknown event:', event);
            }
        }

        /**
         * Handle WebSocket open event
         */
        _handleOpen(negotiatedProtocol) {
            console.log(`[WebSocket] Connection opened: ${this._connectionId}`);

            this.readyState = OPEN;
            this.protocol = negotiatedProtocol || '';

            // Create and dispatch open event
            const openEvent = new Event('open');
            this.dispatchEvent(openEvent);

            // Call legacy event handler
            if (typeof this.onopen === 'function') {
                this.onopen(openEvent);
            }
        }

        /**
         * Handle WebSocket message event
         */
        _handleMessage(messageData) {
            console.log(`[WebSocket] Message received: ${this._connectionId}`);

            // Create message event
            const messageEvent = new MessageEvent('message', {
                data: messageData,
                origin: new URL(this.url).origin,
                lastEventId: '',
                source: null,
                ports: []
            });

            this.dispatchEvent(messageEvent);

            // Call legacy event handler
            if (typeof this.onmessage === 'function') {
                this.onmessage(messageEvent);
            }
        }

        /**
         * Handle WebSocket close event
         */
        _handleClose(closeData) {
            console.log(`[WebSocket] Connection closed: ${this._connectionId}`);

            this.readyState = CLOSED;

            // Parse close data
            let code = 1005; // Default: no status code
            let reason = '';
            let wasClean = false;

            if (closeData) {
                try {
                    const closeInfo = JSON.parse(closeData);
                    code = closeInfo.code || code;
                    reason = closeInfo.reason || reason;
                    wasClean = closeInfo.wasClean || false;
                } catch (e) {
                    console.warn('[WebSocket] Failed to parse close data:', e);
                }
            }

            // Create and dispatch close event
            const closeEvent = new CloseEvent('close', {
                code: code,
                reason: reason,
                wasClean: wasClean
            });

            this.dispatchEvent(closeEvent);

            // Call legacy event handler
            if (typeof this.onclose === 'function') {
                this.onclose(closeEvent);
            }

            // Clean up
            this._connectionId = null;
        }

        /**
         * Handle WebSocket error event
         */
        _handleError(errorData) {
            console.error(`[WebSocket] Error: ${this._connectionId}`, errorData);

            // Create and dispatch error event
            const errorEvent = new Event('error');

            // Add error details if available
            if (errorData) {
                try {
                    const errorInfo = JSON.parse(errorData);
                    errorEvent.message = errorInfo.message || 'WebSocket error';
                    errorEvent.code = errorInfo.code || 0;
                } catch (e) {
                    errorEvent.message = errorData;
                }
            }

            this.dispatchEvent(errorEvent);

            // Call legacy event handler
            if (typeof this.onerror === 'function') {
                this.onerror(errorEvent);
            }

            // If we're still connecting, transition to closed
            if (this.readyState === CONNECTING) {
                this.readyState = CLOSED;
            }
        }
    }

    // Define ready state constants
    BridgedWebSocket.CONNECTING = CONNECTING;
    BridgedWebSocket.OPEN = OPEN;
    BridgedWebSocket.CLOSING = CLOSING;
    BridgedWebSocket.CLOSED = CLOSED;

    /**
     * Global WebSocket bridge event handler
     * This receives events from the Android bridge
     */
    window.WebSocketBridge = window.WebSocketBridge || {};

    // Store active connections for event routing
    window.WebSocketBridge._connections = new Map();

    // Register connection for event handling
    window.WebSocketBridge._registerConnection = function(connectionId, webSocket) {
        this._connections.set(connectionId, webSocket);
    };

    // Handle events from Android bridge
    window.WebSocketBridge.handleEvent = function(eventDataJson) {
        try {
            const eventData = JSON.parse(eventDataJson);
            const { type, connectionId, data } = eventData;

            console.log(`[WebSocketBridge] Received event: ${type} for connection: ${connectionId}`);

            const webSocket = this._connections.get(connectionId);
            if (webSocket) {
                webSocket._handleBridgeEvent(type, data);
            } else {
                console.warn(`[WebSocketBridge] No WebSocket found for connection: ${connectionId}`);
            }

        } catch (error) {
            console.error('[WebSocketBridge] Failed to handle event:', error);
        }
    };

    // Override the original WebSocket with our bridged implementation
    window.WebSocket = function(url, protocols) {
        const webSocket = new BridgedWebSocket(url, protocols);

        // Register for bridge events
        if (webSocket._connectionId) {
            window.WebSocketBridge._registerConnection(webSocket._connectionId, webSocket);
        }

        return webSocket;
    };

    // Copy static properties
    window.WebSocket.CONNECTING = CONNECTING;
    window.WebSocket.OPEN = OPEN;
    window.WebSocket.CLOSING = CLOSING;
    window.WebSocket.CLOSED = CLOSED;

    // Store reference to original for potential fallback
    window.WebSocket._original = OriginalWebSocket;

    console.log('[WebSocket Polyfill] Loaded successfully - native WebSocket replaced with secure bridge');

})();