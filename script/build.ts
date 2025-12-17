import fs           from 'fs'
import * as esbuild from 'esbuild'
import path         from 'path'
import { execSync } from 'child_process'

type Loader = 'js' | 'jsx' | 'ts' | 'tsx' | 'css' | 'json' | 'text' | 'base64' | 'dataurl' | 'file' | 'binary'

interface BuildOptions {
  bundle       : boolean
  minify       : boolean
  sourcemap    : boolean
  target       : string[]
  entryPoints? : string[]
  outfile?     : string
  format?      : 'esm' | 'iife'
  loader?      : { [ext: string]: Loader }
  alias?       : { [pkg: string]: string }
  resolveExtensions?: string[]
  publicPath?: string
  assetNames?: string
  plugins?: any[]
  external?: string[]
}

const PUBLIC_DIR = 'public'
const DIST_DIR   = 'dist'
const ANDROID_ASSETS_DIR = 'android/app/src/main/assets'

/**
 * Copy PWA dist files to Android assets directory
 */
async function copyToAndroidAssets(): Promise<void> {
  console.log('[ build ] copying PWA assets to Android...')

  // Ensure Android assets directory exists
  await fs.promises.mkdir(ANDROID_ASSETS_DIR, { recursive: true })

  // Files and directories to copy
  const itemsToCopy = [
    'app.js',
    'app.js.map',
    'sw.js',
    'sw.js.map',
    'index.html',
    'manifest.json',
    'favicon.ico',
    'styles',
    'icons'
  ]

  for (const item of itemsToCopy) {
    const srcPath = path.join(DIST_DIR, item)
    const destPath = path.join(ANDROID_ASSETS_DIR, item)

    try {
      const stat = await fs.promises.stat(srcPath)
      if (stat.isDirectory()) {
        // Remove existing directory and copy fresh
        await fs.promises.rm(destPath, { recursive: true, force: true })
        await fs.promises.cp(srcPath, destPath, { recursive: true })
        console.log(`[ build ]   copied ${item}/`)
      } else {
        await fs.promises.copyFile(srcPath, destPath)
        console.log(`[ build ]   copied ${item}`)
      }
    } catch (err) {
      // Skip if file doesn't exist (e.g., icons directory may not exist)
      if ((err as NodeJS.ErrnoException).code !== 'ENOENT') {
        throw err
      }
    }
  }

  console.log('[ build ] Android assets updated')
}

/**
 * Build Android APK using Gradle
 */
function buildAndroidApk(): void {
  console.log('[ build ] building Android APK...')

  const androidDir = path.join(process.cwd(), 'android')

  try {
    execSync('./gradlew assembleDebug', {
      cwd: androidDir,
      stdio: 'inherit'
    })
    console.log('[ build ] Android APK built successfully')
    console.log(`[ build ] APK location: android/app/build/outputs/apk/debug/app-debug.apk`)
  } catch (err) {
    console.error('[ build ] Android build failed')
    throw err
  }
}

async function build(): Promise<void> {
  const watch = process.argv.includes('--watch')
  const android = process.argv.includes('--android')

  // Clean dist directory.
  fs.rmSync(`./${DIST_DIR}`, { recursive: true, force: true })

  // Copy public files.
  fs.cpSync(`./${PUBLIC_DIR}`, `./${DIST_DIR}`, { recursive: true })

  // Modified CSS plugin to extract CSS into separate files
  const cssPlugin = {
    name: 'css',
    setup(build) {
      build.onResolve({ filter: /\.css$/ }, args => {
        // Handle both direct imports and aliased imports
        let fullPath: string
        if (args.path.startsWith('@/')) {
          // Handle @ aliases
          fullPath = path.resolve('./src', args.path.slice(2))
        } else {
          // Handle other relative imports
          fullPath = path.resolve(args.resolveDir, args.path)
        }
        return { path: fullPath, namespace: 'css-ns' }
      })

      build.onLoad({ filter: /.*/, namespace: 'css-ns' }, async (args) => {
        try {
          // Read the CSS file from its source location
          const css = await fs.promises.readFile(args.path, 'utf8')
          const filename = path.basename(args.path)
          const stylesDir = path.join(DIST_DIR, 'styles')
          
          // Ensure styles directory exists
          await fs.promises.mkdir(stylesDir, { recursive: true })
          
          // Write CSS to styles directory
          const outPath = path.join(stylesDir, filename)
          await fs.promises.writeFile(outPath, css)
          
          // Return a module that creates a link element to load the CSS
          return {
            contents: `
              const link = document.createElement('link');
              link.rel  = 'stylesheet';
              link.href = 'styles/${filename}';
              document.head.appendChild(link);
            `,
            loader: 'js'
          }
        } catch (error) {
          console.error(`Error processing CSS file ${args.path}:`, error)
          throw error
        }
      })
    }
  }

  // Build options
  const commonOptions: BuildOptions = {
    bundle    : true,
    minify    : !watch,
    sourcemap : true,
    target    : ['chrome58', 'firefox57', 'safari11', 'edge18'],
    alias     : { '@': path.resolve('./src') },
    resolveExtensions: ['.tsx', '.ts', '.jsx', '.js', '.css', '.json'],
    external: [],
    loader: {
      '.tsx' : 'tsx',
      '.ts'  : 'ts',
      '.css' : 'css',
      '.png' : 'file',
      '.jpg' : 'file',
      '.svg' : 'file',
      '.gif' : 'file',
    },
    // Use relative paths for better compatibility
    publicPath: '',
    assetNames: '[name]-[hash]',
    // Add a plugin to handle image paths
    plugins: [{
      name: 'image-path',
      setup(build) {
        build.onResolve({ filter: /\.(png|jpg|svg|gif)$/ }, args => {
          if (args.path.startsWith('/')) {
            return {
              path: path.resolve('public', args.path.slice(1)),
              namespace: 'image-ns'
            }
          }
          return null
        })
      }
    }]
  }

  // Build app
  const appBuildOptions: BuildOptions = {
    ...commonOptions,
    entryPoints : ['src/index.tsx'],
    outfile     : `${DIST_DIR}/app.js`,
    format      : 'esm',
  }

  // Build service worker
  const swBuildOptions: BuildOptions = {
    ...commonOptions,
    entryPoints : ['src/sw.ts'],
    outfile     : 'dist/sw.js',
    format      : 'iife',
    loader      : {
      '.tsx' : 'tsx',
      '.ts'  : 'ts',
    },
  }

  // Copy CSS files to dist
  const copyCssFiles = async () => {
    const srcStylesDir = path.join('src', 'styles')
    const distStylesDir = path.join(DIST_DIR, 'styles')
    
    // Ensure dist styles directory exists
    await fs.promises.mkdir(distStylesDir, { recursive: true })
    
    // Copy all CSS files
    const files = await fs.promises.readdir(srcStylesDir)
    for (const file of files) {
      if (file.endsWith('.css')) {
        const srcPath = path.join(srcStylesDir, file)
        const distPath = path.join(distStylesDir, file)
        await fs.promises.copyFile(srcPath, distPath)
      }
    }
  }

  if (watch) {
    // Use context API for watch mode
    const appContext = await esbuild.context({
      ...appBuildOptions,
      plugins: [cssPlugin]
    })

    const swContext = await esbuild.context(swBuildOptions)
    
    // Watch CSS files
    const watchCssFiles = async () => {
      const srcStylesDir = path.join('src', 'styles')
      const distStylesDir = path.join(DIST_DIR, 'styles')
      
      // Ensure dist styles directory exists
      await fs.promises.mkdir(distStylesDir, { recursive: true })
      
      // Initial copy
      await copyCssFiles()
      
      // Watch for changes
      fs.watch(srcStylesDir, async (eventType, filename) => {
        if (filename && filename.endsWith('.css')) {
          console.log(`[ build ] CSS file changed: ${filename}`)
          const srcPath = path.join(srcStylesDir, filename)
          const distPath = path.join(distStylesDir, filename)
          await fs.promises.copyFile(srcPath, distPath)
        }
      })
    }
    
    await Promise.all([
      appContext.watch(),
      swContext.watch(),
      watchCssFiles()
    ])
    
    console.log('[ build ] watching for changes...')
  } else {
    // One-time build
    await Promise.all([
      esbuild.build({
        ...appBuildOptions,
        plugins: [cssPlugin]
      }),
      esbuild.build(swBuildOptions),
      copyCssFiles()
    ])

    console.log('[ build ] PWA build complete')

    // If --android flag is passed, copy to Android and build APK
    if (android) {
      await copyToAndroidAssets()
      buildAndroidApk()
    }
  }
}

// Run the build function and handle errors
build().catch(err => {
  console.error('[ build ] build failed:', err)
  process.exit(1)
})
