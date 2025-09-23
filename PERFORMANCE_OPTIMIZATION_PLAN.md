# PWA Performance Optimization Plan

## 🔥 **CRITICAL ISSUE ANALYSIS**

### **Root Problems Identified:**
1. **Amethyst Process Killing** - Forces full cold-start (1.5+ seconds)
2. **1MB JavaScript Bundle** - Slow download/parse on mobile
3. **Node Initialization Delay** - 2+ seconds even after PWA loads
4. **No Progressive Loading** - Everything loads before anything works

### **Performance Timeline (From Logs):**
```
00:00.000  onCreate (App process starts)
00:00.009  Intent received
00:00.298  React DevTools (JS parsed & executed)
00:00.429  NIP-55 handlers ready
00:02.434  Node still not ready (init status)
```

## 🎯 **IMMEDIATE OPTIMIZATIONS (Target: <500ms to functional)**

### **Phase 1: Bundle Size Reduction (Target: 500KB → 200KB critical path)**

#### **1.1 Code Splitting Strategy**
```typescript
// BEFORE: Everything in one bundle
import { App } from './components/app'
import { BifrostNode } from './bifrost'
import { AllComponents } from './components/*'

// AFTER: Critical path only
import { NIP55Handler } from './nip55/handler'       // Critical
import { MinimalUI } from './components/minimal'     // Critical
// Lazy load everything else
const App = lazy(() => import('./components/app'))
const Settings = lazy(() => import('./components/settings'))
```

#### **1.2 Dependency Analysis & Reduction**
**Target Dependencies for Analysis:**
- `@frostr/bifrost` - Likely the largest dependency
- `nostr-tools` - May have unused exports
- `@noble/ciphers` + `@noble/hashes` - Crypto libraries
- React 19 - Already optimized

#### **1.3 NIP-55 Fast Path Bundle**
**Create a separate micro-bundle for NIP-55 only:**
```
nip55-fast.js (~50KB) - Just NIP-55 handling + UI
app-full.js (~400KB) - Everything else, lazy loaded
```

### **Phase 2: Runtime Optimizations**

#### **2.1 Progressive Bootstrap Strategy**
```typescript
// Stage 1: NIP-55 ready (target: 200ms)
loadCriticalNIP55Only()

// Stage 2: Basic UI (target: 500ms)
loadMinimalInterface()

// Stage 3: Full app (background)
loadFullApplication()
```

#### **2.2 Node Initialization Optimization**
- **Background connection** during bundle load
- **Cached session tokens** for instant reconnect
- **Progressive connection states** (offline → connecting → online)

### **Phase 3: Build System Optimizations**

#### **3.1 esbuild Configuration Enhancements**
```typescript
// script/build.ts optimizations
{
  minify: true,
  treeShaking: true,
  splitting: true,           // Enable code splitting
  chunkNames: '[name]-[hash]',
  target: ['es2020'],       // Modern JS for better compression
  keepNames: false,         // Remove debug names
}
```

#### **3.2 Service Worker Optimization**
- Reduce SW bundle from 343KB → <100KB
- Only cache critical resources initially
- Progressive enhancement caching

## 📊 **IMPLEMENTATION PRIORITY**

### **Week 1: Emergency Fixes (70% improvement target)**
1. ✅ **Bundle analysis and splitting**
2. ✅ **Remove unused dependencies**
3. ✅ **Create NIP-55 fast-path bundle**
4. ✅ **Optimize esbuild configuration**

### **Week 2: Architecture Improvements (90% improvement target)**
1. ✅ **Progressive loading implementation**
2. ✅ **Background node initialization**
3. ✅ **Service worker optimization**

### **Week 3: Advanced Optimizations (95% improvement target)**
1. ✅ **Resource preloading strategies**
2. ✅ **Advanced caching mechanisms**
3. ✅ **Memory usage optimization**

## 🔧 **MEASUREMENT STRATEGY**

### **Key Metrics to Track:**
- **Time to Interactive (TTI)** - Currently ~2.5s, Target <0.5s
- **First Contentful Paint (FCP)** - Currently ~0.3s, Target <0.2s
- **Bundle Size** - Currently 1MB, Target <0.5MB critical path
- **Node Ready Time** - Currently >2s, Target <1s

### **Testing Protocol:**
1. Cold start from Amethyst intent
2. Measure each stage with performance.mark()
3. Test on low-end Android devices
4. Compare against target metrics

## 🚀 **SUCCESS CRITERIA**

### **Minimum Viable Performance:**
- Cold start to NIP-55 ready: **<800ms** (currently 2.5s+)
- Bundle size: **<600KB total** (currently 1MB)
- Node initialization: **<1.5s** (currently >2s)

### **Optimal Performance:**
- Cold start to NIP-55 ready: **<400ms**
- Bundle size: **<300KB critical path**
- Node initialization: **<800ms**

## 📋 **NEXT STEPS**

1. **Analyze bundle composition** - Identify largest dependencies
2. **Implement code splitting** - Separate NIP-55 critical path
3. **Optimize build configuration** - Tree shaking, minification
4. **Test and measure** - Validate improvements on real devices
5. **Progressive enhancement** - Load full features after critical path

---
*This plan addresses the real architectural constraints while working within the FROSTR multi-party signing requirements.*