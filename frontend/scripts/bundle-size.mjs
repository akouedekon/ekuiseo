/*
 * Mesure du budget de taille du build (memes seuils que .github/workflows/ci.yml) :
 * JS et CSS de dist/assets compresses en gzip, tels que Caddy/nginx les servent.
 *   node scripts/bundle-size.mjs   (apres `npm run build`)
 */
import { gzipSync } from 'node:zlib'
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ASSETS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist/assets')
const BUDGET = { jsTotalKib: 900, jsEntryKib: 320, cssTotalKib: 60 }

const gz = (file) => gzipSync(readFileSync(file), { level: 9 }).length
let jsTotal = 0
let cssTotal = 0
let entry = 0
const rows = []
for (const name of readdirSync(ASSETS).sort()) {
  const file = path.join(ASSETS, name)
  if (name.endsWith('.js')) {
    const size = gz(file)
    jsTotal += size
    if (name.startsWith('index-')) entry += size
    rows.push([size, name])
  } else if (name.endsWith('.css')) {
    const size = gz(file)
    cssTotal += size
    rows.push([size, name])
  }
}
rows.sort((a, b) => b[0] - a[0])
for (const [size, name] of rows.slice(0, 12)) process.stdout.write(`${String(Math.round(size / 1024)).padStart(6)} Kio  ${name}\n`)
const kib = (n) => Math.floor(n / 1024)
process.stdout.write(`\nJS total : ${kib(jsTotal)} Kio gzip (budget ${BUDGET.jsTotalKib})\n`)
process.stdout.write(`JS entree index : ${kib(entry)} Kio gzip (budget ${BUDGET.jsEntryKib})\n`)
process.stdout.write(`CSS total : ${kib(cssTotal)} Kio gzip (budget ${BUDGET.cssTotalKib})\n`)
const over = kib(jsTotal) > BUDGET.jsTotalKib || kib(entry) > BUDGET.jsEntryKib || kib(cssTotal) > BUDGET.cssTotalKib
if (over) {
  process.stderr.write('bundle-size : budget depasse\n')
  process.exit(1)
}
process.stdout.write('bundle-size : dans le budget\n')
