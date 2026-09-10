/*
 * Genere les images SOURCES de @capacitor/assets (mobile/assets/) depuis le symbole
 * de la marque, avec le meme rasteriseur sans dependance que frontend/scripts/icons.mjs
 * (audit F319) : les PNG de frontend/public/icons font au plus 512 px, alors que l outil
 * exige 1024 px pour les icones et 2732 px pour les ecrans de demarrage. Le trace du
 * « E » est lu dans frontend/src/components/layout/Logo.tsx (LOGO_MARK_PATH) pour ne
 * jamais diverger du symbole rendu par l application.
 *
 *   node scripts/generate-sources.mjs      (ou : npm run assets:sources)
 *
 * puis `npm run assets` produit les ressources Android (mipmap-*, drawable-*) via
 * @capacitor/assets. Fichiers produits (noms imposes par l outil) :
 *   icon-only.png         1024x1024  icone opaque (iOS, Android < 8)
 *   icon-foreground.png   1024x1024  couche avant de l icone adaptative (fond transparent)
 *   icon-background.png   1024x1024  couche arriere (vert plein)
 *   splash.png            2732x2732  ecran de demarrage, theme clair
 *   splash-dark.png       2732x2732  ecran de demarrage, theme sombre
 */
import { deflateSync } from 'node:zlib'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
const OUT_DIR = path.resolve(here, '../assets')
const LOGO_SOURCE = path.resolve(here, '../../frontend/src/components/layout/Logo.tsx')

/* Couleurs de la charte (frontend/src/index.css, docs/CHARTE-GRAPHIQUE.md). */
const GREEN = [14, 124, 74] // --primary, vert Benin
const WHITE = [255, 255, 255]
const BG_LIGHT = [246, 246, 243] // --bg, theme clair
const BG_DARK = [13, 13, 15] // --bg, theme sombre
const TRANSPARENT = [0, 0, 0, 0]

/** Trace du symbole, decompose en rectangles + fleche dans le viewBox 32x32 (voir LOGO_MARK_PATH). */
const markPath = readFileSync(LOGO_SOURCE, 'utf8').match(/LOGO_MARK_PATH = '([^']+)'/)?.[1]
if (markPath !== 'M9 8h14v3.5H12.5v2.75H18v3.5h-5.5v2.75H23V24H9z M18 12.5 24.5 16 18 19.5z') {
  throw new Error(`Le trace LOGO_MARK_PATH a change (${markPath}) : mettre a jour la decomposition ci-dessous.`)
}

/** Formes du « E » a fleche, en unites du viewBox 32x32. */
const MARK_RECTS = [
  [9, 8, 12.5, 24], // montant
  [9, 8, 23, 11.5], // barre haute
  [9, 14.25, 18, 17.75], // barre centrale
  [9, 20.5, 23, 24], // barre basse
]
const MARK_ARROW = [18, 12.5, 24.5, 16, 18, 19.5]

/** Toile RGBA (le fond peut etre transparent : couche avant de l icone adaptative). */
class Canvas {
  constructor(size, background) {
    this.size = size
    this.pixels = new Uint8Array(size * size * 4)
    const bg = background.length === 4 ? background : [...background, 255]
    for (let i = 0; i < size * size; i++) this.pixels.set(bg, i * 4)
  }

  /** Rectangle a coins arrondis (rayon r), rempli, avec anticrenelage 4x4 sur les bords. */
  roundedRect(x0, y0, x1, y1, r, color) {
    const inside = (x, y) => {
      if (x < x0 || x > x1 || y < y0 || y > y1) return false
      const cx = x < x0 + r ? x0 + r : x > x1 - r ? x1 - r : x
      const cy = y < y0 + r ? y0 + r : y > y1 - r ? y1 - r : y
      return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    }
    this.fillShape(inside, Math.floor(x0), Math.floor(y0), Math.ceil(x1), Math.ceil(y1), color)
  }

  rect(x0, y0, x1, y1, color) {
    const inside = (x, y) => x >= x0 && x <= x1 && y >= y0 && y <= y1
    this.fillShape(inside, Math.floor(x0), Math.floor(y0), Math.ceil(x1), Math.ceil(y1), color)
  }

  triangle(ax, ay, bx, by, cx, cy, color) {
    const sign = (px, py, qx, qy, rx, ry) => (px - rx) * (qy - ry) - (qx - rx) * (py - ry)
    const inside = (x, y) => {
      const d1 = sign(x, y, ax, ay, bx, by)
      const d2 = sign(x, y, bx, by, cx, cy)
      const d3 = sign(x, y, cx, cy, ax, ay)
      const neg = d1 < 0 || d2 < 0 || d3 < 0
      const pos = d1 > 0 || d2 > 0 || d3 > 0
      return !(neg && pos)
    }
    this.fillShape(
      inside,
      Math.floor(Math.min(ax, bx, cx)),
      Math.floor(Math.min(ay, by, cy)),
      Math.ceil(Math.max(ax, bx, cx)),
      Math.ceil(Math.max(ay, by, cy)),
      color,
    )
  }

  /** Couverture par sur-echantillonnage 4x4, composee « source over » sur le fond (alpha compris). */
  fillShape(inside, minX, minY, maxX, maxY, color) {
    const S = 4
    for (let y = Math.max(0, minY); y < Math.min(this.size, maxY + 1); y++) {
      for (let x = Math.max(0, minX); x < Math.min(this.size, maxX + 1); x++) {
        let hits = 0
        for (let sy = 0; sy < S; sy++) {
          for (let sx = 0; sx < S; sx++) {
            if (inside(x + (sx + 0.5) / S, y + (sy + 0.5) / S)) hits += 1
          }
        }
        if (hits === 0) continue
        const srcA = hits / (S * S)
        const i = (y * this.size + x) * 4
        const dstA = this.pixels[i + 3] / 255
        const outA = srcA + dstA * (1 - srcA)
        for (let c = 0; c < 3; c++) {
          const dst = this.pixels[i + c] * dstA * (1 - srcA)
          this.pixels[i + c] = Math.round((color[c] * srcA + dst) / outA)
        }
        this.pixels[i + 3] = Math.round(outA * 255)
      }
    }
  }

  toPng() {
    const table = new Int32Array(256)
    for (let n = 0; n < 256; n++) {
      let c = n
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      table[n] = c
    }
    const crc32 = (buf) => {
      let c = -1
      for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
      return (c ^ -1) >>> 0
    }
    const chunk = (type, data) => {
      const len = Buffer.alloc(4)
      len.writeUInt32BE(data.length)
      const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
      const crc = Buffer.alloc(4)
      crc.writeUInt32BE(crc32(body))
      return Buffer.concat([len, body, crc])
    }
    const { size, pixels } = this
    const stride = size * 4
    const raw = Buffer.alloc((stride + 1) * size)
    for (let y = 0; y < size; y++) {
      raw[y * (stride + 1)] = 0
      Buffer.from(pixels.buffer, y * stride, stride).copy(raw, y * (stride + 1) + 1)
    }
    const ihdr = Buffer.alloc(13)
    ihdr.writeUInt32BE(size, 0)
    ihdr.writeUInt32BE(size, 4)
    ihdr[8] = 8 // 8 bits par canal
    ihdr[9] = 6 // RGBA
    return Buffer.concat([
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      chunk('IHDR', ihdr),
      chunk('IDAT', deflateSync(raw, { level: 9 })),
      chunk('IEND', Buffer.alloc(0)),
    ])
  }
}

/** Dessine le « E » a fleche dans un carre [ox, ox + side]. */
function drawMark(canvas, ox, oy, side) {
  const u = side / 32
  for (const [x0, y0, x1, y1] of MARK_RECTS) canvas.rect(ox + x0 * u, oy + y0 * u, ox + x1 * u, oy + y1 * u, WHITE)
  const [ax, ay, bx, by, cx, cy] = MARK_ARROW
  canvas.triangle(ox + ax * u, oy + ay * u, ox + bx * u, oy + by * u, ox + cx * u, oy + cy * u, WHITE)
}

/** Icone opaque : fond vert plein, symbole reduit (marge 12 %), comme apple-touch-icon. */
function iconOnly(size) {
  const canvas = new Canvas(size, GREEN)
  const margin = size * 0.12
  drawMark(canvas, margin, margin, size - margin * 2)
  return canvas
}

/**
 * Couche avant de l icone adaptative Android : symbole seul sur fond transparent.
 * @capacitor/assets place cette image dans la zone VISIBLE de l icone (72 dp sur 108,
 * inset de 16,7 % dans mipmap-anydpi-v26/ic_launcher.xml), pas dans la couche
 * complete : la zone sure (cercle de 66 dp) couvre donc 92 % de l image. Le « E »
 * n occupe que 48 % de son carre de trace, et son coin le plus eloigne reste a 66 %
 * du rayon : avec une marge de 6 %, le symbole fait ~42 % de l icone affichee (comme
 * dans le favicon) sans qu un masque rond ou carre arrondi ne le rogne.
 */
function iconForeground(size) {
  const canvas = new Canvas(size, TRANSPARENT)
  const margin = size * 0.06
  drawMark(canvas, margin, margin, size - margin * 2)
  return canvas
}

/** Ecran de demarrage : fond de la charte et symbole (carre vert a coins 28 %) au centre. */
function splash(size, background) {
  const canvas = new Canvas(size, background)
  const side = Math.round(size * 0.176) // 480 px sur 2732
  const o = (size - side) / 2
  canvas.roundedRect(o, o, o + side, o + side, side * 0.28, GREEN)
  drawMark(canvas, o, o, side)
  return canvas
}

mkdirSync(OUT_DIR, { recursive: true })
const outputs = [
  ['icon-only.png', iconOnly(1024)],
  ['icon-foreground.png', iconForeground(1024)],
  ['icon-background.png', new Canvas(1024, GREEN)],
  ['splash.png', splash(2732, BG_LIGHT)],
  ['splash-dark.png', splash(2732, BG_DARK)],
]
for (const [name, canvas] of outputs) {
  const png = canvas.toPng()
  writeFileSync(path.join(OUT_DIR, name), png)
  process.stdout.write(`${name} : ${canvas.size}x${canvas.size}, ${(png.length / 1024).toFixed(1)} Ko\n`)
}
