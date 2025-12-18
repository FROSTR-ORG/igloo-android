// Camera polyfill - Direct Android CameraBridge integration
(function() {
    console.log('[Polyfill] Initializing Camera API...');

    // Create mediaDevices polyfill
    if (!navigator.mediaDevices) {
        navigator.mediaDevices = {};
    }

    navigator.mediaDevices.getUserMedia = function(constraints) {
        return new Promise(function(resolve, reject) {
            try {
                console.log('[Polyfill] getUserMedia called with constraints:', constraints);

                var constraintsJson = JSON.stringify(constraints || { video: true });
                var resultJson = window.CameraBridge.getUserMedia(constraintsJson);
                var result = JSON.parse(resultJson);

                console.log('[Polyfill] getUserMedia result:', result);

                // Bridge returns: {"data": streamInfo, "message": errorMessage}
                if (result.data && result.data.streamId) {
                    // Create a mock MediaStream object
                    var stream = {
                        id: result.data.streamId,
                        active: result.data.active || true,
                        getTracks: function() {
                            return (result.data.videoTracks || []).concat(result.data.audioTracks || []);
                        },
                        getVideoTracks: function() {
                            return result.data.videoTracks || [];
                        },
                        getAudioTracks: function() {
                            return result.data.audioTracks || [];
                        },
                        addTrack: function() {},
                        removeTrack: function() {},
                        _streamId: result.data.streamId
                    };
                    console.log('[Polyfill] Camera stream created:', stream.id);
                    resolve(stream);
                } else {
                    var errorMsg = result.message || 'Camera access denied';
                    console.error('[Polyfill] Camera access failed:', errorMsg);
                    reject(new Error(errorMsg));
                }
            } catch (e) {
                console.error('[Polyfill] getUserMedia error:', e);
                reject(e);
            }
        });
    };

    navigator.mediaDevices.enumerateDevices = function() {
        return new Promise(function(resolve, reject) {
            try {
                var devicesJson = window.CameraBridge.enumerateDevices();
                var devices = JSON.parse(devicesJson);

                console.log('[Polyfill] Enumerated devices:', devices);

                // Bridge returns raw array of devices
                if (Array.isArray(devices)) {
                    resolve(devices);
                } else {
                    console.error('[Polyfill] Invalid devices format:', devices);
                    reject(new Error('Invalid device enumeration response'));
                }
            } catch (e) {
                console.error('[Polyfill] enumerateDevices error:', e);
                reject(e);
            }
        });
    };

    console.log('[Polyfill] Camera API initialized');
})();
