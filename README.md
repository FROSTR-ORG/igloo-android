# Igloo Mobile

A NIP-55 android signing device for Nostr, powered by the FROSTR protocol.

## Overview

Igloo is a mobile signing device for nostr-powered applications. It uses NIP-55 to communicate with other nostr apps, and the FROSTR protocol to sign messages.

FROSTR is a protocol for multiple devices to coordinate and sign messages over a nostr relay. Instead of storing your private key on a single device, FROSTR splits it across multiple signing nodes - Igloo acts as one of those nodes.

## Features

- **NIP-55 Signing**: Handles `nostrsigner:` URI requests from other Nostr apps
- **FROSTR Integration**: Connects to your FROSTR network via a bifrost node
- **Permission Management**: Granular control over which apps can sign which event kinds
- **Manual & Auto Signing**: Choose between prompt-based approval or background signing
- **QR Code Setup**: Scan your FROSTR share to get started quickly

## Architecture

Igloo mobile is built as a PWA (Progressive Web App) wrapped in an Android shell:

```
┌─────────────────────────────────────────┐
│           Android Shell                 │
│  ┌───────────────────────────────────┐  │
│  │  WebView (renders PWA)            │  │
│  │  ┌─────────────────────────────┐  │  │
│  │  │     React PWA               │  │  │
│  │  │  - Bifrost node             │  │  │
│  │  │  - NIP-55 handler           │  │  │
│  │  │  - Permission manager       │  │  │
│  │  └─────────────────────────────┘  │  │
│  └───────────────────────────────────┘  │
│                                         │
│  Native Bridges:                        │
│  - Secure Storage (Android Keystore)    │
│  - WebSocket (OkHttp)                   │
│  - Camera (CameraX)                     │
│  - NIP-55 Intent Handler                │
└─────────────────────────────────────────┘
```

The PWA can be run independently in the web browser, or bundled within an android application.

## Project Structure

```
├── android/          # Android app shell (Kotlin)
├── dist/             # Build output
├── docs/             # Additional documentation
├── public/           # Static assets (index.html, icons)
├── src/              # PWA source code (TypeScript/React)
│   ├── components/   # React components
│   ├── context/      # React context providers
│   ├── hooks/        # Custom hooks (useBifrost, etc.)
│   ├── lib/          # Core libraries (cipher, permissions, etc.)
│   ├── class/        # TypeScript classes (signer, store)
│   ├── styles/       # CSS files
│   └── types/        # TypeScript type definitions
└── script/           # Build and utility scripts
```

## Development

### Getting Started

Before starting development, you need the following:

- Node.js 18+
- npm
- Android Studio (for Android development)
- ADB (for device testing)

You will also need to install dependencies:

```bash
# Install dependencies
npm install
```

### Development Tools

There are a number of scripts and tools to use:

```bash
# Spin up a nostr relay and peer client.
# Use --ngrok to enable ngrok support.
npm run dev 

# Run an emulated android device on your machine.
npm run emulator

# Generate a set of keys (saved to keyset.json),
# or display existing keys (with scannable QR codes).
npm run keygen

# Run a local ephemeral nostr relay for testing.
npm run relay

 # Host the PWA on a webserver with hot reload.
npm run serve
```

### Debugging

To monitor application logs over adb:

```bash
# Monitor Android logs
adb logcat -s "SecureIglooWrapper:*" "InvisibleNIP55Handler:*"
```

### Building

There are also a number of scripts for builds and releases:

```bash
# Build the PWA from the `/src` folder.
npm run build

# Build the debug APK (includes PWA build and asset copy).
npm run build:debug

# Build and install debug APK to connected device.
npm run build -- --debug --install

# Build and sign the release APK.
npm run build:release

# Build and install release APK to connected device.
npm run build -- --release --install

# Create a new github release using github actions.
npm run release
```

**Note**: Always use `npm run build:debug` or `npm run build:release` instead of running `./gradlew` directly. The build script ensures PWA assets are copied to the Android project before building.

## Documentation

- `docs/NIP-55.md` - NIP-55 protocol details
- `docs/DEVELOPMENT.md` - Extended development guide
- `android/README.md` - Android-specific documentation

## License

MIT
