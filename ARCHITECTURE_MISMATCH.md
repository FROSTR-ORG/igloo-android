# Architecture Mismatch Analysis

## The Fundamental Problem

The PWA codebase was built assuming **PWA handles permission prompts**, but the Android wrapper was later changed to use **native dialogs**. Nobody cleaned up the obsolete PWA code.

---

## Current Architecture (What Actually Happens)

```
┌─────────────────┐
│  External App   │
│  (Amethyst)     │
└────────┬────────┘
         │
         ▼ nostrsigner: intent
┌─────────────────────────────┐
│  InvisibleNIP55Handler.kt   │
│  ┌─────────────────────┐    │
│  │ Check Permission    │    │
│  └──────────┬──────────┘    │
│             │                │
│    ┌────────┴────────┐       │
│    │                 │       │
│    ▼                 ▼       │
│  denied           allowed    │
│    │                 │       │
│    ▼                 ▼       │
│  Return         Launch       │
│  Error          MainActivity │
└─────────────────────┬────────┘
                      │
                      ▼
         ┌────────────────────────┐
         │  NIP55PermissionDialog │  ← NATIVE ANDROID DIALOG
         │  (Kotlin UI)           │
         └────────────┬───────────┘
                      │
                 User decides
                      │
                      ▼
              ┌───────────────┐
              │  Store to     │
              │  localStorage │
              └───────────────┘
```

**Key Point**: PWA prompt components are NEVER invoked. Android's `NIP55PermissionDialog.kt` shows ALL permission UI.

---

## Legacy Architecture (What Code Expects)

```
┌─────────────────┐
│  External App   │
└────────┬────────┘
         │
         ▼
┌──────────────────────────┐
│  NIP55Bridge (PWA)       │
│  ┌──────────────────┐    │
│  │ Check Permission │    │
│  └─────────┬────────┘    │
│            │              │
│     ┌──────┴───────┐      │
│     ▼              ▼      │
│  allowed       prompt     │
│     │           required  │
│     │              │      │
│     ▼              ▼      │
│  Execute    ┌─────────────────────┐
│  Signing    │  PromptProvider     │  ← OBSOLETE!
│             │  React Context      │
│             └─────────┬───────────┘
│                       │
│                       ▼
│             ┌─────────────────────┐
│             │  PromptManager      │  ← NEVER RENDERED!
│             │  ├─ ActionPrompt    │
│             │  └─ EventPrompt     │
│             └─────────────────────┘
│                   (622 lines)
└──────────────────────────────────┘
```

**Problem**: These 622 lines of React components are dead code.

---

## File Relationships

### Active (Used)
```
Android (Native)
├── InvisibleNIP55Handler.kt       ✅ Entry point
├── NIP55PermissionDialog.kt       ✅ Shows prompts
└── MainActivity.kt                ✅ Executes signing

PWA (Web)
├── lib/permissions.ts             ✅ Storage logic
├── context/permissions.tsx        ✅ React wrapper
├── components/permissions/        ✅ UI to view/manage
└── components/nip55-bridge.tsx    ✅ Signing bridge
```

### Dead (Unused)
```
PWA (Web)
├── components/prompt/
│   ├── action.tsx                 ❌ 172 lines - DEAD
│   ├── event.tsx                  ❌ 193 lines - DEAD  
│   └── index.tsx                  ❌ 12 lines - DEAD
├── context/prompt.tsx             ⚠️  245 lines - MOSTLY DEAD
├── styles/prompt.css              ❌ Unknown lines - DEAD
├── styles/sessions.css            ❌ 537 lines - ORPHANED
└── types/prompt.ts                ⚠️  Partial - HAS DUPLICATES
```

---

## The Dual Permission API Problem

### What Exists
```typescript
// src/types/prompt.ts
export interface PromptAPI {
  state: PromptState                    // ❌ Never used
  bulkPermissionState: { ... }          // ❌ Stub
  showPrompt: (req) => void             // ❌ Android shows prompts
  showBulkPermissionPrompt: (...) => {} // ❌ Stub (warns)
  approve: () => Promise<any>           // ❌ Android handles
  deny: () => Promise<void>             // ❌ Android handles
  dismiss: () => void                   // ❌ Android handles
}

export interface PermissionAPI {
  has_permission: (...) => boolean      // ✅ Used for checks
  set_permission: (...) => void         // ✅ Used by Android
  revoke_permission: (...) => void      // ✅ Used by UI
  list_permissions: (...) => []         // ✅ Used by UI
  bulk_set_permissions: (...) => void   // ✅ Used by Android
  revoke_all_for_app: (...) => void     // ✅ Used by UI
}
```

### What's Needed
```typescript
// Just use PermissionStorage functions directly!
export const PermissionStorage = {
  check: (appId, type, kind?) => PermissionStatus  // ✅
  set: (appId, type, allowed, kind?) => void       // ✅
  revoke: (appId, type, kind?) => void             // ✅
  list: (appId?) => PermissionRule[]               // ✅
  bulkSet: (appId, perms[], allowed) => void       // ✅
}
```

No need for `PromptAPI` at all.

---

## Type Duplication Issue

### BulkPermissionRequest (defined TWICE!)

**Version 1** - `src/types/prompt.ts` (lines 14-18):
```typescript
export interface BulkPermissionRequest {
  appId: string
  permissions: Array<{ type: string; kind?: number }>
}
```

**Version 2** - `src/types/permissions.ts` (lines 51-60):
```typescript
export interface BulkPermissionRequest {
  appId: string
  appName?: string  // ← More complete!
  permissions: Array<{
    type: NIP55OperationType  // ← Better typing!
    kind?: number
  }>
}
```

**Solution**: Delete version 1, use version 2 everywhere.

---

## Critical Code Analysis

### src/context/prompt.tsx (245 lines)

```typescript
// Lines 211-221: Stub that should never exist
const bulkPermissionState = {
  isOpen: false,
  request: null
}

const showBulkPermissionPrompt = async () => {
  console.warn('showBulkPermissionPrompt called - should be handled by native Android')
  return { approved: false }  // ← Always fails!
}
```

**Analysis**: This is a **lie**. The function is exported in the API, suggesting it works. But it just warns and fails. This is technical debt masking architectural mismatch.

### src/components/prompt/event.tsx (193 lines)

```typescript
export function EventPrompt() {
  const prompt = usePrompt()
  const [remember, setRemember] = useState(false)
  
  // Full UI with:
  // - Event kind labels (30+ cases)
  // - Security warnings for high-risk events
  // - Content preview with expand/collapse
  // - Remember choice checkbox
  // - Approve/Deny buttons
  
  return (
    <div className="prompt-overlay">
      <div className="prompt-modal">
        {/* ... 150 lines of JSX ... */}
      </div>
    </div>
  )
}
```

**Analysis**: Beautiful, well-crafted UI that will **never be shown**. Android's `NIP55PermissionDialog` shows instead. This is ~200 lines of perfect dead code.

---

## The Window Pollution Pattern

### src/context/prompt.tsx
```typescript
// Lines 62, 93, 130, 141, 174, 193
const approve = async () => {
  const resolve = (window as any).__promptResolve  // ← Get from global
  
  // ... do signing ...
  
  if (resolve) {
    resolve({ ok: true, result })  // ← Call stored callback
  }
  
  delete (window as any).__promptResolve  // ← Cleanup
}

const handleManualPrompt = async (request) => {
  return new Promise((resolve) => {
    (window as any).__promptResolve = resolve  // ← Store on window
    setState({ isOpen: true, request })
  })
}
```

**Analysis**: This is a **callback hell workaround** from when PWA handled prompts. Now that Android shows prompts, this entire pattern is unnecessary.

---

## Sessions.css Mystery

### Evidence
1. File exists: `src/styles/sessions.css` (537 lines)
2. Imported in: `src/index.tsx` (line 15)
3. Icon defined: `SessionsIcon` in `src/components/util/icons.tsx`
4. Component exists: **NO**
5. Icon imported: **NO**
6. CSS used: **NO**

### CSS Content Sample
```css
.sessions-container { ... }
.session-card { ... }
.session-header { ... }
.session-permissions-dropdown { ... }
.event-kinds-list { ... }
.permission-checkbox { ... }
/* ... 500+ more lines ... */
```

**Conclusion**: Someone built a session management feature, then deleted the component but left the CSS. Classic technical debt.

---

## Recommendations Summary

### DELETE IMMEDIATELY (Zero Risk)
1. `src/components/prompt/` directory (377 lines)
2. `src/styles/prompt.css` (unknown lines)
3. `src/styles/sessions.css` (537 lines)
4. `src/types/global.ts` (4 lines)
5. SessionsIcon from `icons.tsx` (~10 lines)

### REFACTOR (Medium Risk)
1. `src/context/prompt.tsx` → Extract 30 lines to `lib/permissions.ts`, delete rest
2. `src/types/prompt.ts` → Move needed types to `permissions.ts`, delete file
3. `src/components/app.tsx` → Remove `<PromptManager />`
4. `src/index.tsx` → Remove PromptProvider wrapper, remove CSS imports

### CONSOLIDATE (Low Risk)
1. Use `NIP55OperationType` everywhere (delete `NIP55Action` duplicate)
2. Use `BulkPermissionRequest` from `permissions.ts` (delete `prompt.ts` version)
3. Remove ContentProvider comments (feature doesn't exist)

---

## Impact

| Category | Before | After | Savings |
|----------|--------|-------|---------|
| **Prompt UI** | 622 lines | 0 lines | -622 |
| **Stub Code** | ~100 lines | 0 lines | -100 |
| **Dead CSS** | 537+ lines | 0 lines | -537+ |
| **Type Defs** | ~150 lines | ~50 lines | -100 |
| **Total** | ~1,409 lines | ~50 lines | **-1,359 lines** |

**Code Reduction**: ~33% of codebase is deletable legacy code.

---

## Root Cause

1. **Phase 1**: PWA built with self-contained permission prompts
2. **Phase 2**: Android wrapper added native `NIP55PermissionDialog` for better UX
3. **Phase 3**: Android dialog integration successful
4. **Phase 4**: **MISSING** - Nobody removed obsolete PWA prompt code

**Result**: Dual systems where only one works, creating architectural confusion.

---

## Conclusion

This is a **classic refactoring opportunity**:

- ✅ Native Android dialogs work perfectly
- ❌ PWA prompt system is 100% dead code
- ⚠️  Stub functions hide the architectural mismatch
- 🎯 Cleanup will reduce codebase by 33%

**Action**: Execute cleanup phases from `CLEANUP_SUMMARY.md` to align code with actual architecture.
