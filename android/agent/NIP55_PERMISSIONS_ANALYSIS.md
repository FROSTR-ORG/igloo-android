# NIP-55 Permissions System Analysis Report

## Executive Summary

The current NIP-55 permissions system in Igloo lacks critical features required for proper Amethyst integration. The primary gaps are:

1. **No event kind filtering** - Cannot restrict `sign_event` permissions to specific event types (e.g., only allow kind 22242 for relay auth)
2. **No bulk permission insertion** - Cannot process permission arrays from `get_public_key` requests
3. **No permission parsing from get_public_key** - Android handler captures but doesn't parse/store the permissions array
4. **Missing permissions UI** - No settings component to view/manage NIP-55 permissions

## Current State Analysis

### Permission Data Structure

**Location**: `src/types/permissions.ts` (inferred from usage)

```typescript
interface SimplePermissionRule {
  appId: string      // e.g., "com.vitorpamplona.amethyst"
  type: string       // e.g., "sign_event", "nip04_encrypt"
  allowed: boolean   // true/false
  timestamp: number  // Unix timestamp
  // MISSING: kind?: number
}
```

**Storage**: localStorage key `nip55_permissions` as JSON array

**Problem**: No `kind` field means all event signing is all-or-nothing. Cannot grant "sign only relay auth events" permission.

### Permission Checking Logic

**Files**:
- `src/lib/signer.ts:13-41` (auto-signing check)
- `android/app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt:439-461` (Android check)
- `android/app/src/main/kotlin/com/frostr/igloo/IglooContentProvider.kt:313-340` (Content Resolver check)

**Current Logic**:
```typescript
const permission = stored_permissions.find((p: any) =>
  p.appId === request.host && p.type === request.type
  // MISSING: && (p.kind === undefined || p.kind === request.event?.kind)
)
```

**Problem**: Only checks `appId + type`, ignoring event kind entirely.

### Permission API

**Location**: `src/context/permissions.tsx`

**Methods**:
```typescript
has_permission(host: string, type: string): Promise<boolean>
set_permission(host: string, type: string, allowed: boolean): Promise<void>
revoke_permission(host: string, type: string): Promise<void>
list_permissions(): Promise<Permission[]>
```

**Problems**:
- No `kind` parameter
- No bulk operations
- No way to set multiple permissions atomically

### Android Permission Parsing

**Location**: `android/app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt:316-322`

```kotlin
"get_public_key" -> {
    intent.getStringExtra("permissions")?.let { params["permissions"] = it }
    // PROBLEM: Captured but never parsed or stored
}
```

**NIP-55 Spec** (from `docs/NIP-55.md:118-129`):
```kotlin
val permissions = listOf(
    Permission(
        type = "sign_event",
        kind = 22242  // Optional kind filter
    ),
    Permission(
        type = "nip44_decrypt"  // No kind = applies to all
    )
)
intent.putExtra("permissions", permissions.toJson())
```

**Problem**: Android extracts permissions string but doesn't forward to PWA for storage.

### Prompt System

**Location**: `src/context/prompt.tsx:56-87`

```typescript
if (remember && request.host) {
  addPermissionRule(request.host, request.type, true)
  // PROBLEM: Not extracting event kind from request
}
```

**Problem**: When user approves with "remember", doesn't capture event kind for `sign_event` requests.

### UI Component

**Location**: Settings component not found

**Expected**: `src/components/settings/permissions.tsx` does not exist

**Problem**: No way for users to view, edit, or revoke NIP-55 permissions in the UI.

## Gap Analysis

### 1. Event Kind Filtering (HIGH PRIORITY)

**What's Missing**:
- `kind?: number` field in permission data structure
- Kind-aware permission matching logic
- Extraction of event kind from sign_event requests
- UI display of event kind in prompts and permission list

**Impact**: Cannot grant fine-grained permissions like "allow signing relay auth events only". Amethyst expects this per NIP-55 spec.

**Affected Files**:
- `src/types/permissions.ts` - Add kind field
- `src/lib/signer.ts` - Extract kind from request.event, match on kind
- `src/context/permissions.tsx` - Add kind parameter to all methods
- `src/context/prompt.tsx` - Extract and save kind when user approves
- `android/.../InvisibleNIP55Handler.kt` - Extract kind for Android checks
- `android/.../IglooContentProvider.kt` - Extract kind for Content Resolver checks

### 2. get_public_key Permission Parsing (HIGH PRIORITY)

**What's Missing**:
- Android-side parsing of permissions JSON array
- Forwarding permissions to PWA for storage
- PWA handler for bulk permission insertion
- **Dedicated bulk permission prompt component** (separate from standard prompts)
- User confirmation prompt for bulk permissions

**Impact**: Amethyst's permission workflow is broken. Permissions sent in get_public_key are silently ignored.

**Affected Files**:
- `android/.../InvisibleNIP55Handler.kt:316-322` - Parse permissions JSON
- `android/.../MainActivity.kt` - Forward permissions to PWA (new handler)
- `src/lib/permissions.ts` - Add `bulk_add_permissions()` function
- `src/context/permissions.tsx` - Add `bulk_set_permissions()` method
- **New component: `src/components/prompt/bulk-permission-prompt.tsx`** - Dedicated window component for bulk approval (distinct from standard NIP-55 prompts)

### 3. Bulk Permission Operations (HIGH PRIORITY)

**What's Missing**:
- `bulk_set_permissions(rules: SimplePermissionRule[]): Promise<void>` method
- Atomic storage update for multiple permissions
- Deduplication logic (don't create duplicates)

**Impact**: Cannot efficiently process Amethyst's permission list.

**Affected Files**:
- `src/lib/permissions.ts` - Add bulk operations
- `src/context/permissions.tsx` - Expose bulk API
- `src/types/prompt.ts` - Add to PermissionAPI interface

### 4. Permissions UI Component (MEDIUM PRIORITY)

**What's Missing**:
- List view of all granted permissions
- Filter/search by app or operation type
- Revoke button for each permission
- Visual indication of event kind filtering
- Design/styling

**Impact**: Users cannot manage permissions, creating trust and security concerns.

**New Files Needed**:
- `src/components/settings/permissions.tsx` - Permission list component
- `src/styles/permissions.css` - Styling for permission list

## Proposed Architecture

### Enhanced Permission Data Structure

```typescript
interface SimplePermissionRule {
  appId: string        // Calling app package name
  type: string         // NIP-55 operation type
  kind?: number        // Optional: event kind filter (undefined = all kinds)
  allowed: boolean     // Permission granted/denied
  timestamp: number    // When permission was granted
}
```

**Matching Logic**:
1. Match `appId` and `type` first
2. If `type === "sign_event"`, check kind:
   - If `kind` is undefined → matches all events (wildcard)
   - If `kind` is defined → only matches events with that kind
3. Fall back to wildcard if no kind-specific match found

**Example Permissions**:
```json
[
  {
    "appId": "com.vitorpamplona.amethyst",
    "type": "sign_event",
    "kind": 22242,
    "allowed": true,
    "timestamp": 1234567890
  },
  {
    "appId": "com.vitorpamplona.amethyst",
    "type": "nip44_decrypt",
    "allowed": true,
    "timestamp": 1234567890
  }
]
```

This grants Amethyst:
- Sign only kind 22242 events (relay auth)
- Decrypt all NIP-44 messages

### Enhanced Permission API

```typescript
interface PermissionAPI {
  // Check permission (kind-aware)
  has_permission(host: string, type: string, kind?: number): Promise<boolean>

  // Set single permission
  set_permission(host: string, type: string, allowed: boolean, kind?: number): Promise<void>

  // Revoke permission (kind-aware)
  revoke_permission(host: string, type: string, kind?: number): Promise<void>

  // List all permissions (optionally filtered)
  list_permissions(host?: string): Promise<Permission[]>

  // NEW: Bulk insert permissions (atomic)
  bulk_set_permissions(rules: SimplePermissionRule[]): Promise<void>

  // NEW: Parse NIP-55 permissions array format
  parse_nip55_permissions(permissionsJson: string, appId: string): SimplePermissionRule[]
}
```

### get_public_key Flow with Permissions

**Step 1**: Amethyst sends get_public_key intent with permissions array

**Step 2**: InvisibleNIP55Handler parses request
```kotlin
"get_public_key" -> {
    val permissionsJson = intent.getStringExtra("permissions")
    if (permissionsJson != null) {
        params["permissions"] = permissionsJson
        params["permissions_require_approval"] = "true"
    }
}
```

**Step 3**: Launch MainActivity with special flag to show bulk permission prompt

**Step 4**: PWA displays bulk permission prompt:
```
Amethyst is requesting these permissions:

✓ Sign relay authentication events (kind 22242)
✓ Decrypt private messages (NIP-44)

[Approve All] [Approve Selected] [Deny]
```

**Step 5**: User approves → PWA calls `bulk_set_permissions()`

**Step 6**: Return public key to Amethyst

**Step 7**: Future Content Resolver requests auto-approve based on stored permissions

## Implementation Plan

### Phase 1: Core Data Structure (1-2 hours)

1. **Create New Type Definitions**
   - **Create fresh** `src/types/permissions.ts` with `kind: number | undefined` (required field, not optional for clarity)
   - **Update** `Permission` interface in `src/types/prompt.ts` with kind support
   - Add bulk methods to `PermissionAPI` interface
   - Add types for bulk permission prompt

2. **Build New Permission Storage System**
   - **Rewrite** `src/lib/permissions.ts` from scratch:
     - Kind-aware matching logic from the start
     - `bulk_add_permissions()` function
     - Proper deduplication logic
     - **No legacy compatibility code**

3. **Storage Reset Logic**
   - Add version flag to permission storage
   - Clear old permissions on first load with new system
   - Clean slate implementation

### Phase 2: Permission Checking (2-3 hours)

1. **Update PWA Permission Checking**
   - `src/lib/signer.ts:13-41` - Extract event kind, check kind-specific permissions
   - `src/context/permissions.tsx` - Add kind parameter to all methods
   - `src/context/prompt.tsx:56-87` - Extract kind from request when saving permission

2. **Update Android Permission Checking**
   - `android/.../InvisibleNIP55Handler.kt:439-461` - Extract kind from params, check kind-aware
   - `android/.../IglooContentProvider.kt:313-340` - Same for Content Resolver

3. **Testing**
   - Test kind-specific permissions work correctly
   - Test wildcard permissions still work
   - Test fallback to wildcard when kind-specific not found

### Phase 3: get_public_key Permission Parsing (3-4 hours)

1. **Android-side Parsing**
   - `InvisibleNIP55Handler.kt:316-322` - Parse permissions JSON array
   - Forward to MainActivity with flag `nip55_bulk_permissions_approval_required`

2. **PWA Bulk Permission Handler**
   - Create `src/lib/permissions.ts::parse_nip55_permissions()`
   - Add validation and deduplication
   - Implement `bulk_set_permissions()`

3. **Bulk Permission Prompt Component** (NEW PROMPT TYPE)
   - Create `src/components/prompt/bulk-permission-prompt.tsx` - **Dedicated component, separate from standard prompts**
   - **Full-screen modal window** (not a standard prompt overlay)
   - Display app requesting permissions with app name/package
   - List all requested permissions with clear descriptions
   - For `sign_event` permissions, show event kind with human-readable labels
   - Checkboxes for selective approval
   - "Approve All" / "Approve Selected" / "Deny All" action buttons
   - **Distinct visual design** from standard NIP-55 prompts to emphasize bulk nature

4. **Integration**
   - Wire up Android → PWA flow
   - Test with real Amethyst login

### Phase 4: Permissions UI (2-3 hours)

1. **Create Permissions Component**
   - `src/components/settings/permissions.tsx` - Main component
   - List all permissions grouped by app
   - Show operation type and event kind (if applicable)
   - Revoke button for each permission
   - "Revoke All for App" button

2. **Styling**
   - `src/styles/permissions.css` - Professional styling
   - Card-based layout
   - App icon display (if available)
   - Color coding for operation types

3. **Integration**
   - Add to `src/components/settings/index.tsx`
   - Test permission CRUD operations

### Phase 5: Testing & Polish (1-2 hours)

1. **End-to-End Testing**
   - Test full Amethyst login flow with permissions
   - Test Content Resolver auto-signing with kind filtering
   - Test manual prompt with kind display
   - Test permission revocation

2. **Edge Cases**
   - Multiple apps with same permissions
   - Duplicate permission requests
   - Invalid permission JSON
   - Bulk permission prompt with 10+ permissions (UI scrolling)

## File Modification Checklist

### TypeScript Files (PWA)

**src/types/permissions.ts** (NEW or UPDATE)
- Add `kind?: number` to `SimplePermissionRule`

**src/types/prompt.ts**
- Add `kind?: number` to `Permission` interface
- Add bulk methods to `PermissionAPI` interface

**src/lib/permissions.ts** (MAJOR REFACTOR)
- Add `kind` parameter to all functions
- Implement kind-aware matching
- Add `bulk_add_permissions()`
- Add `parse_nip55_permissions()`

**src/context/permissions.tsx** (MODERATE CHANGES)
- Add `kind` parameter to all methods
- Implement `bulk_set_permissions()`
- Update permission matching logic

**src/lib/signer.ts** (MODERATE CHANGES)
- Extract event kind from `request.event?.kind`
- Pass kind to permission check
- Update `check_permission()` signature

**src/context/prompt.tsx** (MINOR CHANGES)
- Extract kind from request when saving permission (line 73)
- Pass kind to `addPermissionRule()`

**src/components/settings/permissions.tsx** (NEW FILE)
- Create permissions list component
- Implement revoke functionality
- Add styling

**src/components/prompt/bulk-permission-prompt.tsx** (NEW FILE - DEDICATED COMPONENT)
- Create dedicated bulk permission approval window (separate from standard prompts)
- Full-screen modal with distinct visual design
- Display app information and requested permissions list
- Checkbox-based selective approval UI
- Event kind labels for sign_event permissions (e.g., "Relay Authentication (22242)")
- Handle approve all / approve selected / deny all actions
- Integration with bulk permission flow from `get_public_key`

**src/styles/permissions.css** (NEW FILE)
- Style permissions list

### Kotlin Files (Android)

**InvisibleNIP55Handler.kt** (MODERATE CHANGES)
- Parse permissions JSON in `parseNIP55RequestParams()` (line 320)
- Extract kind from event in `checkPermission()` (line 448)
- Add kind-aware permission matching

**IglooContentProvider.kt** (MINOR CHANGES)
- Extract kind from sign_event requests (line 324)
- Pass kind to permission check (line 324)

**MainActivity.kt** (MODERATE CHANGES)
- Add handler for bulk permission approval flow
- Forward permissions to PWA via JavaScript bridge

**Permission.kt** (Kotlin data class - check if exists, else add to InvisibleNIP55Handler.kt)
- Add `val kind: Int? = null` field

## Migration Strategy

**NO MIGRATION NEEDED** - Alpha status means clean slate:
- Existing permission storage will be **completely replaced** with new structure
- No backward compatibility concerns - we're in alpha
- Users will need to re-grant permissions after update (acceptable in alpha)
- **Clean implementation with no legacy code or tech debt**

**Storage Reset**:
- Clear existing `nip55_permissions` on first load with new system
- Fresh start with proper `kind` support from day one
- No dual-format support or compatibility shims

## Risk Assessment

**Low Risk**:
- Adding optional `kind` field (backward compatible)
- Adding bulk operations (new code paths)

**Medium Risk**:
- Permission matching logic changes (need thorough testing)
- Android-PWA communication for bulk permissions (new flow)

**High Risk**:
- None identified (changes are additive, not destructive)

## Estimated Timeline

- **Phase 1** (Data Structure): 1-2 hours
- **Phase 2** (Permission Checking): 2-3 hours
- **Phase 3** (get_public_key Parsing): 3-4 hours
- **Phase 4** (UI Component): 2-3 hours
- **Phase 5** (Testing): 1-2 hours

**Total**: 9-14 hours of development time

## Recommendations

1. **Implement in order** - Each phase builds on the previous
2. **Test thoroughly** - Permission systems are security-critical
3. **Document well** - Add JSDoc comments explaining kind matching logic
4. **Consider future expansion** - May want time-based permissions, one-time permissions, etc.
5. **User education** - Add help text explaining event kinds in the UI
6. **Clean implementation** - No legacy code, no tech debt, no migration compatibility layers

## Design Principles (Alpha Mindset)

**No Backward Compatibility Constraints**:
- Design the permission system correctly from the ground up
- Don't compromise architecture for legacy support
- Break things if needed - we're in alpha

**Clean Code Only**:
- No migration shims or compatibility layers
- No "old format" vs "new format" handling
- One clean implementation path

**User Impact**:
- Users will re-grant permissions after update (acceptable in alpha)
- Better to have clean foundation than carry forward tech debt
- Document breaking change in release notes

---

**Ready to proceed?** Implementation can start with Phase 1 (Core Data Structure) immediately, or modifications to this plan can be discussed first.
