# Tech Debt Cleanup - Quick Reference

## Files to DELETE (Zero Risk)

```bash
# Deprecated types
rm src/types/global.ts

# Unused styles  
rm src/styles/sessions.css
rm src/styles/prompt.css

# Outdated docs
rm src/components/prompt/README.md
rm src/components/permissions/README.md

# Dead prompt components (Android handles all prompts now)
rm src/components/prompt/action.tsx
rm src/components/prompt/event.tsx  
rm src/components/prompt/index.tsx
```

## Files to REFACTOR

### src/index.tsx
```diff
- import './styles/sessions.css'
- import './styles/prompt.css'
- import { PromptProvider } from '@/context/prompt.js'

  root.render(
    <StrictMode>
      <SettingsProvider>
        <PermissionsProvider>
          <ConsoleProvider>
            <NodeProvider>
-             <PromptProvider>
                <App />
-             </PromptProvider>
            </NodeProvider>
          </ConsoleProvider>
        </PermissionsProvider>
      </SettingsProvider>
    </StrictMode>
  )
```

### src/components/app.tsx
```diff
- import { PromptManager } from '@/components/prompt/index.js'

  export function App () {
    return (
      <div className="app">
        <Header />
        <Tabs />
-       <PromptManager />
        <NIP55Bridge />
      </div>
    )
  }
```

### src/components/util/icons.tsx
```diff
- // Remove SessionsIcon (never imported)
- export const SessionsIcon = () => ( ... )
```

### src/types/index.ts
```diff
  export * from './global.js'
  export * from './signer.js'
- export * from './prompt.js'
  export * from './settings.js'
  export * from './util.js'
+ export * from './permissions.js'
```

### src/context/prompt.tsx
**Option A**: Delete entirely (if Android handles ALL prompts)
**Option B**: Extract ~30 lines of permission storage logic to lib/permissions.ts, delete rest

## Code Metrics

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| TypeScript LOC | 4,151 | ~3,000 | -28% |
| CSS LOC | 2,572 | ~2,000 | -22% |
| Dead Code | 1,200 | 0 | -100% |
| Files | 50+ | 42 | -8 files |

## Testing Checklist

- [ ] `npm run build` succeeds
- [ ] No TypeScript errors
- [ ] PWA loads at localhost:3000
- [ ] Android app installs: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
- [ ] NIP-55 login from Amethyst works
- [ ] Native permission dialog appears
- [ ] "Remember choice" saves to localStorage
- [ ] Auto-signing works for approved permissions
- [ ] Permission management UI still works
- [ ] No console errors in browser or logcat

## Key Insights

1. **Dual Permission Systems**: Android's `NIP55PermissionDialog.kt` handles ALL prompts, making PWA prompt UI (622 lines) completely obsolete

2. **Window Pollution**: `window.__promptResolve` pattern unnecessary - Android manages all user interaction

3. **Type Duplication**: `BulkPermissionRequest` defined in both `prompt.ts` and `permissions.ts`

4. **Orphaned CSS**: 537 lines of `sessions.css` for feature that doesn't exist

5. **Stub Functions**: `showBulkPermissionPrompt()` is a stub that warns but can never work

## Execution Plan

### Phase 1: Safe Deletions (30 min)
Execute all `rm` commands above

### Phase 2: Prompt Removal (1-2 hrs)
Delete prompt components, update imports, test Android dialogs

### Phase 3: Type Cleanup (30 min)  
Consolidate types, remove duplicates, fix imports

### Phase 4: Context Simplification (2-3 hrs, optional)
Remove PromptProvider, simplify permission API

## Full Analysis
See `TECH_DEBT_ANALYSIS.md` for complete details
