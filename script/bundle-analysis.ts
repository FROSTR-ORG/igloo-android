import fs from 'fs'
import * as esbuild from 'esbuild'
import path from 'path'

interface BundleAnalysis {
  totalSize: number
  dependencies: { [name: string]: number }
  chunks: { [name: string]: number }
}

async function analyzeBundleComposition(): Promise<void> {
  console.log('📊 Analyzing bundle composition...\n')

  // Build with metafile to get detailed analysis
  const result = await esbuild.build({
    entryPoints: ['src/index.tsx'],
    bundle: true,
    minify: true,
    sourcemap: false,
    target: ['chrome58', 'firefox57', 'safari11', 'edge18'],
    format: 'esm',
    metafile: true,
    outfile: 'dist/analysis-app.js',
    alias: { '@': path.resolve('./src') },
    resolveExtensions: ['.tsx', '.ts', '.jsx', '.js', '.css', '.json'],
    external: [],
    write: false // Don't write file, just analyze
  })

  if (!result.metafile) {
    console.error('❌ No metafile generated')
    return
  }

  const meta = result.metafile

  // Analyze the inputs to see what's taking up space
  console.log('📦 **TOP DEPENDENCIES BY SIZE:**')
  console.log('=' .repeat(50))

  const inputs = Object.entries(meta.inputs)
    .map(([path, info]) => ({
      path,
      bytes: info.bytes,
      imports: info.imports?.length || 0
    }))
    .sort((a, b) => b.bytes - a.bytes)
    .slice(0, 20) // Top 20

  let totalBytes = 0
  inputs.forEach(input => totalBytes += input.bytes)

  inputs.forEach((input, i) => {
    const sizeKB = (input.bytes / 1024).toFixed(1)
    const percentage = ((input.bytes / totalBytes) * 100).toFixed(1)
    const filename = path.basename(input.path)
    const isNodeModule = input.path.includes('node_modules')
    const prefix = isNodeModule ? '📦' : '📄'

    console.log(`${String(i + 1).padStart(2)}. ${prefix} ${filename.padEnd(30)} ${sizeKB.padStart(7)}KB (${percentage.padStart(5)}%)`)

    if (isNodeModule) {
      // Extract package name for node modules
      const nodeModulesPath = input.path.split('node_modules/')[1]
      const packageName = nodeModulesPath?.split('/')[0] || 'unknown'
      console.log(`    └─ ${packageName}`)
    }
  })

  console.log('\n' + '='.repeat(50))
  console.log(`📈 **Total analyzed: ${(totalBytes / 1024).toFixed(1)}KB across ${inputs.length} files**`)

  // Analyze by package
  console.log('\n📦 **DEPENDENCIES BY PACKAGE:**')
  console.log('=' .repeat(50))

  const packageSizes: { [pkg: string]: number } = {}

  Object.entries(meta.inputs).forEach(([filePath, info]) => {
    if (filePath.includes('node_modules/')) {
      const nodeModulesPath = filePath.split('node_modules/')[1]
      const packageName = nodeModulesPath?.split('/')[0] || 'unknown'
      packageSizes[packageName] = (packageSizes[packageName] || 0) + info.bytes
    } else {
      packageSizes['[app-code]'] = (packageSizes['[app-code]'] || 0) + info.bytes
    }
  })

  const sortedPackages = Object.entries(packageSizes)
    .sort(([,a], [,b]) => b - a)
    .slice(0, 15)

  sortedPackages.forEach(([pkg, bytes], i) => {
    const sizeKB = (bytes / 1024).toFixed(1)
    const percentage = ((bytes / totalBytes) * 100).toFixed(1)
    const emoji = pkg === '[app-code]' ? '📄' : '📦'
    console.log(`${String(i + 1).padStart(2)}. ${emoji} ${pkg.padEnd(25)} ${sizeKB.padStart(7)}KB (${percentage.padStart(5)}%)`)
  })

  // Analyze outputs
  console.log('\n🎯 **BUNDLE OUTPUT ANALYSIS:**')
  console.log('=' .repeat(50))

  Object.entries(meta.outputs).forEach(([outputPath, info]) => {
    const sizeKB = (info.bytes / 1024).toFixed(1)
    const filename = path.basename(outputPath)
    console.log(`📦 ${filename}: ${sizeKB}KB`)

    if (info.imports) {
      console.log(`   └─ Imports: ${info.imports.length} files`)
    }
  })

  // Identify potential optimization targets
  console.log('\n💡 **OPTIMIZATION OPPORTUNITIES:**')
  console.log('=' .repeat(50))

  const largePackages = sortedPackages.filter(([, bytes]) => bytes > 50 * 1024) // > 50KB
  largePackages.forEach(([pkg, bytes]) => {
    const sizeKB = (bytes / 1024).toFixed(1)
    if (pkg.includes('frostr') || pkg.includes('bifrost')) {
      console.log(`🔥 ${pkg} (${sizeKB}KB) - Core FROSTR dependency, consider lazy loading non-critical parts`)
    } else if (pkg.includes('noble')) {
      console.log(`🔐 ${pkg} (${sizeKB}KB) - Crypto library, could be lazy loaded for non-signing operations`)
    } else if (pkg.includes('nostr')) {
      console.log(`⚡ ${pkg} (${sizeKB}KB) - Nostr library, analyze what parts are actually used`)
    } else if (pkg.includes('react')) {
      console.log(`⚛️  ${pkg} (${sizeKB}KB) - React framework, already optimized`)
    } else {
      console.log(`📦 ${pkg} (${sizeKB}KB) - Analyze if all exports are needed`)
    }
  })

  // Specific NIP-55 analysis
  console.log('\n🎯 **NIP-55 CRITICAL PATH ANALYSIS:**')
  console.log('=' .repeat(50))
  console.log('For NIP-55 operations, we only need:')
  console.log('✅ Basic React rendering')
  console.log('✅ NIP-55 request parsing')
  console.log('✅ Permission checking')
  console.log('✅ Basic UI for prompts')
  console.log('❌ Full FROSTR node initialization (can be lazy)')
  console.log('❌ Full settings management (can be lazy)')
  console.log('❌ Dashboard components (can be lazy)')
  console.log('')
  console.log('🚀 **Recommendation: Create NIP-55 micro-bundle (~200KB) + lazy load full app**')

  console.log('\n✅ Bundle analysis complete!')
}

// Run the analysis
analyzeBundleComposition().catch(err => {
  console.error('❌ Bundle analysis failed:', err)
  process.exit(1)
})