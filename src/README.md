# Source Code Directory (src/)

This directory contains the complete TypeScript/React PWA source code for the Igloo signing device. The codebase is organized into a modular architecture with clear separation of concerns.

## Architecture Overview

### Technology Stack
- **React 19**: Modern React with hooks and functional components
- **TypeScript**: Full type safety with strict configuration
- **Context API**: State management without external libraries
- **CSS Modules**: Component-specific styling
- **@frostr/bifrost**: FROSTR network integration
- **@noble/crypto**: Cryptographic operations
- **Service Worker**: PWA capabilities

### Path Aliases
- `@/*` maps to `src/*` for clean imports
- All imports use `.js` extensions for ESM compatibility

## Directory Structure

```
src/
├── index.tsx              # App entry point with providers
├── types.ts               # Centralized TypeScript definitions
├── const.ts               # Application constants
├── sw.ts                  # Service Worker for PWA functionality
├── components/            # React components
├── context/               # React Context providers
├── hooks/                 # Custom React hooks
├── lib/                   # Core utility libraries
├── class/                 # TypeScript classes
├── util/                  # Helper utilities
└── styles/                # CSS stylesheets
```

## Core Modules

### Types System (`types.ts`)

Central type definitions including:

#### Core Types
- `NodeStatus`: Bifrost node states (`init`, `disabled`, `locked`, `online`, `offline`)
- `LogType`: Console log levels (`info`, `debug`, `error`, `warn`)
- `PermissionType`: Permission categories (`action`, `event`)
- `NIP55RequestType`: Supported NIP-55 operations
- `PromptStatus`: User response states (`pending`, `approved`, `denied`)

#### Key Interfaces
- `BifrostNodeAPI`: Bifrost node management interface
- `WebConsoleAPI`: Debug console interface
- `SettingsData`: Application configuration structure
- `CacheAPI<T>`: Generic cache management
- `StoreAPI<T>`: Persistent storage with subscriptions
- `NIP55Request`: NIP-55 request types with full type safety
- `PromptAPI`: User prompt management
- `AndroidSecureStorage`: Android bridge interface

#### NIP-55 Request Types
Complete type coverage for all NIP-55 operations:
- `GetPublicKeyRequest`: Retrieve wallet public key
- `SignEventRequest`: Sign Nostr events
- `EncryptRequest`: NIP-04/NIP-44 encryption
- `DecryptRequest`: NIP-04/NIP-44 decryption
- `DecryptZapRequest`: Zap event decryption

### Application Entry (`index.tsx`)

The main entry point sets up:
- **Provider Hierarchy**: ConsoleProvider → NodeProvider → SettingsProvider → PromptProvider
- **CSS Imports**: All stylesheets imported at application level
- **PWA Installation**: Install prompt handling and user engagement tracking
- **NIP-55 URL Handling**: Initial URL parameter processing

## Context Providers

### NodeProvider (`context/node.tsx`)
- **Purpose**: Manages Bifrost node state and operations
- **Hook**: `useBifrostNode()`
- **Dependencies**: `useBifrost` custom hook
- **Features**: Node lifecycle, peer management, connection status

### PromptProvider (`context/prompt.tsx`)
- **Purpose**: Handles NIP-55 signing prompts and user interactions
- **Hook**: `usePrompt()`
- **Features**: Permission checking, user prompts, callback handling
- **Integration**: Uses SettingsProvider for permissions, NodeProvider for signing

### SettingsProvider (`context/settings.tsx`)
- **Purpose**: Application configuration and persistence
- **Hook**: `useSettings()`
- **Storage**: Local storage with reactive updates
- **Data**: Groups, peers, relays, permissions, public keys

### ConsoleProvider (`context/console.tsx`)
- **Purpose**: Debug logging and console management
- **Hook**: `useConsole()`
- **Features**: Log aggregation, filtering, persistence limits

### CacheProvider (`context/cache.tsx`)
- **Purpose**: Temporary data storage and session management
- **Hook**: `useCache()`
- **Features**: Public key caching, share package storage

## Component Architecture

### Layout Components (`components/layout/`)
- **Header** (`header.tsx`): App title, status indicators
- **Tabs** (`tabs.tsx`): Main navigation interface

### Dashboard Components (`components/dash/`)
- **Dashboard** (`index.tsx`): Main application view
- **Console** (`console.tsx`): Debug log viewer
- **Node** (`node.tsx`): Bifrost node status and controls
- **Peers** (`peers.tsx`): Connected peer management

### Settings Components (`components/settings/`)
- **Settings** (`index.tsx`): Configuration management hub
- **Group** (`group.tsx`): FROSTR group configuration
- **Peers** (`peers.tsx`): Peer configuration management
- **Relays** (`relays.tsx`): Relay server configuration
- **Share** (`share.tsx`): Share package management
- **Reset** (`reset.tsx`): Application reset functionality

### Prompt System (`components/prompt/`)
- **PromptManager** (`index.tsx`): Main prompt orchestrator
- **ActionPrompt** (`action.tsx`): Non-signing operations (encrypt, decrypt, get_public_key)
- **EventPrompt** (`event.tsx`): Event signing with security warnings
- **Demo** (`demo.tsx`): Testing and demonstration interface

See `components/prompt/README.md` for detailed prompt system documentation.

### Permissions Management (`components/permissions/`)
- **PermissionsView** (`index.tsx`): Permission management interface
- **Features**: Action/event permission tables, removal controls

See `components/permissions/README.md` for detailed permissions documentation.

### Utility Components (`components/util/`)
- **Icons** (`icons.tsx`): SVG icon components
- **Scanner** (`scanner.tsx`): QR code scanning functionality

### Banner Components (`components/banner/`)
- **PendingRequest** (`pending-request.tsx`): Notification for queued requests

## Custom Hooks

### useBifrost (`hooks/useBifrost.ts`)
- **Purpose**: Bifrost node lifecycle management
- **Features**: Connection handling, peer management, status tracking
- **Integration**: Core hook used by NodeProvider

### useNIP55Handler (`hooks/useNIP55Handler.ts`)
- **Purpose**: NIP-55 URL parameter processing
- **Features**: URL parsing, request routing, Android intent handling
- **Integration**: Used in main App component

## Core Libraries

### NIP-55 Support (`lib/nip55.ts`)
- **URL Parsing**: Handles `nostrsigner:` scheme
- **Request Validation**: Type-safe NIP-55 request parsing
- **Parameter Extraction**: Query parameter and path parsing
- **Android Integration**: Intent data handling

### Cryptography (`lib/cipher.ts`, `lib/enclave.ts`)
- **Cipher**: Encryption/decryption utilities
- **Enclave**: Secure key storage and cryptographic operations
- **Integration**: Noble crypto libraries

### Encoding (`lib/encoder.ts`, `lib/nip19.ts`)
- **Encoder**: Data serialization utilities
- **NIP-19**: Nostr bech32 encoding/decoding

### Utilities (`lib/util.ts`)
- **Purpose**: General utility functions
- **Features**: Data manipulation, validation helpers

## TypeScript Classes

### BifrostSigner (`class/signer.ts`)
- **Purpose**: Cryptographic signing operations
- **Features**: Event signing, encryption, key management
- **Integration**: Used by prompt system for NIP-55 operations

### Store (`class/store.ts`)
- **Purpose**: Reactive data storage with persistence
- **Features**: Local storage integration, change subscriptions
- **Usage**: Backing store for settings and cache providers

## Utilities

### Logger (`util/logger.ts`)
- **Purpose**: Centralized logging system
- **Features**: Level-based logging, console integration
- **Integration**: Used throughout the application

## Styling System

### CSS Architecture (`styles/`)
- **Global** (`global.css`): Base styles, CSS variables
- **Layout** (`layout.css`): App structure, navigation
- **Component-Specific**: Dedicated CSS files per major component
- **Responsive**: Mobile-first design with desktop enhancements

### Style Files
- `banner.css`: Notification banner styles
- `console.css`: Debug console interface
- `layout.css`: Application layout and navigation
- `node.css`: Bifrost node status display
- `prompt.css`: NIP-55 prompt dialogs
- `scanner.css`: QR code scanning interface
- `sessions.css`: Session management (legacy)
- `settings.css`: Configuration interfaces

## Service Worker (`sw.ts`)

PWA functionality including:
- **Offline Support**: Resource caching strategies
- **Background Sync**: Message queuing and retry logic
- **Install Management**: PWA installation handling
- **Update Notifications**: Version management

## Development Guidelines

### Adding New Components
1. Create component in appropriate subdirectory
2. Add corresponding CSS file if needed
3. Export from directory index if applicable
4. Update types in `types.ts` if new interfaces are needed
5. Follow existing patterns for context integration

### Adding New Context Providers
1. Create provider in `context/` directory
2. Define API interface in `types.ts`
3. Add to provider hierarchy in `index.tsx`
4. Export hook with proper error handling

### Adding NIP-55 Operations
1. Add request type to `types.ts`
2. Update URL parsing in `lib/nip55.ts`
3. Add handler in prompt system
4. Update permission system if needed

### TypeScript Best Practices
- Use strict type checking
- Prefer interfaces over types for object shapes
- Export types that may be used elsewhere
- Use `ReactElement` for component return types
- Leverage union types for state enums

### State Management Patterns
- Use Context API for global state
- Keep local state in components when possible
- Use custom hooks for complex state logic
- Prefer reactive patterns with subscriptions

### Import Conventions
- Use path aliases (`@/`) for internal imports
- Import types with `import type`
- Use `.js` extensions for all relative imports
- Group imports: React, third-party, internal

## Security Considerations

### NIP-55 Security
- All requests validated against TypeScript types
- Permission system prevents unauthorized operations
- User consent required for all signing operations
- Secure callback URL handling

### Cryptographic Operations
- Noble crypto libraries for all cryptographic functions
- Secure key storage through Android bridge
- Proper entropy for key generation
- Constant-time operations where applicable

### Data Protection
- No sensitive data in localStorage without encryption
- Android secure storage for credentials
- Proper cleanup of sensitive data from memory
- CORS handling for cross-origin requests

## Integration Notes

### Android Bridge
- Secure storage API through `window.AndroidSecureStorage`
- Session persistence through `window.androidSessionPersistence`
- Logging bridge for debugging
- Device information access

### PWA Features
- Service worker registration and updates
- Offline capability with fallbacks
- Install prompt management
- Background sync for message queuing

### Build Integration
- ESM modules with proper extensions
- CSS imported at application level
- TypeScript strict mode
- Path alias resolution

This architecture provides a robust foundation for the Igloo signing device with clear separation of concerns, type safety, and extensibility for future NIP-55 and FROSTR features.