/*
 * Genere public/og-image.png (1200 x 630) sans dependance : encodeur PNG
 * minimal (zlib de Node) et rasterisation de formes simples. Aucun texte n'est
 * rendu (pas de moteur de polices) : le visuel est le symbole de la marque sur
 * le vert Ekuiseo, avec le filet tricolore ; la version SVG (public/og-image.svg)
 * porte le nom et la promesse. A relancer si la charte change :
 *   node scripts/og-image.mjs
 */
import { deflateSync } from 'node:zlib'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const W = 1200
const H = 630

const GREEN = [14, 124, 74]
const GREEN_DEEP = [9, 96, 58]
const WHITE = [255, 255, 255]
const YELLOW = [252, 209, 22]
const RED = [232, 17, 45]

const pixels = new Uint8Array(W * H * 3)

function fill(x0, y0, x1, y1, color, alpha = 1) {
  const xa = Math.max(0, Math.floor(x0))
  const ya = Math.max(0, Math.floor(y0))
  const xb = Math.min(W, Math.ceil(x1))
  const yb = Math.min(H, Math.ceil(y1))
  for (let y = ya; y < yb; y++) {
    for (let x = xa; x < xb; x++) {
      const i = (y * W + x) * 3
      pixels[i] = Math.round(pixels[i] * (1 - alpha) + color[0] * alpha)
      pixels[i + 1] = Math.round(pixels[i + 1] * (1 - alpha) + color[1] * alpha)
      pixels[i + 2] = Math.round(pixels[i + 2] * (1 - alpha) + color[2] * alpha)
    }
  }
}

/** Rectangle a coins arrondis (rayon r), rempli. */
function roundedRect(x0, y0, x1, y1, r, color) {
  for (let y = Math.floor(y0); y < Math.ceil(y1); y++) {
    for (let x = Math.floor(x0); x < Math.ceil(x1); x++) {
      const cx = x < x0 + r ? x0 + r : x > x1 - r ? x1 - r : x
      const cy = y < y0 + r ? y0 + r : y > y1 - r ? y1 - r : y
      const dx = x - cx
      const dy = y - cy
      if (dx * dx + dy * dy <= r * r) {
        const i = (y * W + x) * 3
        pixels[i] = color[0]
        pixels[i + 1] = color[1]
        pixels[i + 2] = color[2]
      }
    }
  }
}

/** Triangle plein (fleche du symbole). */
function triangle(ax, ay, bx, by, cx, cy, color) {
  const minX = Math.floor(Math.min(ax, bx, cx))
  const maxX = Math.ceil(Math.max(ax, bx, cx))
  const minY = Math.floor(Math.min(ay, by, cy))
  const maxY = Math.ceil(Math.max(ay, by, cy))
  const sign = (px, py, qx, qy, rx, ry) => (px - rx) * (qy - ry) - (qx - rx) * (py - ry)
  for (let y = minY; y < maxY; y++) {
    for (let x = minX; x < maxX; x++) {
      const d1 = sign(x + 0.5, y + 0.5, ax, ay, bx, by)
      const d2 = sign(x + 0.5, y + 0.5, bx, by, cx, cy)
      const d3 = sign(x + 0.5, y + 0.5, cx, cy, ax, ay)
      const neg = d1 < 0 || d2 < 0 || d3 < 0
      const pos = d1 > 0 || d2 > 0 || d3 > 0
      if (!(neg && pos)) {
        const i = (y * W + x) * 3
        pixels[i] = color[0]
        pixels[i + 1] = color[1]
        pixels[i + 2] = color[2]
      }
    }
  }
}

// Fond : vert de marque, avec une nappe plus sombre en bas a droite.
fill(0, 0, W, H, GREEN)
for (let y = 0; y < H; y++) {
  fill(0, y, W, y + 1, GREEN_DEEP, (y / H) * 0.55)
}
// Grille pointillee discrete (motif « ek-dots » de l'application).
for (let y = 24; y < H; y += 32) {
  for (let x = 24; x < W; x += 32) {
    fill(x, y, x + 2, y + 2, WHITE, 0.12)
  }
}

// Symbole : carre blanc a coins arrondis portant le « E » vert dont le bras central devient une fleche.
const S = 300
const sx = (W - S) / 2
const sy = (H - S) / 2 - 30
roundedRect(sx, sy, sx + S, sy + S, S * 0.28, WHITE)
const u = S / 32 // unite du viewBox 32x32 du logo
const gx = (v) => sx + v * u
const gy = (v) => sy + v * u
// Chemin du logo (LOGO_MARK_PATH) : M9 8h14v3.5H12.5v2.75H18v3.5h-5.5v2.75H23V24H9z, decompose en rectangles.
fill(gx(9), gy(8), gx(12.5), gy(24), GREEN) // montant
fill(gx(9), gy(8), gx(23), gy(11.5), GREEN) // barre haute
fill(gx(9), gy(14.25), gx(18), gy(17.75), GREEN) // barre centrale
fill(gx(9), gy(20.5), gx(23), gy(24), GREEN) // barre basse
triangle(gx(18), gy(12.5), gx(24.5), gy(16), gx(18), gy(19.5), GREEN) // fleche

// Filet tricolore (signature de l'en-tete) sous le symbole.
const barY = sy + S + 48
const barW = 420
const bx0 = (W - barW) / 2
fill(bx0, barY, bx0 + barW / 3, barY + 10, WHITE)
fill(bx0 + barW / 3, barY, bx0 + (2 * barW) / 3, barY + 10, YELLOW)
fill(bx0 + (2 * barW) / 3, barY, bx0 + barW, barY + 10, RED)

/* ---------------------------------------------------------------- PNG */

const table = new Int32Array(256)
for (let n = 0; n < 256; n++) {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  table[n] = c
}
function crc32(buf) {
  let c = -1
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ -1) >>> 0
}
function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}

const raw = Buffer.alloc((W * 3 + 1) * H)
for (let y = 0; y < H; y++) {
  raw[y * (W * 3 + 1)] = 0 // filtre « none »
  Buffer.from(pixels.buffer, y * W * 3, W * 3).copy(raw, y * (W * 3 + 1) + 1)
}
const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(W, 0)
ihdr.writeUInt32BE(H, 4)
ihdr[8] = 8 // profondeur
ihdr[9] = 2 // couleur RVB
ihdr[10] = 0
ihdr[11] = 0
ihdr[12] = 0

const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
])

const out = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../public/og-image.png')
writeFileSync(out, png)
process.stdout.write(`og-image.png : ${W}x${H}, ${(png.length / 1024).toFixed(1)} Ko -> ${out}
`)
