/*
 * Genere les icones PNG du manifeste PWA depuis le symbole de la marque
 * (audit F319) : icon-192.png, icon-512.png, maskable-512.png (marge de securite
 * de 20 %, fond vert plein) et apple-touch-icon.png (180 px). Sans dependance :
 * meme encodeur PNG et memes primitives de rasterisation que og-image.mjs. Le
 * trace du « E » est lu dans src/components/layout/Logo.tsx (LOGO_MARK_PATH)
 * pour ne jamais diverger du symbole rendu par l'application.
 *   node scripts/icons.mjs
 */
import { deflateSync } from 'node:zlib'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
const OUT_DIR = path.resolve(here, '../public/icons')
const LOGO_SOURCE = path.resolve(here, '../src/components/layout/Logo.tsx')

/* Couleurs de la charte (src/index.css, theme clair) : vert Benin plein et blanc. */
const GREEN = [14, 124, 74]
const WHITE = [255, 255, 255]

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

class Canvas {
  constructor(size, background) {
    this.size = size
    this.pixels = new Uint8Array(size * size * 3)
    for (let i = 0; i < size * size; i++) this.pixels.set(background, i * 3)
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

  /** Couverture par sur-echantillonnage 4x4 : bords lisses a 192 px comme a 512 px. */
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
        const alpha = hits / (S * S)
        const i = (y * this.size + x) * 3
        for (let c = 0; c < 3; c++) this.pixels[i + c] = Math.round(this.pixels[i + c] * (1 - alpha) + color[c] * alpha)
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
    const raw = Buffer.alloc((size * 3 + 1) * size)
    for (let y = 0; y < size; y++) {
      raw[y * (size * 3 + 1)] = 0
      Buffer.from(pixels.buffer, y * size * 3, size * 3).copy(raw, y * (size * 3 + 1) + 1)
    }
    const ihdr = Buffer.alloc(13)
    ihdr.writeUInt32BE(size, 0)
    ihdr.writeUInt32BE(size, 4)
    ihdr[8] = 8
    ihdr[9] = 2
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

/**
 * Icone « any » : le symbole tel quel (carre vert a coins 28 %, E blanc) sur un
 * fond blanc, comme le favicon. Le carre occupe tout le cadre : l'OS applique
 * son propre masque.
 */
function anyIcon(size) {
  const canvas = new Canvas(size, WHITE)
  canvas.roundedRect(0, 0, size, size, size * 0.28, GREEN)
  drawMark(canvas, 0, 0, size)
  return canvas
}

/**
 * Icone « maskable » : fond vert plein bord a bord et symbole reduit dans la zone
 * sure (marge de 20 % de chaque cote), pour que ni cercle ni carre arrondi ne
 * rogne le « E ».
 */
function maskableIcon(size) {
  const canvas = new Canvas(size, GREEN)
  const margin = size * 0.2
  drawMark(canvas, margin, margin, size - margin * 2)
  return canvas
}

mkdirSync(OUT_DIR, { recursive: true })
const outputs = [
  ['icon-192.png', anyIcon(192)],
  ['icon-512.png', anyIcon(512)],
  ['maskable-512.png', maskableIcon(512)],
  // iOS applique ses propres coins : fond vert plein, symbole legerement reduit (marge 12 %).
  ['apple-touch-icon.png', (() => {
    const canvas = new Canvas(180, GREEN)
    const margin = 180 * 0.12
    drawMark(canvas, margin, margin, 180 - margin * 2)
    return canvas
  })()],
]
for (const [name, canvas] of outputs) {
  const png = canvas.toPng()
  writeFileSync(path.join(OUT_DIR, name), png)
  process.stdout.write(`${name} : ${canvas.size}x${canvas.size}, ${(png.length / 1024).toFixed(1)} Ko\n`)
}
