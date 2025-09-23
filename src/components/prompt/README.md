# NIP-55 Prompt System

This prompt system handles NIP-55 signing requests from external applications, providing user-friendly interfaces for approving or denying cryptographic operations.

## Architecture

### Components

1. **PromptManager** (`index.tsx`) - Main component that renders both action and event prompts
2. **ActionPrompt** (`action.tsx`) - Handles non-signing operations (encrypt, decrypt, get_public_key)
3. **EventPrompt** (`event.tsx`) - Handles event signing requests
4. **PromptProvider** (`context/prompt.tsx`) - Context provider for managing prompt state

### Key Features

- **Permission Memory**: Users can choose to remember their decision for future requests
- **Security Warnings**: High-risk event types show warning notices
- **Content Preview**: Shows encrypted/decrypted content and event details
- **Callback Support**: Sends results to callback URLs or copies to clipboard
- **Loading States**: Provides feedback during cryptographic operations

## Usage

### 1. Setup Providers

Add the PromptProvider to your app:

```tsx
import { PromptProvider } from '@/context/prompt'
import { PromptManager } from '@/components/prompt'

function App() {
  return (
    <PromptProvider>
      {/* Your app content */}
      <PromptManager />
    </PromptProvider>
  )
}
```

### 2. Trigger Prompts

Use the prompt context to show signing requests:

```tsx
import { usePrompt } from '@/context/prompt'

function YourComponent() {
  const prompt = usePrompt()

  const handleNIP55Request = (request: NIP55Request) => {
    prompt.showPrompt(request)
  }
}
```

### 3. NIP-55 Request Structure

```typescript
// Example sign_event request
const signRequest: SignEventRequest = {
  type: 'sign_event',
  host: 'example.com',
  event: {
    kind: 1,
    content: 'Hello, Nostr!',
    tags: []
  },
  callbackUrl: 'https://example.com/result',
  id: 'request-123'
}

// Example encrypt request
const encryptRequest: EncryptRequest = {
  type: 'nip04_encrypt',
  host: 'example.com',
  plaintext: 'Secret message',
  pubkey: 'recipient_pubkey_hex',
  callbackUrl: 'https://example.com/result'
}
```

## Supported NIP-55 Operations

### Actions (ActionPrompt)
- `get_public_key` - Share the wallet's public key
- `nip04_encrypt` - Encrypt message using NIP-04
- `nip04_decrypt` - Decrypt message using NIP-04
- `nip44_encrypt` - Encrypt message using NIP-44
- `nip44_decrypt` - Decrypt message using NIP-44
- `decrypt_zap_event` - Decrypt zap event (placeholder)

### Events (EventPrompt)
- `sign_event` - Sign any Nostr event with detailed preview

## Permission System

### Automatic Permission Checking
The system automatically checks existing permissions before showing prompts:
- If user previously allowed/denied with "remember", that choice is applied
- Only new requests or non-remembered requests show prompts

### Permission Storage
Permissions are stored in `settings.data.perms` as:
- **Action permissions**: Host + action type
- **Event permissions**: Host + event kind

### High-Risk Events
The following event kinds show security warnings:
- Kind 0: Profile Metadata
- Kind 3: Contact Lists
- Kind 10002: Relay List Metadata
- Kind 23195: Wallet Requests
- Kind 24133: Nostr Connect

## Callback Handling

### With Callback URL
Results are sent to the provided callback URL with parameters:
- `result`: The operation result (signature, encrypted text, etc.)
- `event`: Full signed event (for sign_event only)
- `id`: Request ID (if provided)

### Without Callback URL
Results are copied to the system clipboard as fallback.

## Security Features

- **Content Preview**: Users can see what they're signing/encrypting
- **Host Identification**: Clear display of requesting application
- **Risk Warnings**: Alerts for dangerous operations
- **Permission Memory**: Reduces prompt fatigue while maintaining security
- **Dismiss Option**: Users can cancel without action

## Styling

The prompt system uses:
- **CSS File**: `src/styles/prompt.css`
- **Global Styles**: Inherits from existing design system
- **Responsive**: Adapts to mobile screens
- **Accessible**: Proper focus management and keyboard navigation

## Integration Notes

- Requires BifrostNode context for cryptographic operations
- Requires Settings context for permission storage
- CSS must be included in the build system
- Works with existing permission management UI