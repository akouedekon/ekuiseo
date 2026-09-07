/*
 * Garde-fou de la charte (audit F326) : refuse, dans src/, les tailles de texte
 * arbitraires (`text-[13px]`) hors liste blanche et les alias historiques de
 * couleur (`var(--indigo)`, `bg-vermillon-soft`…). L'echelle typographique et les
 * roles de couleur vivent dans src/index.css ; ce script empeche la derive.
 *   node scripts/check-tokens.mjs   (execute par `npm run lint`)
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src')

/** Fichiers ou une taille arbitraire est admise, avec la raison. */
const ARBITRARY_SIZE_ALLOWLIST = new Map([
  ['pages/SystemPages.tsx', 'code d’erreur 404 en 72 px : hors echelle, decoratif'],
])

const LEGACY_VARS = /var\(--(indigo|vermillon|ocre|vert|paper|surface-calm)(-[a-z0-9-]+)?\)/g
const LEGACY_CLASSES =
  /(?:^|[\s'"`:(])(?:bg|text|border|fill|ring|from|to)-(?:indigo(?:-brand|-soft|-ink|-deep|-hover|-active)?|vermillon(?:-soft|-ink|-hover)?|ocre(?:-soft|-ink|-hover)?|vert(?:-soft|-ink|-hover)?|paper|surface-calm|on-indigo|on-vermillon|on-ocre|on-vert)(?=[\s'"`)]|$)/g
const ARBITRARY_SIZE = /(?:^|[\s'"`:(])(?:(?:sm|md|lg|xl|2xl):)?text-\[\d+(?:\.\d+)?px\]/g

const files = []
function walk(dir) {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) walk(full)
    else if (/\.(tsx?|css)$/.test(entry) && !/\.test\.tsx?$/.test(entry)) files.push(full)
  }
}
walk(ROOT)

const problems = []
for (const file of files) {
  const relative = path.relative(ROOT, file).split(path.sep).join('/')
  const content = readFileSync(file, 'utf8')
  const lines = content.split('\n')
  lines.forEach((line, index) => {
    const where = `${relative}:${index + 1}`
    if (relative !== 'index.css') {
      for (const match of line.matchAll(LEGACY_VARS)) problems.push(`${where}  alias historique ${match[0]} (utiliser le role : --primary, --danger, --accent, --success, --bg, --surface-2)`)
      for (const match of line.matchAll(LEGACY_CLASSES)) problems.push(`${where}  classe historique ${match[0].trim()}`)
    }
    if (!ARBITRARY_SIZE_ALLOWLIST.has(relative)) {
      for (const match of line.matchAll(ARBITRARY_SIZE)) {
        problems.push(`${where}  taille arbitraire ${match[0].trim()} (utiliser text-micro … text-hero, voir index.css)`)
      }
    }
  })
}

if (problems.length > 0) {
  process.stderr.write(`check-tokens : ${problems.length} ecart(s) avec la charte\n\n`)
  for (const problem of problems) process.stderr.write(`  ${problem}\n`)
  process.exit(1)
}
process.stdout.write(`check-tokens : ${files.length} fichiers conformes (echelle typographique et roles de couleur)\n`)
