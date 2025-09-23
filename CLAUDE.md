# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Progressive Web Application (PWA) called "Igloo PWA" - a NIP-46 and NIP-55 signing device for mobile and web, powered by FROSTR. It's a Bitcoin/Nostr crypto wallet application that handles cryptographic operations in a secure environment.

The PWA is designed to be wrapped in an Android application shell that provides:
- Local Web View for rendering the PWA
- Local Web Server for hosting and manual version control
- Secure Storage for session secrets during background operation
- Web App Bridge for accessing Android system calls and intents

## Development Commands

### Build and Development
- `npm run dev` - Start development server with watch mode and hot reload (port 3000)
- `npm run build` - Build production version to `dist/` directory
- `npm run test` - Run test suite using tape

### Utility Scripts
- `npm run keygen` - Generate cryptographic keys
- `npm run relay` - Run relay server
- `npm run release` - Create release build

The dev server runs on port 3000 and is accessible on all network interfaces for mobile testing.

### Android/Mobile Development
- `adb logcat -s IglooWrapper:* PWA-*:*` - Monitor Android app logs for debugging
- `adb logcat -c` - Clear Android logs before monitoring
- The Android companion app provides secure storage and system integration
- Use Chrome DevTools on desktop to debug the PWA when running in Android WebView

## Architecture Overview

### Build System
- **Build Tool**: Custom esbuild configuration in `script/build.ts`
- **Entry Points**:
  - Main app: `src/index.tsx` → `dist/app.js`
  - Service Worker: `src/sw.ts` → `dist/sw.js`
- **CSS**: Separate CSS files are copied from `src/styles/` to `dist/styles/`
- **Path Aliases**: `@/*` maps to `src/*`

### Core Structure
- **Frontend**: React 19 with TypeScript, using React Context for state management
- **Service Worker**: Custom PWA service worker for background sync and offline capabilities
- **Mobile Optimization**: Includes WebSocket persistence strategies for mobile browsers
- **State Management**: Uses React Context providers instead of React Query for application state

### Key Directories
- `android/` - Android application shell source code (Kotlin), APK files, and gradle build environment
- `docs/` - Documentation, guides, and protocol specification references
- `public/` - Web resources for the PWA (index.html, images, styling, manifest.json)
- `scripts/` - Utility scripts for building and launching development tools
- `src/components/` - React components organized by feature (layout, dash, settings)
- `src/context/` - React context providers for state management (console, cache, node, settings)
- `src/hooks/` - React hooks (useBifrost, useConsole)
- `src/types.ts` - Centralized TypeScript type definitions and interfaces
- `src/lib/` - Utility libraries (cipher, enclave, encoder, nip19, util)
- `src/class/` - Class definitions (signer, store)
- `src/util/` - Utility functions (logger)
- `src/styles/` - CSS files for different UI components
- `dist/` - Output directory for esbuild and packaged PWA
- `test/` - Test suite for the entire application (work in progress)

### Mobile WebSocket Persistence
The app implements several strategies to maintain WebSocket connections on mobile:
- Page Visibility API for background/foreground detection
- Exponential backoff reconnection (up to 10 attempts)
- Keep-alive pings every 30 seconds
- Service Worker background sync
- Connection state monitoring

Configuration can be adjusted in the node context provider.

### Dependencies
- **Core**: React 19, TypeScript
- **Crypto**: @noble/ciphers, @noble/hashes, nostr-tools
- **FROSTR/Nostr**: @cmdcode/nostr-p2p, @frostr/bifrost
- **Utils**: @vbyte/buff, @vbyte/micro-lib
- **Mobile**: qr-scanner for QR code functionality
- **Build**: esbuild, tsx, serve, concurrently

### Testing
- Uses `tape` testing framework
- Test entry point: `test/tape.ts`
- Currently minimal test coverage

## Development Notes

- The application uses ES modules and modern TypeScript
- Service Worker is critical for PWA functionality and mobile persistence
- CSS is handled as separate files, not CSS-in-JS
- The app supports URL parameters for configuration (group, pubkey, share, relay URLs)
- Network status monitoring is built-in for debugging mobile connectivity issues
- The PWA handles NIP-55 signature requests via `nostrsigner://` URI scheme when wrapped in Android
- Background FROSTR signing operations are supported through the Android bridge
- The app includes both manual (prompt-based) and automated (background) signing modes

## Detailed Documentation

For comprehensive understanding of the codebase architecture:

### Source Code Architecture
- **`src/README.md`** - Complete PWA source code documentation including:
  - TypeScript type system and interfaces
  - React component architecture and Context providers
  - Custom hooks and state management patterns
  - NIP-55 implementation details
  - Service Worker and PWA capabilities
  - Security considerations and best practices

### Android Wrapper Architecture
- **`android/README.md`** - Complete Android application shell documentation including:
  - Hybrid application architecture and integration patterns
  - Java source code breakdown (MainActivity, SecureStorage, JavaScriptBridge)
  - Android Keystore security implementation
  - NIP-55 intent handling and processing
  - Build configuration and development workflow
  - PWA ↔ Android bridge communication protocols