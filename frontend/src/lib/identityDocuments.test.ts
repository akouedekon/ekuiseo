import { describe, expect, it } from 'vitest'
import {
  IDENTITY_DOCUMENT_ACCEPT,
  IDENTITY_DOCUMENT_MAX_BYTES,
  formatFileSize,
  guessDocumentType,
  sideLabel,
  validateIdentityDocument,
} from './identityDocuments'

describe('validateIdentityDocument', () => {
  const file = (overrides: Partial<{ type: string; size: number; name: string }>) => ({
    type: 'image/jpeg',
    size: 250_000,
    name: 'cni.jpg',
    ...overrides,
  })

  it('accepte une photo ou un PDF sous 5 Mo', () => {
    expect(validateIdentityDocument(file({}))).toBeNull()
    expect(validateIdentityDocument(file({ type: 'image/png', name: 'a.png' }))).toBeNull()
    expect(validateIdentityDocument(file({ type: 'image/webp', name: 'a.webp' }))).toBeNull()
    expect(validateIdentityDocument(file({ type: 'application/pdf', name: 'a.pdf' }))).toBeNull()
    expect(validateIdentityDocument(file({ size: IDENTITY_DOCUMENT_MAX_BYTES }))).toBeNull()
  })

  it('refuse un fichier vide, trop gros ou d un autre format, avec un message lisible', () => {
    expect(validateIdentityDocument(file({ size: 0 }))).toMatch(/vide/)
    expect(validateIdentityDocument(file({ size: IDENTITY_DOCUMENT_MAX_BYTES + 1 }))).toMatch(/5 Mo/)
    expect(validateIdentityDocument(file({ type: 'image/gif', name: 'a.gif' }))).toMatch(/Format non pris en charge/)
    expect(validateIdentityDocument(file({ type: 'application/x-msdownload', name: 'virus.jpg' }))).toMatch(/Format/)
  })

  it('se replie sur l extension quand le navigateur ne declare aucun type (selecteurs Android)', () => {
    expect(guessDocumentType({ type: '', name: 'IMG_0001.JPG' })).toBe('image/jpeg')
    expect(guessDocumentType({ type: '', name: 'scan.pdf' })).toBe('application/pdf')
    expect(guessDocumentType({ type: '', name: 'inconnu' })).toBeNull()
    expect(guessDocumentType({ type: 'image/jpg', name: 'x' })).toBe('image/jpeg')
    expect(validateIdentityDocument(file({ type: '', name: 'photo.webp' }))).toBeNull()
  })

  it('expose la liste des types acceptes par le champ fichier', () => {
    expect(IDENTITY_DOCUMENT_ACCEPT).toBe('image/*,application/pdf')
  })
})

describe('formatFileSize', () => {
  it('ecrit les tailles a la francaise', () => {
    expect(formatFileSize(512)).toBe('512 o')
    expect(formatFileSize(340 * 1024)).toBe('340 Ko')
    expect(formatFileSize(1.25 * 1024 * 1024)).toBe('1,3 Mo')
  })
})

describe('sideLabel', () => {
  it('nomme chaque face', () => {
    expect(sideLabel('FRONT')).toMatch(/Recto/)
    expect(sideLabel('BACK')).toMatch(/Verso/)
    expect(sideLabel('SELFIE')).toMatch(/Selfie/)
  })
})
