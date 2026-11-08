// ZeroLinux 终端页构建脚本。
// 刻意不用 Vite / React：WebView 通过 file:// 加载 assets，
// IIFE 单文件最稳，也不受 ES module 的 CORS 限制。
import { build } from 'esbuild'
import { cp, mkdir, rm } from 'node:fs/promises'

const OUT = 'dist'

await rm(OUT, { recursive: true, force: true })
await mkdir(OUT, { recursive: true })

await build({
  entryPoints: ['src/main.ts'],
  bundle: true,
  format: 'iife',
  globalName: 'ZlTerminal',
  target: ['es2020'],
  minify: true,
  sourcemap: false,
  legalComments: 'none',
  outfile: `${OUT}/app.js`,
  logLevel: 'info',
})

// entry 里 import 了两个 css，esbuild 会合并输出到 app.css
await cp('index.html', `${OUT}/index.html`)

console.log('[zl] build ok -> dist/')
