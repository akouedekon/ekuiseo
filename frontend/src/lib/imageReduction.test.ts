import { describe, expect, it, vi } from 'vitest'
import { IDENTITY_DOCUMENT_MAX_BYTES } from './identityDocuments'
import { ImageDecodeError, REDUCE_ABOVE_BYTES, fitWithin, isImageFile, needsReduction, prepareIdentityDocument } from './imageReduction'

/** File de test : `size` force sans construire un vrai contenu. */
function fakeFile(name: string, type: string, size: number): File {
  const file = new File(['x'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

describe('fitWithin', () => {
  it('reduit le plus grand cote a la limite en gardant les proportions, sans jamais agrandir', () => {
    expect(fitWithin(4000, 3000, 2000)).toEqual({ width: 2000, height: 1500 })
    expect(fitWithin(3000, 4000, 2000)).toEqual({ width: 1500, height: 2000 })
    expect(fitWithin(800, 600, 2000)).toEqual({ width: 800, height: 600 })
    expect(fitWithin(2000, 2000, 2000)).toEqual({ width: 2000, height: 2000 })
  })
})

describe('isImageFile / needsReduction', () => {
  it('reconnait une image par son type ou son extension, HEIC compris', () => {
    expect(isImageFile({ type: 'image/heic', name: 'IMG_1.HEIC' })).toBe(true)
    expect(isImageFile({ type: '', name: 'IMG_1.heic' })).toBe(true)
    expect(isImageFile({ type: 'application/pdf', name: 'a.pdf' })).toBe(false)
    expect(isImageFile({ type: '', name: 'notes.txt' })).toBe(false)
  })

  it('reduit les images lourdes et les formats que le serveur refuse, laisse passer un petit JPEG', () => {
    expect(needsReduction({ type: 'image/jpeg', name: 'a.jpg', size: 300_000 })).toBe(false)
    expect(needsReduction({ type: 'image/jpeg', name: 'a.jpg', size: REDUCE_ABOVE_BYTES + 1 })).toBe(true)
    expect(needsReduction({ type: 'image/heic', name: 'a.heic', size: 300_000 })).toBe(true)
    expect(needsReduction({ type: 'application/pdf', name: 'a.pdf', size: 9_000_000 })).toBe(false)
  })
})

describe('prepareIdentityDocument', () => {
  it('laisse un petit JPEG et un PDF inchanges, sans appeler la reduction', async () => {
    const reduce = vi.fn()
    const jpeg = fakeFile('cni.jpg', 'image/jpeg', 400_000)
    const pdf = fakeFile('cni.pdf', 'application/pdf', 2_000_000)
    await expect(prepareIdentityDocument(jpeg, reduce)).resolves.toBe(jpeg)
    await expect(prepareIdentityDocument(pdf, reduce)).resolves.toBe(pdf)
    expect(reduce).not.toHaveBeenCalled()
  })

  it('convertit une photo de 8 Mo (ou un HEIC) en JPEG reduit avant l envoi', async () => {
    const reduced = fakeFile('IMG_0001.jpg', 'image/jpeg', 700_000)
    const reduce = vi.fn(async () => reduced)
    await expect(prepareIdentityDocument(fakeFile('IMG_0001.jpg', 'image/jpeg', 8_000_000), reduce)).resolves.toBe(reduced)
    await expect(prepareIdentityDocument(fakeFile('IMG_0002.HEIC', 'image/heic', 2_000_000), reduce)).resolves.toBe(reduced)
    expect(reduce).toHaveBeenCalledTimes(2)
  })

  it('explique un format illisible, un PDF trop lourd ou un fichier vide', async () => {
    const reduce = vi.fn(async () => {
      throw new ImageDecodeError()
    })
    await expect(prepareIdentityDocument(fakeFile('x.heic', 'image/heic', 1000), reduce)).rejects.toThrow(/Format non pris en charge/)
    await expect(prepareIdentityDocument(fakeFile('x.pdf', 'application/pdf', IDENTITY_DOCUMENT_MAX_BYTES + 1), reduce)).rejects.toThrow(/5 Mo/)
    await expect(prepareIdentityDocument(fakeFile('x.jpg', 'image/jpeg', 0), reduce)).rejects.toThrow(/vide/)
    await expect(prepareIdentityDocument(fakeFile('notes.txt', 'text/plain', 10), reduce)).rejects.toThrow(/Format/)
  })

  it('traduit une erreur inattendue du canvas en message de format', async () => {
    const reduce = vi.fn(async () => {
      throw new TypeError('canvas indisponible')
    })
    await expect(prepareIdentityDocument(fakeFile('x.jpg', 'image/jpeg', 6_000_000), reduce)).rejects.toThrow(/Format non pris en charge/)
  })
})
