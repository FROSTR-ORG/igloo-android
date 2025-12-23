# Changelog

All notable changes to this project will be documented in this file.

## [0.1.2]

- Updated copy on welcome message.
- New architecture. Fixed a lot of issues with signing.

## [0.1.1]

- Added a number of improvements to request de-duplication, rate-limiting, message-queuing, and connection failure detection / recovery.

## [0.1.0]

Initial alpha release of Igloo Mobile.

### Features
- NIP-55 signing support for Nostr apps via `nostrsigner:` URI scheme
- FROSTR bifrost integration for distributed key management
- Permission management with granular event kind control
- Manual (prompt) and automatic (background) signing modes
- QR code scanning for FROSTR share import
- Secure storage using Android Keystore
