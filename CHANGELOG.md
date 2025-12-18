# Changelog

All notable changes to this project will be documented in this file.

## [0.1.0]

Initial alpha release of Igloo Mobile.

### Features
- NIP-55 signing support for Nostr apps via `nostrsigner:` URI scheme
- FROSTR bifrost integration for distributed key management
- Permission management with granular event kind control
- Manual (prompt) and automatic (background) signing modes
- QR code scanning for FROSTR share import
- Secure storage using Android Keystore
- Welcome dialog for first-time users

### Technical
- PWA architecture wrapped in Android shell
- WebSocket persistence via OkHttp
- CameraX integration for QR scanning
- React 19 with TypeScript
