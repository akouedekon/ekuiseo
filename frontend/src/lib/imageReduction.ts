/*
 * Reduction des photos avant envoi (pieces d'identite, V20).
 *
 * Une photo prise avec un telephone recent pese couramment 4 a 12 Mo (12 a 48 Mpx), et
 * les iPhone produisent du HEIC : les deux depassent ce que le serveur accepte (5 Mo,
 * JPEG/PNG/WebP/PDF). Plutot que de renvoyer l'utilisateur vers un outil de conversion,
 * l'image est redessinee dans un canvas a 2 000 px de cote maximum et exportee en JPEG :
 * une piece d'identite reste parfaitement lisible, et le fichier tombe sous 1 Mo, ce qui
 * compte aussi sur un reseau mobile beninois. Le navigateur applique lui-meme l'orientation
 * EXIF au dessin (`image-orientation: from-image`, comportement par defaut).
 *
 * Les fonctions pures (`fitWithin`, `needsReduction`, `prepareIdentityDocument` avec
 * reduction injectable) sont testees ; `reduceImage` touche le DOM et ne l'est pas.
 */
import { guessDocumentType, validateIdentityDocument } from './identityDocuments'

/** Plus grand cote conserve : lisible pour un controle humain, leger a envoyer. */
export const IMAGE_MAX_SIDE = 2000
/** Qualite JPEG a l'export. */
export const IMAGE_JPEG_QUALITY = 0.85
/** En dessous de cette taille, une image deja acceptee part telle quelle (rien a gagner). */
export const REDUCE_ABOVE_BYTES = 1024 * 1024

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'webp', 'heic', 'heif', 'gif', 'bmp', 'tif', 'tiff', 'avif'])

/** Vrai pour tout ce qui ressemble a une image, y compris les formats que le serveur refuse (HEIC...). */
export function isImageFile(file: Pick<File, 'type' | 'name'>): boolean {
  if (file.type.toLowerCase().startsWith('image/')) return true
  if (file.type) return false
  const extension = file.name.toLowerCase().split('.').pop() ?? ''
  return IMAGE_EXTENSIONS.has(extension)
}

/**
 * Une image passe par la reduction quand elle est trop lourde pour le serveur ou dans un
 * format qu'il ne lit pas. Un JPEG de 300 Ko part tel quel.
 */
export function needsReduction(file: Pick<File, 'type' | 'name' | 'size'>): boolean {
  if (!isImageFile(file)) return false
  const accepted = guessDocumentType(file)
  return accepted === null || file.size > REDUCE_ABOVE_BYTES
}

/** Dimensions ramenees dans un carre de `maxSide`, proportions conservees, jamais agrandies. */
export function fitWithin(width: number, height: number, maxSide: number = IMAGE_MAX_SIDE): { width: number; height: number } {
  const longest = Math.max(width, height)
  if (longest <= maxSide) return { width, height }
  const ratio = maxSide / longest
  return { width: Math.max(1, Math.round(width * ratio)), height: Math.max(1, Math.round(height * ratio)) }
}

/** Le navigateur n'a pas su decoder l'image (HEIC hors Safari, fichier corrompu...). */
export class ImageDecodeError extends Error {
  constructor() {
    super('Format non pris en charge : envoyez une photo (JPEG, PNG, WebP) ou un PDF.')
    this.name = 'ImageDecodeError'
  }
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new ImageDecodeError())
    image.src = url
  })
}

function toBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new ImageDecodeError())), 'image/jpeg', IMAGE_JPEG_QUALITY)
  })
}

/** Redessine `file` en JPEG dans la limite de `maxSide` px de cote. Leve ImageDecodeError si illisible. */
export async function reduceImage(file: File, maxSide: number = IMAGE_MAX_SIDE): Promise<File> {
  const url = URL.createObjectURL(file)
  try {
    const image = await loadImage(url)
    const source = { width: image.naturalWidth || image.width, height: image.naturalHeight || image.height }
    if (source.width === 0 || source.height === 0) throw new ImageDecodeError()
    const target = fitWithin(source.width, source.height, maxSide)
    const canvas = document.createElement('canvas')
    canvas.width = target.width
    canvas.height = target.height
    const context = canvas.getContext('2d')
    if (!context) throw new ImageDecodeError()
    // Fond blanc : un PNG transparent exporte en JPEG deviendrait noir.
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, target.width, target.height)
    context.drawImage(image, 0, 0, target.width, target.height)
    const blob = await toBlob(canvas)
    const baseName = file.name.replace(/\.[^.]+$/, '') || 'photo'
    return new File([blob], `${baseName}.jpg`, { type: 'image/jpeg', lastModified: file.lastModified })
  } finally {
    URL.revokeObjectURL(url)
  }
}

/**
 * Fichier pret a partir : PDF et petites images inchanges, grosses images et formats
 * exotiques convertis en JPEG reduit. Leve une Error au message affichable si le fichier
 * ne peut pas etre envoye (format illisible, PDF trop lourd, fichier vide).
 */
export async function prepareIdentityDocument(file: File, reduce: (file: File) => Promise<File> = reduceImage): Promise<File> {
  if (file.size <= 0) throw new Error('Le fichier est vide.')
  let ready = file
  if (needsReduction(file)) {
    try {
      ready = await reduce(file)
    } catch (error) {
      if (error instanceof ImageDecodeError) throw error
      throw new ImageDecodeError()
    }
  }
  // Derniere garde, memes bornes que le serveur (type declare, taille) : un PDF de 8 Mo s'arrete ici.
  const problem = validateIdentityDocument(ready)
  if (problem) throw new Error(problem)
  return ready
}
