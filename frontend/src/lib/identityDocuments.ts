/*
 * Pieces d'identite televersees (V20) : faces attendues, controle local d'un fichier
 * avant envoi (type et taille, memes bornes que le serveur, qui reverifie par les
 * octets), formats d'affichage. Fichier sans composant, testable en isolation.
 */

export type IdentityDocumentSide = 'FRONT' | 'BACK' | 'SELFIE'

/** 5 Mo, comme `spring.servlet.multipart.max-file-size` et IdentityDocumentService.MAX_SIZE_BYTES. */
export const IDENTITY_DOCUMENT_MAX_BYTES = 5 * 1024 * 1024

export const IDENTITY_DOCUMENT_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'] as const
export type IdentityDocumentType = (typeof IDENTITY_DOCUMENT_TYPES)[number]

/**
 * Valeur de l'attribut `accept` du champ fichier : toute image (HEIC des iPhone compris,
 * converti en JPEG par lib/imageReduction.ts avant l'envoi) ou un PDF.
 */
export const IDENTITY_DOCUMENT_ACCEPT = 'image/*,application/pdf'

export const IDENTITY_SIDES: { side: IdentityDocumentSide; label: string; hint: string }[] = [
  { side: 'FRONT', label: 'Recto de la pièce', hint: 'Photo nette, les quatre coins visibles, sans reflet.' },
  { side: 'BACK', label: 'Verso de la pièce', hint: 'Inutile pour un passeport.' },
  { side: 'SELFIE', label: 'Selfie avec la pièce', hint: 'Votre visage et la pièce sur la même photo.' },
]

export function sideLabel(side: IdentityDocumentSide): string {
  return IDENTITY_SIDES.find((entry) => entry.side === side)?.label ?? side
}

const EXTENSION_TYPES: Record<string, IdentityDocumentType> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
  pdf: 'application/pdf',
}

/**
 * Type d'un fichier tel que le navigateur le declare, avec repli sur l'extension :
 * certains selecteurs Android renvoient un `type` vide. Le serveur, lui, ne se fie
 * qu'aux octets. Null si inconnu.
 */
export function guessDocumentType(file: Pick<File, 'type' | 'name'>): IdentityDocumentType | null {
  const declared = file.type.toLowerCase()
  if ((IDENTITY_DOCUMENT_TYPES as readonly string[]).includes(declared)) return declared as IdentityDocumentType
  if (declared === 'image/jpg') return 'image/jpeg'
  if (declared) return null
  const extension = file.name.toLowerCase().split('.').pop() ?? ''
  return EXTENSION_TYPES[extension] ?? null
}

/** Message d'erreur affichable, ou null si le fichier peut partir. */
export function validateIdentityDocument(file: Pick<File, 'type' | 'size' | 'name'>): string | null {
  if (file.size <= 0) return 'Le fichier est vide.'
  if (!guessDocumentType(file)) return 'Format non pris en charge : envoyez une photo (JPEG, PNG, WebP) ou un PDF.'
  if (file.size > IDENTITY_DOCUMENT_MAX_BYTES) {
    return `Fichier trop volumineux (${formatFileSize(file.size)}) : 5 Mo au maximum.`
  }
  return null
}

export function isImageType(contentType: string): boolean {
  return contentType.startsWith('image/')
}

/** « 340 Ko », « 1,2 Mo » : virgule decimale, comme partout dans l'interface. */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} o`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} Ko`
  return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} Mo`
}
