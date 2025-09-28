# Code Style Guide

This document defines the coding style and conventions used throughout the current project. These guidelines ensure consistency, readability, and maintainability across the codebase.

## Overview

This project follows a **functional programming** approach with strict TypeScript conventions. The style emphasizes clarity, type safety, and domain-specific terminology for Nostr protocol and Lightning Network development.

## Naming Conventions

### Functions and Variables
- **Format**: `snake_case` (lowercase with underscores)
- **Example Functions**: `get_relay_status()`, `process_nostr_event()`, `validate_lightning_payment()`
- **Example Variables**: `event_count`, `relay_url`, `payment_hash`, `subscription_id`

### Constants
- **Format**: `SCREAMING_SNAKE_CASE` (uppercase with underscores)
- **Example Constants**: `EVENT_KINDS`, `DEFAULT_RELAYS`, `ZAP_TIMEOUT_MS`, `MAX_RECONNECT_ATTEMPTS`
- **Object Constants**: Use nested objects for grouping related constants

```typescript
export const EVENT_KINDS = {
  TEXT_NOTE    : 1,
  ENCRYPTED_DM : 4,
  ZAP_REQUEST  : 9734,
  ZAP_RECEIPT  : 9735
} as const
```

### Types and Interfaces
- **Format**: `PascalCase`
- **Interface Names**: `NostrEvent`, `RelayConnection`, `ZapRequest`, `PhoenixdInvoice`
- **Type Aliases**: `EventKind`, `RelayStatus`
- **Generic Types**: Single uppercase letters (`S`, `T`) or descriptive names

### Files and Directories
- **Files**: `snake_case.ts` (e.g., `event_handler.ts`, `relay_monitor.ts`, `payment_tracker.ts`)
- **Directories**: `snake_case` or domain-specific (e.g., `nostr/`, `phoenix/`, `chatbot/`)
- **Index Files**: Always named `index.ts` in each directory

### Domain-Specific Abbreviations
Consistent use of Nostr and Lightning Network abbreviations:
- `pubkey` - Public Key (Nostr identity)
- `msats` - Millisatoshis (Lightning amount unit)
- `lnurl` - Lightning URL Protocol
- `zap` - Lightning payment on Nostr
- `dm` - Direct Message (encrypted)
- `sig` - Signature
- `ws` - WebSocket (relay connection)
- `nip` - Nostr Implementation Possibility
- `bolt11` - Lightning payment request format

### Parameter Formatting
- **Multi-line**: When functions have multiple parameters, format with alignment
- **Default Values**: Place optional parameters with defaults at the end
- **Type Annotations**: Always explicit, even when TypeScript can infer

```typescript
export function process_nostr_event (
  event       : NostrEvent,
  relay_url   : string,
  validate    : boolean = true
) : MentionEvent | null {
  // Implementation
}
```

### Return Types
- **Explicit**: Always specify return types, even for simple functions
- **Nullable**: Use `| null` for functions that may not return a value
- **Assertion Functions**: Use TypeScript assertion signatures

```typescript
export function assert_nostr_event (
  event: unknown
) : asserts event is NostrEvent {
  // Implementation
}
```

## Import and Export Patterns

### Import Organization
1. **External Dependencies**: Third-party libraries first
2. **Internal Libraries**: Local utilities and constants
3. **Type Imports**: Separated with `type` keyword
4. **Namespace Imports**: Use `* as` for schema and utility modules

```typescript
import { getPublicKey }     from 'nostr-tools'
import { EVENT_KINDS }      from '../const.js'
import { get_relay_status } from './relay_monitor.js'
import { validate_event }   from './validation.js'

import * as SCHEMA from '../schema/index.js'

import type {
  NostrEvent,
  RelayConnection,
  MentionEvent,
  ZapRequest,
  PhoenixdInvoice
} from '../types/index.js'
```

### Vertical Alignment in Imports
- **Strict Alignment**: All import statements must be vertically aligned on the `from` keyword
- **Consistent Spacing**: Use spaces to align the `from` keyword across all imports in a block
- **Grouping**: Maintain alignment within each import group (external, internal, types)

### Export Patterns
- **Named Exports**: Preferred over default exports
- **Re-exports**: Use `export *` in index files for flat APIs
- **Namespace Exports**: Use `export * as` for schema modules

```typescript
// Flat re-exports (lib/index.ts, types/index.ts)
export * from './event_handler.js'
export * from './relay_monitor.js'

// Namespaced exports (schema/index.ts)
export * as nostr from './nostr_operations.js'
export * as phoenix from './phoenix_operations.js'
```

### File Extensions
- **Imports**: Always use `.js` extension for ES module compatibility
- **Path Aliases**: Use `@/` for internal references, resolved by build system

## TypeScript Conventions

### Type Definitions
- **Interfaces**: For object shapes and API contracts
- **Type Aliases**: For unions, primitives, and computed types
- **Const Assertions**: Use `as const` for literal type inference

```typescript
export type EventKind = typeof EVENT_KINDS[keyof typeof EVENT_KINDS]

export const DEFAULT_RELAYS = [
  'wss://relay.damus.io',
  'wss://nos.lol',
  'wss://relay.snort.social',
  'wss://relay.nostr.band'
] as const
```

### Generic Constraints
- **Zod Integration**: Use `z.ZodTypeAny` for schema validation functions
- **Satisfies Operator**: Ensure schema-type alignment

```typescript
export const nostr_event = z.object({
  id         : z.string(),
  pubkey     : z.string(),
  created_at : z.number(),
  kind       : z.number(),
  content    : z.string()
}) satisfies z.ZodType<NostrEvent>
```

## Variable Declarations

### Declaration Style
- **Const Preference**: Use `const` by default, `let` only when reassignment needed
- **Multiple Declarations**: Use individual declarations for clarity

```typescript
// Preferred
const event_count     = 0
const relay_connected = false
const zap_amount_sats = 0

// Avoid (except for related values)
let subscription_count = 0
  , active_relays      = 0
  , pending_events     = 0
```

### Destructuring
- **Object Destructuring**: Extract properties with original names or meaningful aliases
- **Array Destructuring**: Use for tuples and known-length arrays

```typescript
// Object destructuring with rest
const { tags: _, ...event } = nostr_event

// Array destructuring for tuples
const [ event_id, relay_url ] = subscription_data
```

## Comments and Documentation

### JSDoc Comments
- **Required**: For public API functions, especially complex operations
- **Format**: Standard JSDoc with parameter descriptions

```typescript
/**
 * Process a Nostr mention event and extract relevant data.
 * @param event : A NostrEvent object containing the mention.
 * @returns MentionEvent or null if invalid
 */
```

### Inline Comments
- **Business Logic**: Explain the "why" rather than the "what"
- **Complex Operations**: Break down multi-step processes
- **Domain Context**: Clarify Bitcoin-specific operations

```typescript
// Subscribe to mention events for the bot's pubkey.
relay.subscribe(mention_filter)

// If the event is a zap receipt, process the payment.
if (event.kind === EVENT_KINDS.ZAP_RECEIPT) process_zap(event)
```

### Comment Style
- **Single Line**: Use `//` with a space
- **Sentence Case**: Start with capital letter, end with period for complete sentences
- **No Trailing Periods**: For phrase-style comments

## Error Handling

### Assertion Pattern
- **Library**: Use `@vbyte/micro-lib/assert` for all assertions
- **Error Messages**: Descriptive with relevant values included
- **Validation**: Combine with Zod schemas for runtime type checking

```typescript
Assert.ok(event.sig, `missing signature for event: ${event.id}`)
Assert.exists(relay_connection, `relay connection not found: ${relay_url}`)
```

### Schema Validation
- **Zod Integration**: Use consistent schema validation patterns
- **Error Handling**: Provide meaningful error messages for validation failures

```typescript
export function parse_nostr_event<S extends z.ZodTypeAny>(
  input  : unknown,
  schema : S,
  error? : string
): z.infer<S> {
  try {
    return schema.parse(input)
  } catch {
    throw new Error(error ?? `invalid nostr event format`)
  }
}
```

## Constants and Configuration

### Numeric Constants
- **Underscores**: Use underscores in large numbers for readability
- **Bitcoin Values**: Follow Bitcoin convention (satoshis as base unit)

```typescript
export const MSATS_PER_SAT        = 1000
export const ZAP_TIMEOUT_MS       = 30_000
export const MAX_RECONNECT_ATTEMPTS = 5
```

### Object Constants
- **Nested Structure**: Group related constants in objects
- **Const Assertions**: Ensure literal type inference
- **Descriptive Keys**: Use clear, unambiguous names

```typescript
export const NOSTR_EVENT_MAX_SIZE = 65_536
export const RELAY_PING_INTERVAL  = 30_000

export const TIMEOUTS = {
  CONNECTION : {
    RELAY_MS     : 10_000,
    LIGHTNING_MS : 15_000
  },
  SUBSCRIPTION : {
    NOSTR_MS : 30_000,
    ZAP_MS   : 60_000
  }
} as const
```

## Module Organization

### Directory Structure
- **Domain-Driven**: Each domain concept gets its own module
- **Modular Pattern**: `types/`, `lib/`, `api/`, `schema/` for each domain
- **Specialized Modules**: `nostr/`, `phoenix/`, `chatbot/`, `webserver/` for specific concerns

### Index Files
- **Public API**: Each directory should have an `index.ts` defining the public interface
- **Re-export Strategy**: Flat for implementation, namespaced for schemas
- **Consistency**: Follow the same pattern across all modules

```typescript
// lib/index.ts - Flat exports
export * from './event_handler.js'
export * from './relay_monitor.js'

// schema/index.ts - Namespaced exports
export * as nostr from './nostr_operations.js'
export * as phoenix from './phoenix_operations.js'
```

## Formatting Preferences

### Line Length
- **Target**: 80-100 characters per line
- **Breaking**: Break long function signatures across multiple lines
- **Alignment**: Align parameters and properties for readability

### Indentation
- **Style**: Two spaces (no tabs)
- **Consistency**: All code blocks, object properties, and nested structures use 2-space indentation
- **Continuation Lines**: Use 2 additional spaces for continuation lines in multi-variable declarations

### Vertical Alignment and Spacing

#### Object Properties and Interface Members
- **Strict Vertical Alignment**: All colons (`:`) must be aligned vertically
- **Consistent Spacing**: Use spaces before colons to achieve alignment
- **Property Grouping**: Maintain alignment across all properties in an object/interface

```typescript
export interface NostrEvent {
  id         : string
  pubkey     : string
  created_at : number
  kind       : number
  content    : string
}

export interface RelayConnection {
  url                 : string
  ws                  : WebSocket | null
  status              : 'connected' | 'disconnected'
  last_ping           : number
  reconnect_attempts  : number
}
```

#### Function Parameters
- **Colon Alignment**: Parameter type annotations should be vertically aligned
- **Multi-line Parameters**: When parameters span multiple lines, align colons

```typescript
export function subscribe_to_mentions(
  relay_url   : string,
  bot_pubkey  : string,
  since_time  : number = Date.now()
): SubscriptionResult {
  // Implementation
}
```

#### Variable Declarations
- **Multi-variable Alignment**: When declaring multiple related variables, align colons and values

```typescript
// Initialize the connection state.
let relay_count      = 0
  , active_subs      = 0
  , pending_events   = 0

// Object with 2-space indentation and alignment
export default {
  TIMEOUTS : {
    CONNECTION_MS   : 10_000,
    SUBSCRIPTION_MS : 30_000,
    ZAP_REQUEST_MS  : 60_000,
    HEALTH_CHECK_MS : 5_000,
  },
  LIMITS : {
    MAX_RELAYS      : 20,
    MAX_SUBS        : 100,
    EVENT_CACHE_SIZE: 1_000,
  }
}
```

### Spacing Rules
- **Function Calls**: No space between function name and parentheses
- **Operators**: Spaces around operators (`=`, `+`, `>=`, etc.)
- **Object Literals**: Spaces after colons, around braces
- **Colon Spacing**: Space before and after colon in type annotations and object properties

### Array and Object Formatting
- **Short Arrays**: Single line when under 3 elements
- **Long Arrays**: Multi-line with trailing comma
- **Objects**: Multi-line formatting with vertical alignment

```typescript
// Short array
const event_kinds = [1, 4, 7]

// Aligned object properties
const relay_config = {
  url          : 'wss://relay.damus.io',
  timeout_ms   : 10_000,
  max_retries  : 3,
  ping_interval: 30_000
}
```

## Build System Considerations

### Path Aliases
- **Development**: Use `@/` aliases for cleaner imports
- **Build Output**: Custom script resolves to relative paths
- **Consistency**: Apply aliases consistently across the codebase

### ES Module Compatibility
- **File Extensions**: Always include `.js` in imports
- **Type Imports**: Separate type-only imports with `type` keyword
- **JSON Imports**: Use import assertions for JSON files

```typescript
import DEFAULT_RELAYS from './const/relays.json' with { type: 'json' }
import type { NostrEvent } from '../types/index.js'
```

## Linting and Formatting Rules

### Biome Configuration
Based on the existing `biome.json`:
- **Linting**: Enabled with strict rules
- **Formatting**: Disabled (manual formatting preferred)
- **Unused Imports**: Error level enforcement
- **Unused Variables**: Error level enforcement

### Recommended Rules
For generating linting configurations:
- Enforce snake_case for functions and variables
- Enforce PascalCase for types and interfaces
- Enforce SCREAMING_SNAKE_CASE for constants
- Require explicit return types on functions
- Prefer `const` over `let`
- Require `.js` extensions in imports
- Enforce consistent comment styles
- Require JSDoc for exported functions
- **Enforce 2-space indentation (no tabs)**
- **Enforce vertical alignment of colons in interfaces, objects, and function parameters**
- **Enforce vertical alignment of `from` keyword in import statements**
- **Require consistent spacing for colon alignment**

### Vertical Alignment Rules for Automated Tools
- **Import Alignment**: All `from` keywords in consecutive import statements must be vertically aligned
- **Interface Properties**: All colons in interface property declarations must be vertically aligned
- **Object Literals**: All colons in object literal properties must be vertically aligned
- **Function Parameters**: All colons in multi-line function parameter lists must be vertically aligned
- **Variable Declarations**: Related variable declarations should align assignment operators when on consecutive lines

This style guide ensures consistency across the DUCAT Core Library and serves as the foundation for automated linting and formatting rules.