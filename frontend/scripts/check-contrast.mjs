/*
 * Contraste des paires de tokens de src/index.css (audits F314, F315), dans les
 * deux themes : texte >= 4,5:1 (WCAG 1.4.3), composants d'interface >= 3:1
 * (WCAG 1.4.11). Les valeurs sont lues dans la feuille de style, jamais
 * recopiees ici : modifier un token sans casser une paire est le seul chemin.
 *   node scripts/check-contrast.mjs   (execute par `npm run lint`)
 */
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const CSS = readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/index.css'), 'utf8')

/** Extrait les declarations `--token: valeur;` d'un bloc `:root { … }` ou `:root.dark { … }`. */
function readBlock(selector) {
  const start = CSS.indexOf(`${selector} {`)
  if (start < 0) throw new Error(`Bloc ${selector} introuvable dans index.css`)
  const end = CSS.indexOf('\n}', start)
  const body = CSS.slice(start, end)
  const tokens = new Map()
  for (const match of body.matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)) tokens.set(match[1], match[2].trim())
  return tokens
}

/** Resout `var(--x)` recursivement jusqu'a une couleur hexadecimale. */
function resolve(tokens, name, depth = 0) {
  const raw = tokens.get(name)
  if (!raw) throw new Error(`Token --${name} absent`)
  const alias = raw.match(/^var\(--([a-z0-9-]+)\)$/)
  if (alias) {
    if (depth > 8) throw new Error(`Alias circulaire sur --${name}`)
    return resolve(tokens, alias[1], depth + 1)
  }
  return raw
}

function hexToRgb(hex) {
  const value = hex.replace('#', '')
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value
  if (!/^[0-9a-f]{6}$/i.test(full)) throw new Error(`Couleur non hexadecimale : ${hex}`)
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16))
}

function luminance([r, g, b]) {
  const channel = (v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

function contrast(a, b) {
  const la = luminance(hexToRgb(a))
  const lb = luminance(hexToRgb(b))
  const [light, dark] = la > lb ? [la, lb] : [lb, la]
  return (light + 0.05) / (dark + 0.05)
}

/** [texte ou element, fond, seuil, description]. */
const PAIRS = [
  // Texte sur les neutres
  ['ink', 'bg', 4.5, 'texte principal sur le fond'],
  ['ink', 'surface', 4.5, 'texte principal sur une carte'],
  ['ink', 'surface-2', 4.5, 'texte principal sur zone secondaire'],
  ['ink-2', 'bg', 4.5, 'texte secondaire sur le fond'],
  ['ink-2', 'surface', 4.5, 'texte secondaire sur une carte'],
  ['ink-2', 'surface-2', 4.5, 'texte secondaire sur zone secondaire'],
  ['muted', 'bg', 4.5, 'texte attenue sur le fond'],
  ['muted', 'surface', 4.5, 'texte attenue sur une carte'],
  ['muted', 'surface-2', 4.5, 'texte attenue sur zone secondaire'],
  // Encres sur fonds pales (la teinte pleine n'y est jamais posee en texte)
  ['primary-ink', 'primary-soft', 4.5, 'encre primaire sur fond pale'],
  ['accent-ink', 'accent-soft', 4.5, 'encre accent sur fond pale'],
  ['danger-ink', 'danger-soft', 4.5, 'encre danger sur fond pale'],
  ['primary-ink', 'surface', 4.5, 'encre primaire (liens) sur une carte'],
  ['primary-ink', 'bg', 4.5, 'encre primaire (liens) sur le fond'],
  ['danger-ink', 'surface', 4.5, 'message d’erreur sous un champ'],
  ['danger-ink', 'bg', 4.5, 'encre danger sur le fond'],
  ['accent-ink', 'surface', 4.5, 'encre accent sur une carte'],
  // Texte sur teinte pleine (boutons)
  ['primary-contrast', 'primary', 4.5, 'texte des boutons primaires'],
  ['primary', 'bg', 4.5, 'onglet actif de la barre basse (libelle 11 px, gras)'],
  ['accent-contrast', 'accent', 4.5, 'texte sur accent plein'],
  ['danger-contrast', 'danger', 4.5, 'texte des boutons danger'],
  // Composants d'interface (bordures, anneaux, icones) : 3:1
  ['field-border', 'surface', 3, 'contour des champs de saisie'],
  ['field-border', 'bg', 3, 'contour des cases et radios sur le fond'],
  ['focus-ring', 'surface', 3, 'anneau de focus sur une carte'],
  ['focus-ring', 'bg', 3, 'anneau de focus sur le fond'],
  ['primary', 'surface', 3, 'icones et filets primaires'],
  ['danger', 'surface', 3, 'marqueur de destination, filets danger'],
  /*
   * Le jaune plein (--accent, 1,98:1 sur blanc en theme clair) ne porte jamais
   * seul une information : les etoiles sont remplies en accent mais contourees
   * en accent-ink, les bandeaux d'attente posent leur texte en accent-ink sur
   * accent-soft. C'est cette encre qui est mesuree ci-dessous.
   */
  ['accent-ink', 'bg', 3, 'contour des etoiles et pictogrammes d’attente'],
]

let failures = 0
for (const theme of [':root', ':root.dark']) {
  const tokens = readBlock(theme)
  process.stdout.write(`\n${theme === ':root' ? 'Theme clair' : 'Theme sombre'}\n`)
  for (const [fg, bg, min, label] of PAIRS) {
    const ratio = contrast(resolve(tokens, fg), resolve(tokens, bg))
    const ok = ratio >= min
    if (!ok) failures += 1
    process.stdout.write(`  ${ok ? 'ok ' : 'KO '} ${ratio.toFixed(2).padStart(5)}:1  (min ${min})  --${fg} / --${bg}  ${label}\n`)
  }
}

if (failures > 0) {
  process.stderr.write(`\ncheck-contrast : ${failures} paire(s) sous le seuil\n`)
  process.exit(1)
}
process.stdout.write(`\ncheck-contrast : toutes les paires respectent la charte\n`)
