# Igloo Mobile

A NIP-55 and FROSTR signing device, wrapped in a hybrid android application.

## Overview

This application is built as a PWA (progressive web app) at its core, wrapped in a light android compatibility layer. This allows the PWA to better integrate with device intents (for NIP-55 support), persist connections in the background (for relay subscriptions), and survive the brutal dictatorship that is android power management.

### Features

* Uses a `bifrost` node to connect to your FROSTR network (over nostr).
* Handles NIP-55 device signature requests for the `nostrsigner://` URI scheme.
* Provides both manual (prompt) and automated (background) signing modes.
* Includes permissions management for peers and event signing.
* QR-Code scanning for easy setup and key exchange.
* Secure enclave (on device) for storing cryptographic keys.

### Architecture

The architecture of the android application shell is the following:

* **Local Web View**: Renders the PWA as a web application
* **Local Web Server**: Hosts the PWA and provides manual version control
* **Secure Storage**: Stores session secrets while the PWA is asleep
* **Web App Bridge**: Provides the PWA access to android system calls

The PWA itself is built with:
* **Frontend**: React 19 + TypeScript with Context-based state management.
* **Build System**: Custom esbuild configuration with hot reload.
* **Service Worker**: PWA capabilities with background sync and offline support.
* **Cryptography**: Noble libraries for cryptographic operations.
* **Nostr Integration**: NIP-55 signing support with FROSTR bifrost node.

### Filesystem

- `android/`: Android application shell (Kotlin), APK files, and gradle build environment
- `docs/`: Documentation including NIP-55 guides, testing procedures, and protocol references
- `dist/`: Output directory for esbuild and packaged PWA
- `public/`: Web resources (index.html, manifest.json, icons, test files)
- `script/`: Utility scripts for build, development, key generation, and relay server
- `src/`: TypeScript/React source code for the PWA
  - `components/`: React components (app, dash, settings, permissions, prompt, layout, utils)
  - `context/`: React Context providers (console, cache, node, prompt, settings)
  - `hooks/`: Custom React hooks (useBifrost, useNIP55Handler)
  - `lib/`: Core libraries (nip55, cipher, enclave, encoder, nip19, util)
  - `class/`: TypeScript classes (signer, store)
  - `styles/`: CSS files for UI components
  - `types.ts`: Centralized TypeScript type definitions
- `test/`: Test suite (work in progress)

## Development

### Prerequisites

- Node.js and npm
- Android SDK (for Android development)
- ADB tools (for testing and debugging)

### PWA Development

```bash
# Install dependencies
npm install

# Start development server with hot reload (port 3000)
npm run dev

# Build production version
npm run build

# Run test suite
npm run test
```

The dev server runs on port 3000 and is accessible on all network interfaces for mobile testing.

### Android Development

```bash
# Monitor Android app logs
adb logcat -s IglooWrapper:* PWA-*:*

# Clear logs before monitoring
adb logcat -c

# Test NIP-55 intents
./test-nip55-intents.sh
```

### Utility Scripts

```bash
# Generate cryptographic keys
npm run keygen

# Run development relay server
npm run relay

# Create release build
npm run release
```

### Build System

The project uses a custom esbuild configuration with:
- **Entry Points**: `src/index.tsx` → `dist/app.js`, `src/sw.ts` → `dist/sw.js`
- **TypeScript**: Full TypeScript support with path aliases (`@/*` → `src/*`)
- **CSS**: Separate CSS files copied from `src/styles/` to `dist/styles/`
- **Watch Mode**: Hot reload during development
- **PWA**: Service worker for offline capabilities

## Testing

### PWA Testing
- Use Chrome DevTools for debugging
- Test on localhost:3000 for development
- Use the built-in console for runtime debugging

### Android Testing
- Use Android Studio or command-line tools
- Test NIP-55 intents with the provided script
- Monitor logs with `adb logcat`
- Debug PWA within WebView using Chrome DevTools

### NIP-55 Protocol Testing
The project includes comprehensive NIP-55 testing:
- Intent testing script (`test-nip55-intents.sh`)
- Protocol handler debugging guides
- Localhost testing procedures
- URL handler compatibility testing

## Documentation

The `docs/` directory contains:
- **NIP-55.md**: Core NIP-55 specification details
- **NIP-55-compatibility.md**: Compatibility notes and testing

## Configuration

The application supports URL parameters for configuration:
- `group`: Group configuration
- `pubkey`: Public key setup
- `share`: Share package data
- `relay`: Relay URL configuration
- `nip55`: NIP-55 request handling

## Key Technologies

### Core Dependencies
- **React 19**: Modern React with hooks and Context
- **TypeScript**: Full type safety
- **@frostr/bifrost**: FROSTR network integration
- **@cmdcode/nostr-p2p**: Nostr peer-to-peer networking
- **@noble/ciphers, @noble/hashes**: Cryptographic operations
- **nostr-tools**: Nostr protocol utilities

### Development Tools
- **esbuild**: Fast TypeScript/React bundling
- **tsx**: TypeScript execution
- **serve**: Development server
- **concurrently**: Parallel script execution
- **tape**: Testing framework

### Android Integration
- **Package**: `com.frostr.igloo`
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **WebView**: Chrome-based for PWA rendering
- **Intent Handling**: NIP-55 URL scheme support

## Protocol Support

- **NIP-55**: Android intent-based signing
- **FROSTR**: Distributed key management and signing
- **PWA**: Progressive Web App standards
- **WebSocket**: Real-time relay connections with mobile persistence strategies