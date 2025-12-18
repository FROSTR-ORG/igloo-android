// WebSocket polyfill - Direct Android WebSocketBridge integration
(function() {
    console.log('[Polyfill] Initializing WebSocket API...');

    // Store connection callbacks globally
    window.__wsCallbacks = window.__wsCallbacks || {};

    // Handle events from Android bridge
    window.__handleWebSocketEvent = function(eventJson) {
        try {
            var event = JSON.parse(eventJson);
            var callback = window.__wsCallbacks[event.connectionId];

            if (!callback) {
                console.warn('[Polyfill] No callback for connection:', event.connectionId);
                return;
            }

            callback(event);
        } catch (e) {
            console.error('[Polyfill] WebSocket event handler error:', e);
        }
    };

    // WebSocket polyfill constructor
    window.WebSocket = function(url, protocols) {
        var self = this;

        // WebSocket state constants
        this.CONNECTING = 0;
        this.OPEN = 1;
        this.CLOSING = 2;
        this.CLOSED = 3;

        // WebSocket properties
        this.url = url;
        this.readyState = this.CONNECTING;
        this.protocol = '';
        this.extensions = '';
        this.bufferedAmount = 0;

        // Event handlers
        this.onopen = null;
        this.onmessage = null;
        this.onerror = null;
        this.onclose = null;

        // Create connection via Android bridge
        try {
            var protocolsStr = Array.isArray(protocols) ? protocols.join(',') : (protocols || '');
            var connectionId = window.WebSocketBridge.createWebSocket(url, protocolsStr);
            this._connectionId = connectionId;

            // Register callback for this connection
            window.__wsCallbacks[connectionId] = function(event) {
                if (event.type === 'open') {
                    self.readyState = self.OPEN;
                    self.protocol = event.protocol || '';
                    if (self.onopen) {
                        self.onopen(new Event('open'));
                    }
                } else if (event.type === 'message') {
                    if (self.onmessage) {
                        var msgEvent = new MessageEvent('message', { data: event.data });
                        self.onmessage(msgEvent);
                    }
                } else if (event.type === 'error') {
                    self.readyState = self.CLOSED;
                    if (self.onerror) {
                        var errorEvent = new Event('error');
                        errorEvent.message = event.message || 'WebSocket error';
                        self.onerror(errorEvent);
                    }
                } else if (event.type === 'close') {
                    self.readyState = self.CLOSED;
                    if (self.onclose) {
                        var closeEvent = new CloseEvent('close', {
                            code: event.code || 1000,
                            reason: event.reason || '',
                            wasClean: event.wasClean !== false
                        });
                        self.onclose(closeEvent);
                    }
                    // Clean up callback
                    delete window.__wsCallbacks[connectionId];
                }
            };

            console.log('[Polyfill] WebSocket created:', url, 'ID:', connectionId);
        } catch (e) {
            console.error('[Polyfill] Failed to create WebSocket:', e);
            this.readyState = this.CLOSED;
            throw e;
        }
    };

    // WebSocket.prototype.send()
    window.WebSocket.prototype.send = function(data) {
        if (this.readyState !== this.OPEN) {
            throw new DOMException('WebSocket is not open: readyState ' + this.readyState + ' (OPEN=' + this.OPEN + ')', 'InvalidStateError');
        }

        try {
            window.WebSocketBridge.send(this._connectionId, String(data));
        } catch (e) {
            console.error('[Polyfill] WebSocket send error:', e);
            throw e;
        }
    };

    // WebSocket.prototype.close()
    window.WebSocket.prototype.close = function(code, reason) {
        if (this.readyState === this.CLOSING || this.readyState === this.CLOSED) {
            return;
        }

        this.readyState = this.CLOSING;

        try {
            window.WebSocketBridge.close(this._connectionId, code || 1000, reason || '');
        } catch (e) {
            console.error('[Polyfill] WebSocket close error:', e);
            this.readyState = this.CLOSED;
        }
    };

    console.log('[Polyfill] WebSocket API initialized successfully');
})();
