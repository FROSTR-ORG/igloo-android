# PWA Codebase Tech Debt Analysis

**Date**: 2025-10-04
**Scope**: `/home/cscott/Repos/frostr/pwa/src/`
**Context**: Alpha PWA for NIP-55 Android signing with native permission dialogs

---

## Executive Summary

This analysis identified **~1,200 lines of legacy code** (29% of the 4,151 line codebase) that can be removed, including:

- **622 lines** of PWA prompt UI components (obsolete - native Android handles all prompts)
- **~200 lines** of stub/legacy permission code in contexts and types
- **537 lines** of unused sessions.css
- Multiple deprecated type definitions and documentation

**Key Finding**: The architecture contradicts itself - Android's `NIP55PermissionDialog.kt` handles ALL permission prompts, yet the PWA maintains a complete prompt UI system that will never be used.

---

## Critical Issues (Breaks Current Architecture)

### 1. **Dual Permission Systems**
**Impact**: HIGH - Architectural contradiction

**Problem**: The codebase maintains TWO complete permission prompt systems:

**Android (Active)**:
- `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/NIP55PermissionDialog.kt` (312 lines)
- `/home/cscott/Repos/frostr/pwa/android/app/src/main/kotlin/com/frostr/igloo/InvisibleNIP55Handler.kt`
  - `showBulkPermissionDialog()` (line 179)
  - `showSinglePermissionDialog()` (line 243)

**PWA (Dead Code)**:
- `/home/cscott/Repos/frostr/pwa/src/components/prompt/action.tsx` (172 lines)
- `/home/cscott/Repos/frostr/pwa/src/components/prompt/event.tsx` (193 lines)
- `/home/cscott/Repos/frostr/pwa/src/components/prompt/index.tsx` (12 lines)
- `/home/cscott/Repos/frostr/pwa/src/context/prompt.tsx` (245 lines)

**Evidence from `InvisibleNIP55Handler.kt`**:
```kotlin
// Line 67: Bulk permission = NATIVE DIALOG
showBulkPermissionDialog()

// Line 90: Single permission = NATIVE DIALOG
showSinglePermissionDialog()
```

The PWA prompt components will **never be invoked** in the current architecture.

### 2. **Stub Functions Masking Dead Code**
**Impact**: MEDIUM - Misleading architecture

**Location**: `/home/cscott/Repos/frostr/pwa/src/context/prompt.tsx`

```typescript
// Lines 211-221: Stub functions that warn but do nothing
const bulkPermissionState = {
  isOpen: false,
  request: null
}

const showBulkPermissionPrompt = async () => {
  console.warn('showBulkPermissionPrompt called - should be handled by native Android')
  return { approved: false }
}
```

**Issue**: These stubs are exported in the API but can never succeed. They exist only to satisfy TypeScript interfaces, creating a false sense of functionality.

### 3. **Global State Pollution**
**Impact**: MEDIUM - Memory leak risk

**Location**: `/home/cscott/Repos/frostr/pwa/src/context/prompt.tsx`

```typescript
// Lines 62, 93, 130, 141, 174, 193: Window pollution for unused prompts
const resolve = (window as any).__promptResolve
(window as any).__promptResolve = resolve
delete (window as any).__promptResolve
```

The manual prompt system stores resolver functions on `window` object, which is unnecessary since Android handles all prompts natively.

---

## Legacy Code to Delete

### Category 1: PWA Prompt UI (622 lines total)

#### Components
- ✂️ `/home/cscott/Repos/frostr/pwa/src/components/prompt/action.tsx` (172 lines)
  - Handles encrypt/decrypt/get_public_key prompts
  - Has complete UI with loading states, remember checkbox, content preview
  - **Never shown** - Android native dialog used instead

- ✂️ `/home/cscott/Repos/frostr/pwa/src/components/prompt/event.tsx` (193 lines)
  - Handles sign_event prompts
  - Event kind labels, security warnings, content preview
  - **Never shown** - Android native dialog used instead

- ✂️ `/home/cscott/Repos/frostr/pwa/src/components/prompt/index.tsx` (12 lines)
  - PromptManager wrapper component
  - **Unused** - exports dead components

#### Context Provider
- ✂️ `/home/cscott/Repos/frostr/pwa/src/context/prompt.tsx` (245 lines)
  - Contains: approve(), deny(), dismiss(), showPrompt()
  - Window pollution: `__promptResolve`
  - Stub bulk permission functions
  - **Partially obsolete** - only permission storage functions needed

**Keep (refactor into minimal module)**:
- Permission storage logic (lines 72-88, 145-157)
- These ~30 lines should move to `/home/cscott/Repos/frostr/pwa/src/lib/permissions.ts`

### Category 2: Prompt Types & Interfaces

#### Type Definitions
- ✂️ `/home/cscott/Repos/frostr/pwa/src/types/prompt.ts` - **Partially obsolete**
  ```typescript
  // Lines 3-33: DELETE - PWA prompt UI types
  export type PromptStatus = 'pending' | 'approved' | 'denied'
  export type PermissionType = 'action' | 'event'
  export type PermissionMode = 'prompt' | 'automatic' | 'denied'

  export interface PromptState { ... }
  export interface BulkPermissionState { ... }
  export interface BulkPermissionRequest { ... }  // DUPLICATE!
  export interface PromptAPI { ... }

  // Lines 35-52: KEEP - Permission storage types (used by lib/permissions.ts)
  export interface Permission { ... }
  export interface PermissionAPI { ... }
  ```

**Duplicate Issue**: `BulkPermissionRequest` defined in:
1. `/home/cscott/Repos/frostr/pwa/src/types/prompt.ts` (lines 14-23)
2. `/home/cscott/Repos/frostr/pwa/src/types/permissions.ts` (lines 51-60)

The `types/permissions.ts` version is canonical and used by the actual permission system.

### Category 3: Styling

- ✂️ `/home/cscott/Repos/frostr/pwa/src/styles/prompt.css` - **Need to verify size**
  - Imported in `index.tsx` (line 18)
  - Styles for `.prompt-overlay`, `.prompt-modal`, etc.
  - **Never rendered** - Android uses native dialogs

- ✂️ `/home/cscott/Repos/frostr/pwa/src/styles/sessions.css` (537 lines)
  - Imported in `index.tsx` (line 15)
  - Contains extensive session management UI styles
  - **No component uses these styles**
  - Only reference: `SessionsIcon` in icons.tsx which is **never imported**

### Category 4: Documentation

- ✂️ `/home/cscott/Repos/frostr/pwa/src/components/prompt/README.md` (149 lines)
  - Documents PWA prompt system that's replaced by Android
  - Outdated architecture description

- ✂️ `/home/cscott/Repos/frostr/pwa/src/components/permissions/README.md` (47 lines)
  - References old permission storage format
  - Mentions "PermActionRecord" and "PermEventRecord" which don't exist anymore

### Category 5: Deprecated Types

- ✂️ `/home/cscott/Repos/frostr/pwa/src/types/global.ts` (4 lines)
  ```typescript
  // Line 1: Explicitly marked deprecated
  // This file is deprecated - Window interface is now defined in bridge.ts
  ```

- ✂️ `/home/cscott/Repos/frostr/pwa/src/types/index.ts` (line 3)
  ```typescript
  export * from './prompt.js'  // Contains obsolete PromptAPI
  ```

### Category 6: Unused Icons

- ✂️ `/home/cscott/Repos/frostr/pwa/src/components/util/icons.tsx` - **Partial**
  ```typescript
  // SessionsIcon (lines ~40-50) - NEVER IMPORTED ANYWHERE
  export const SessionsIcon = () => ( ... )
  ```

---

## Code to Refactor (Not Delete)

### 1. **Permission Context Simplification**
**File**: `/home/cscott/Repos/frostr/pwa/src/context/permissions.tsx` (105 lines)

**Current State**: Clean - KEEP AS IS ✅

**Why**: This is the actual permission storage layer that works with native dialogs. It correctly uses `lib/permissions.ts` for storage operations.

### 2. **Prompt Context Restructuring**
**File**: `/home/cscott/Repos/frostr/pwa/src/context/prompt.tsx` (245 lines)

**Refactor Plan**:
```typescript
// DELETE (lines 1-210): All UI-related prompt logic
// DELETE: window.__promptResolve pattern
// DELETE: bulkPermissionState stub
// DELETE: showBulkPermissionPrompt stub

// KEEP: Manual prompt handler (lines 189-209) for rare edge cases
// MOVE: Permission creation logic to lib/permissions.ts
```

**Result**: Reduce from 245 → ~50 lines (internal use only)

### 3. **Type System Cleanup**
**File**: `/home/cscott/Repos/frostr/pwa/src/types/prompt.ts`

**Refactor Plan**:
```typescript
// MOVE Permission interface to types/permissions.ts (canonical location)
// MOVE PermissionAPI interface to types/permissions.ts
// DELETE all prompt-related types (PromptState, PromptStatus, etc.)
// KEEP ONLY storage-related types in permissions.ts
```

### 4. **App Component Update**
**File**: `/home/cscott/Repos/frostr/pwa/src/components/app.tsx`

**Current**:
```typescript
import { PromptManager } from '@/components/prompt/index.js'

export function App () {
  return (
    <div className="app">
      <Header />
      <Tabs />
      <PromptManager />  // ← DELETE THIS
      <NIP55Bridge />
    </div>
  )
}
```

**After**:
```typescript
export function App () {
  return (
    <div className="app">
      <Header />
      <Tabs />
      <NIP55Bridge />
    </div>
  )
}
```

### 5. **Index Provider Cleanup**
**File**: `/home/cscott/Repos/frostr/pwa/src/index.tsx`

**Current**:
```typescript
import { PromptProvider } from '@/context/prompt.js'
import './styles/prompt.css'
import './styles/sessions.css'

root.render(
  <StrictMode>
    <SettingsProvider>
      <PermissionsProvider>
        <ConsoleProvider>
          <NodeProvider>
            <PromptProvider>  {/* ← REMOVE */}
              <App />
            </PromptProvider>
          </NodeProvider>
        </ConsoleProvider>
      </PermissionsProvider>
    </SettingsProvider>
  </StrictMode>
)
```

**After**:
```typescript
// Remove PromptProvider wrapper (if fully removing prompt system)
// OR keep minimal version if needed for edge cases
// Remove CSS imports for prompt and sessions
```

---

## Architecture Recommendations

### 1. **Single Source of Truth for Permissions**
**Current**: Permission decisions stored in PWA localStorage + Android SharedPreferences (encrypted)

**Recommendation**:
- Keep PWA localStorage as the canonical source (via `nip55_permissions_v2`)
- Android reads permissions only for quick checks
- All permission modifications go through PWA → Android syncs

**Why**: The PWA already has the crypto and permission logic. Android should be a thin UI layer.

### 2. **Eliminate Prompt Provider Entirely**
**Recommendation**: Delete `PromptProvider` and `PromptManager`

**Justification**:
1. Android `NIP55PermissionDialog` handles ALL user prompts
2. PWA signing bridge only needs permission **checking**, not **prompting**
3. Automatic signing (allowed) uses `executeAutoSigning()` directly
4. Manual prompts trigger Android native dialog via `InvisibleNIP55Handler`

**New Flow**:
```
External App
  ↓
Android NIP55Handler
  ↓ (check permission)
  ├─ allowed? → Direct signing via MainActivity PWA
  ├─ denied? → Return error
  └─ prompt? → Show NIP55PermissionDialog → User decides → Return
```

### 3. **Simplify Permission Storage API**
**Current**: Two separate APIs (`PermissionAPI` + `PromptAPI`)

**Recommendation**: Single unified API in `lib/permissions.ts`:
```typescript
export const PermissionStorage = {
  check: (appId, type, kind?) => PermissionStatus
  set: (appId, type, allowed, kind?) => void
  revoke: (appId, type, kind?) => void
  revokeAll: (appId) => void
  list: (appId?) => PermissionRule[]
  bulkSet: (appId, permissions[], allowed) => void
}
```

No React context needed - just pure storage functions.

### 4. **ContentProvider Comments Cleanup**
**Files with outdated comments**:
- `/home/cscott/Repos/frostr/pwa/src/components/nip55-bridge.tsx` (line 13)
  ```typescript
  // "Synchronizes permissions to window context for ContentProvider access"
  ```
  **Reality**: No ContentProvider in current Android implementation

- `/home/cscott/Repos/frostr/pwa/src/types/prompt.ts` (line 35)
  ```typescript
  // Unified Permission format (matches ContentProvider format)
  ```
  **Reality**: No ContentProvider format to match

**Recommendation**: Remove all ContentProvider references from comments.

### 5. **Remove Sessions CSS**
**Evidence**: 537 lines of unused CSS for a session management feature that doesn't exist

**Files to check**:
- `SessionsIcon` in icons.tsx - Never imported
- No `<Sessions>` component exists
- CSS imported in index.tsx but no component uses it

**Recommendation**: Delete entire `sessions.css` file.

---

## Inconsistent Patterns

### 1. **Permission Type Duplication**
**Issue**: Same permission types defined in multiple files

**Locations**:
- `NIP55OperationType` in `/types/permissions.ts` (canonical)
- `NIP55Action` in `/types/signer.ts` (duplicate)

**Recommendation**: Use only `NIP55OperationType` everywhere.

### 2. **Event Kind Labels in Two Places**
**Issue**: Event kind → human label mapping exists in:

1. `/src/components/prompt/event.tsx` (lines 47-76)
   ```typescript
   const getEventKindName = (kind: number) => {
     switch (kind) {
       case 0: return 'Profile Metadata'
       case 1: return 'Text Note'
       // ... 30+ cases
     }
   }
   ```

2. `/src/lib/permissions.ts` (lines 250-269)
   ```typescript
   export function getEventKindLabel(kind: number): string {
     const labels: Record<number, string> = {
       0: 'Metadata',
       1: 'Text Note',
       // ... different labels!
     }
   }
   ```

**Problems**:
- Different label text ("Profile Metadata" vs "Metadata")
- Duplication of ~20 event kinds
- One is in a component that will be deleted

**Recommendation**: Keep only `lib/permissions.ts` version. Delete prompt component.

### 3. **React Hook Usage**
**Finding**: 85 hook calls across 19 files (reasonable)

**Potential Over-Use**:
- `/components/settings/relays.tsx`: 4 useState + 2 useEffect for simple form
- `/components/permissions/index.tsx`: 3 useState + 1 useEffect for list display

**Recommendation**: Consider reducer pattern for complex forms (low priority for alpha).

---

## Action Plan

### Phase 1: Safe Deletions (Zero Risk)
**Estimated Time**: 30 minutes

1. ✂️ Delete deprecated types
   ```bash
   rm /home/cscott/Repos/frostr/pwa/src/types/global.ts
   ```

2. ✂️ Delete unused sessions CSS
   ```bash
   rm /home/cscott/Repos/frostr/pwa/src/styles/sessions.css
   ```

3. ✂️ Remove import from index.tsx
   ```typescript
   // Remove: import './styles/sessions.css'
   ```

4. ✂️ Delete SessionsIcon from icons.tsx
   ```typescript
   // Remove export const SessionsIcon = () => ( ... )
   ```

5. ✂️ Delete outdated documentation
   ```bash
   rm /home/cscott/Repos/frostr/pwa/src/components/prompt/README.md
   rm /home/cscott/Repos/frostr/pwa/src/components/permissions/README.md
   ```

### Phase 2: Prompt System Removal (Medium Risk)
**Estimated Time**: 1-2 hours
**Testing Required**: Verify native Android dialogs still work

1. ✂️ Delete prompt UI components
   ```bash
   rm /home/cscott/Repos/frostr/pwa/src/components/prompt/action.tsx
   rm /home/cscott/Repos/frostr/pwa/src/components/prompt/event.tsx
   rm /home/cscott/Repos/frostr/pwa/src/components/prompt/index.tsx
   ```

2. ✂️ Delete prompt.css
   ```bash
   rm /home/cscott/Repos/frostr/pwa/src/styles/prompt.css
   ```

3. ✂️ Remove PromptManager from App
   ```typescript
   // File: src/components/app.tsx
   // Remove: import { PromptManager } from '@/components/prompt/index.js'
   // Remove: <PromptManager />
   ```

4. 🔧 Refactor PromptProvider
   ```typescript
   // File: src/context/prompt.tsx
   // Extract only permission storage logic (~30 lines)
   // Move to lib/permissions.ts
   // Delete rest of file OR make minimal version
   ```

5. 🔧 Update index.tsx
   ```typescript
   // Remove: import { PromptProvider }
   // Remove: import './styles/prompt.css'
   // Remove: <PromptProvider> wrapper (if fully deleting)
   ```

6. ✅ Test native Android permission dialogs
   - Launch from Amethyst
   - Verify permission prompts appear
   - Verify "remember choice" works
   - Verify auto-signing works for approved perms

### Phase 3: Type System Cleanup (Low Risk)
**Estimated Time**: 30 minutes

1. 🔧 Consolidate permission types
   ```typescript
   // File: src/types/permissions.ts
   // Move Permission interface here
   // Move PermissionAPI interface here
   ```

2. 🔧 Clean up prompt types
   ```typescript
   // File: src/types/prompt.ts
   // Delete: PromptState, PromptStatus, PromptAPI
   // Delete: BulkPermissionState, BulkPermissionRequest (use permissions.ts version)
   ```

3. 🔧 Update type exports
   ```typescript
   // File: src/types/index.ts
   // Remove: export * from './prompt.js'
   // Add: export * from './permissions.js'
   ```

4. 🔧 Fix imports across codebase
   ```bash
   # Find all imports of removed types
   grep -r "from '@/types/prompt" src/
   # Update to use permissions.ts types
   ```

### Phase 4: Architecture Simplification (Optional)
**Estimated Time**: 2-3 hours
**Risk**: Medium - requires testing

1. 🔧 Consider removing PermissionsProvider context
   ```typescript
   // Current: React context wrapping permission functions
   // Alternative: Direct imports from lib/permissions.ts
   ```

2. 🔧 Evaluate if PromptProvider is needed at all
   ```typescript
   // If native Android handles ALL prompts
   // Then NO React prompt provider needed
   ```

3. 🔧 Simplify NIP55Bridge
   ```typescript
   // Remove permission synchronization comments
   // Remove ContentProvider references
   ```

---

## Testing Checklist

### After Each Phase:

- [ ] **Build succeeds**: `npm run build`
- [ ] **No TypeScript errors**: Check console output
- [ ] **PWA loads**: Visit http://localhost:3000
- [ ] **Android app installs**: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
- [ ] **NIP-55 login works**: Launch from Amethyst with new account
- [ ] **Permission prompts show**: Verify native Android dialog appears
- [ ] **Auto-signing works**: Test pre-approved permission (if implemented)
- [ ] **Permission management**: Check PWA permissions tab still works
- [ ] **No console errors**: Check browser DevTools and `adb logcat`

### Integration Tests:

1. **Fresh install** (clear app data)
   - First-time login from Amethyst
   - Grant permissions
   - Verify saved in localStorage

2. **Existing permissions**
   - Relaunch from Amethyst
   - Should auto-sign without prompts
   - Verify localStorage read correctly

3. **Permission revocation**
   - Remove permission in PWA UI
   - Relaunch from Amethyst
   - Should show prompt again

---

## Metrics

### Current State:
- **Total TypeScript Lines**: 4,151
- **Total CSS Lines**: 2,572
- **Legacy Code Identified**: ~1,200 lines (29%)
- **Files to Delete**: 8 files
- **Files to Refactor**: 5 files

### After Cleanup:
- **Estimated TypeScript**: ~3,000 lines (-28%)
- **Estimated CSS**: ~2,000 lines (-22%)
- **Architectural Clarity**: HIGH (single permission system)
- **Maintenance Burden**: REDUCED (no dual prompt systems)

---

## Risk Assessment

### Low Risk Deletions:
- ✅ Deprecated `global.ts` type file
- ✅ Unused `sessions.css` stylesheet
- ✅ Outdated README files
- ✅ SessionsIcon that's never imported

### Medium Risk Deletions:
- ⚠️ Prompt UI components (verify Android dialogs work)
- ⚠️ PromptProvider context (may have edge case usage)
- ⚠️ Type definitions (check all imports updated)

### High Risk Changes:
- ⛔ Removing permission storage (DON'T DO - it's needed)
- ⛔ Changing localStorage keys (would break existing installs)
- ⛔ Modifying NIP55Bridge without testing (breaks signing)

---

## Long-Term Recommendations

### 1. **Version 2 Architecture**
When moving beyond alpha, consider:

- **Separate permission storage from UI**: Make `lib/permissions.ts` the single source
- **Remove React contexts for pure storage**: Use direct imports
- **Consolidate Android ↔ PWA communication**: Single bridge interface
- **Add permission migration system**: For future schema changes

### 2. **Code Organization**
```
src/
├── lib/
│   ├── permissions.ts      # All permission logic (no React)
│   ├── signer.ts           # Crypto operations
│   └── storage.ts          # NEW: Generic storage abstraction
├── context/
│   ├── node.tsx            # KEEP: Bifrost node state
│   ├── settings.tsx        # KEEP: App settings
│   └── console.tsx         # KEEP: Debug logging
├── components/
│   ├── permissions/        # KEEP: Permission UI (read-only)
│   ├── settings/           # KEEP: Settings UI
│   └── dash/               # KEEP: Dashboard
└── types/
    ├── permissions.ts      # Canonical permission types
    ├── signer.ts           # NIP-55 types
    └── settings.ts         # App config types
```

### 3. **Documentation Updates**
After cleanup, update:

- `/home/cscott/Repos/frostr/pwa/CLAUDE.md` - Remove prompt system mentions
- `/home/cscott/Repos/frostr/pwa/src/README.md` - Update architecture section
- Add: `PERMISSION_FLOW.md` - Document native Android flow

---

## Conclusion

The PWA contains **significant technical debt** from its evolution:

1. **622 lines of dead prompt UI** - Android native dialogs replaced PWA prompts
2. **537 lines of orphaned CSS** - Sessions feature never implemented
3. **Type system duplication** - Same types in 3 different files
4. **Architectural mismatch** - PWA thinks it handles prompts, Android actually does

**Recommendation**: Execute Phase 1 (safe deletions) immediately. Phase 2 (prompt removal) requires thorough testing but offers major simplification. Phases 3-4 are optional cleanup for maintainability.

**Impact**: Removing legacy code will:
- ✅ Reduce codebase by ~29%
- ✅ Eliminate architectural confusion
- ✅ Improve build times
- ✅ Make onboarding easier for new developers
- ✅ Reduce attack surface (less code = fewer bugs)

This is an **alpha application** - now is the perfect time for aggressive cleanup before users depend on the codebase.
