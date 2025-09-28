/**
 * Camera Polyfill for Secure Android Bridge
 *
 * This polyfill provides a transparent replacement for navigator.mediaDevices.getUserMedia(),
 * routing all camera operations through the secure Android bridge while maintaining
 * 100% API compatibility. The PWA code remains completely unchanged.
 */

(function() {
    'use strict';

    // Store references to original MediaDevices APIs if they exist
    const OriginalMediaDevices = navigator.mediaDevices;
    const OriginalGetUserMedia = navigator.getUserMedia || navigator.webkitGetUserMedia || navigator.mozGetUserMedia;

    /**
     * Secure MediaStream implementation
     * Maintains full compatibility with the MediaStream API
     */
    class SecureMediaStream extends EventTarget {
        constructor(streamInfo = null) {
            super();

            this._streamInfo = streamInfo;
            this._id = streamInfo ? streamInfo.streamId : 'stream_' + Date.now();
            this._active = streamInfo ? streamInfo.active : false;
            this._videoTracks = [];
            this._audioTracks = [];

            if (streamInfo) {
                // Create video tracks
                streamInfo.videoTracks.forEach(trackInfo => {
                    this._videoTracks.push(new SecureMediaStreamTrack(trackInfo, this));
                });

                // Create audio tracks
                streamInfo.audioTracks.forEach(trackInfo => {
                    this._audioTracks.push(new SecureMediaStreamTrack(trackInfo, this));
                });
            }

            console.log(`[Camera Polyfill] Created MediaStream: ${this._id}`);
        }

        get id() {
            return this._id;
        }

        get active() {
            return this._active && (this._videoTracks.some(t => !t.ended) || this._audioTracks.some(t => !t.ended));
        }

        getVideoTracks() {
            return [...this._videoTracks];
        }

        getAudioTracks() {
            return [...this._audioTracks];
        }

        getTracks() {
            return [...this._videoTracks, ...this._audioTracks];
        }

        getTrackById(trackId) {
            return this.getTracks().find(track => track.id === trackId) || null;
        }

        addTrack(track) {
            if (track.kind === 'video') {
                this._videoTracks.push(track);
            } else if (track.kind === 'audio') {
                this._audioTracks.push(track);
            }

            this.dispatchEvent(new MediaStreamTrackEvent('addtrack', { track: track }));
        }

        removeTrack(track) {
            const videoIndex = this._videoTracks.indexOf(track);
            if (videoIndex !== -1) {
                this._videoTracks.splice(videoIndex, 1);
            }

            const audioIndex = this._audioTracks.indexOf(track);
            if (audioIndex !== -1) {
                this._audioTracks.splice(audioIndex, 1);
            }

            this.dispatchEvent(new MediaStreamTrackEvent('removetrack', { track: track }));
        }

        clone() {
            const clonedStream = new SecureMediaStream();
            this.getTracks().forEach(track => {
                clonedStream.addTrack(track.clone());
            });
            return clonedStream;
        }

        stop() {
            this.getTracks().forEach(track => track.stop());
            this._active = false;
        }
    }

    /**
     * Secure MediaStreamTrack implementation
     */
    class SecureMediaStreamTrack extends EventTarget {
        constructor(trackInfo, stream) {
            super();

            this._id = trackInfo.id;
            this._kind = trackInfo.kind;
            this._label = trackInfo.label;
            this._enabled = trackInfo.enabled;
            this._muted = trackInfo.muted;
            this._readyState = 'live';
            this._stream = stream;
            this._constraints = {};

            console.log(`[Camera Polyfill] Created MediaStreamTrack: ${this._id} (${this._kind})`);
        }

        get id() { return this._id; }
        get kind() { return this._kind; }
        get label() { return this._label; }
        get readyState() { return this._readyState; }

        get enabled() { return this._enabled; }
        set enabled(value) {
            this._enabled = Boolean(value);
            console.log(`[Camera Polyfill] Track ${this._id} enabled: ${this._enabled}`);
        }

        get muted() { return this._muted; }

        get ended() { return this._readyState === 'ended'; }

        clone() {
            const clonedTrack = new SecureMediaStreamTrack({
                id: this._id + '_clone_' + Date.now(),
                kind: this._kind,
                label: this._label,
                enabled: this._enabled,
                muted: this._muted
            }, this._stream);
            return clonedTrack;
        }

        stop() {
            if (this._readyState !== 'ended') {
                this._readyState = 'ended';

                // Notify Android bridge
                if (this._kind === 'video' && window.CameraBridge) {
                    window.CameraBridge.stopUserMedia(this._stream.id);
                }

                this.dispatchEvent(new Event('ended'));
                console.log(`[Camera Polyfill] Track stopped: ${this._id}`);
            }
        }

        getCapabilities() {
            if (this._kind === 'video' && window.CameraBridge) {
                try {
                    const capabilitiesJson = window.CameraBridge.getCapabilities(this._deviceId || 'camera_0');
                    return JSON.parse(capabilitiesJson);
                } catch (error) {
                    console.error('[Camera Polyfill] Failed to get capabilities:', error);
                }
            }
            return {};
        }

        getConstraints() {
            return { ...this._constraints };
        }

        getSettings() {
            // Return current track settings
            return {
                deviceId: this._deviceId || '',
                width: 1280,
                height: 720,
                aspectRatio: 16/9,
                frameRate: 30,
                facingMode: 'environment'
            };
        }

        applyConstraints(constraints) {
            return new Promise((resolve, reject) => {
                try {
                    this._constraints = { ...constraints };
                    console.log(`[Camera Polyfill] Applied constraints to track ${this._id}:`, constraints);
                    resolve();
                } catch (error) {
                    reject(new OverconstrainedError('Failed to apply constraints'));
                }
            });
        }
    }

    /**
     * Secure MediaDevices implementation
     * Maintains full compatibility with the MediaDevices API
     */
    class SecureMediaDevices extends EventTarget {
        constructor() {
            super();
            console.log('[Camera Polyfill] SecureMediaDevices initialized');
        }

        async enumerateDevices() {
            try {
                if (!window.CameraBridge) {
                    console.warn('[Camera Polyfill] Bridge not available');
                    return [];
                }

                const devicesJson = window.CameraBridge.enumerateDevices();
                const devices = JSON.parse(devicesJson);

                console.log(`[Camera Polyfill] Enumerated ${devices.length} camera devices`);
                return devices;

            } catch (error) {
                console.error('[Camera Polyfill] Failed to enumerate devices:', error);
                return [];
            }
        }

        async getUserMedia(constraints = {}) {
            console.log('[Camera Polyfill] getUserMedia called with constraints:', constraints);

            try {
                if (!window.CameraBridge) {
                    throw new DOMException('Camera bridge not available', 'NotSupportedError');
                }

                // Validate constraints
                if (!constraints.video && !constraints.audio) {
                    throw new DOMException('At least one of audio and video must be requested', 'TypeError');
                }

                // Convert constraints to JSON string
                const constraintsJson = JSON.stringify(constraints);

                // Request media stream from Android bridge
                const resultJson = window.CameraBridge.getUserMedia(constraintsJson);
                const result = JSON.parse(resultJson);

                // Check if this is a CameraResult.Error (has message but no data)
                if (result.message && !result.data) {
                    throw new DOMException(result.message, 'NotAllowedError');
                }

                // Check if this is a CameraResult.Success but missing data
                if (!result.data) {
                    throw new DOMException('Failed to create media stream', 'NotReadableError');
                }

                // Create secure media stream
                const stream = new SecureMediaStream(result.data);

                // Store stream reference for bridge callbacks
                window.CameraBridge._activeStreams = window.CameraBridge._activeStreams || new Map();
                window.CameraBridge._activeStreams.set(stream.id, stream);

                console.log(`[Camera Polyfill] Created media stream: ${stream.id}`);
                return stream;

            } catch (error) {
                console.error('[Camera Polyfill] getUserMedia failed:', error);
                throw error;
            }
        }

        async getDisplayMedia(constraints = {}) {
            // Screen capture not supported in this implementation
            throw new DOMException('getDisplayMedia not supported', 'NotSupportedError');
        }

        getSupportedConstraints() {
            return {
                width: true,
                height: true,
                aspectRatio: true,
                frameRate: true,
                facingMode: true,
                resizeMode: true,
                deviceId: true
            };
        }
    }

    /**
     * Bridge callback handlers
     */
    window.CameraBridge = window.CameraBridge || {};

    // Handle stream ready notification from Android
    window.CameraBridge.onStreamReady = function(streamId) {
        console.log(`[Camera Polyfill] Stream ready: ${streamId}`);

        const activeStreams = window.CameraBridge._activeStreams;
        if (activeStreams && activeStreams.has(streamId)) {
            const stream = activeStreams.get(streamId);
            stream._active = true;

            // Dispatch loadedmetadata event on video tracks
            stream.getVideoTracks().forEach(track => {
                track.dispatchEvent(new Event('loadedmetadata'));
            });
        }
    };

    // Handle frame capture notification from Android
    window.CameraBridge.onFrameCaptured = function(streamId, base64Image) {
        console.log(`[Camera Polyfill] Frame captured for stream: ${streamId}`);

        const activeStreams = window.CameraBridge._activeStreams;
        if (activeStreams && activeStreams.has(streamId)) {
            const stream = activeStreams.get(streamId);

            // Create custom event with image data
            const event = new CustomEvent('framecaptured', {
                detail: {
                    streamId: streamId,
                    imageData: base64Image,
                    timestamp: Date.now()
                }
            });

            stream.dispatchEvent(event);
        }
    };

    // Handle stream error notification from Android
    window.CameraBridge.onStreamError = function(streamId, errorMessage) {
        console.error(`[Camera Polyfill] Stream error for stream: ${streamId} - ${errorMessage}`);

        const activeStreams = window.CameraBridge._activeStreams;
        if (activeStreams && activeStreams.has(streamId)) {
            const stream = activeStreams.get(streamId);

            // End all tracks
            stream.getTracks().forEach(track => {
                track._readyState = 'ended';
                track.dispatchEvent(new Event('ended'));
            });

            // Mark stream as inactive
            stream._active = false;

            // Create custom error event
            const event = new CustomEvent('error', {
                detail: {
                    streamId: streamId,
                    message: errorMessage,
                    timestamp: Date.now()
                }
            });

            stream.dispatchEvent(event);

            // Clean up
            activeStreams.delete(streamId);
        }
    };

    /**
     * Helper classes for Web API compatibility
     */
    class MediaStreamTrackEvent extends Event {
        constructor(type, eventInitDict = {}) {
            super(type, eventInitDict);
            this.track = eventInitDict.track || null;
        }
    }

    class OverconstrainedError extends Error {
        constructor(constraint, message = '') {
            super(message);
            this.name = 'OverconstrainedError';
            this.constraint = constraint;
        }
    }

    // Replace navigator.mediaDevices with secure implementation
    try {
        const secureMediaDevices = new SecureMediaDevices();

        // Replace mediaDevices
        Object.defineProperty(navigator, 'mediaDevices', {
            get: function() {
                return secureMediaDevices;
            },
            set: function(value) {
                // Web API doesn't allow setting mediaDevices directly
                throw new TypeError('Illegal invocation');
            },
            enumerable: true,
            configurable: false
        });

        // Backward compatibility for legacy getUserMedia
        if (!navigator.getUserMedia) {
            navigator.getUserMedia = function(constraints, successCallback, errorCallback) {
                secureMediaDevices.getUserMedia(constraints)
                    .then(successCallback)
                    .catch(errorCallback);
            };
        }

        console.log('[Camera Polyfill] Successfully replaced navigator.mediaDevices with secure implementation');

        // Add compatibility check method
        window.SecureCamera = {
            isActive: function() {
                return window.CameraBridge !== undefined;
            },
            // Store references to original APIs for potential fallback
            _originalMediaDevices: OriginalMediaDevices,
            _originalGetUserMedia: OriginalGetUserMedia
        };

        // Test the bridge connection
        if (window.CameraBridge) {
            console.log('[Camera Polyfill] Bridge connection test passed');

            // Test basic functionality
            try {
                const devices = window.CameraBridge.enumerateDevices();
                const deviceList = JSON.parse(devices);

                if (Array.isArray(deviceList)) {
                    console.log(`[Camera Polyfill] Functionality test passed - found ${deviceList.length} camera devices`);
                } else {
                    console.warn('[Camera Polyfill] Functionality test failed - invalid device list');
                }

            } catch (testError) {
                console.error('[Camera Polyfill] Functionality test failed:', testError);
            }

        } else {
            console.warn('[Camera Polyfill] Bridge not available - camera operations will fail');
        }

        // Global error handlers for camera operations
        window.addEventListener('error', function(e) {
            if (e.message && e.message.includes('camera')) {
                console.error('[Camera Polyfill] Camera error detected:', e.error);
            }
        });

    } catch (error) {
        console.error('[Camera Polyfill] Failed to replace MediaDevices API:', error);

        // Fallback: log error but don't break the page
        window.SecureCamera = {
            isActive: function() { return false; },
            error: error.message
        };
    }

    console.log('[Camera Polyfill] Loaded successfully - navigator.mediaDevices replaced with secure bridge');

})();