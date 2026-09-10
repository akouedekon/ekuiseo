import { afterEach, describe, expect, it, vi } from 'vitest'
import { hapticSuccess, isNativeApp, nativePlatform, shareNative, syncStatusBar } from './native'

/** Hors de l application (jsdom = navigateur), tout est neutre ou retombe sur l API web. */
describe('native (navigateur)', () => {
  afterEach(() => {
    delete (navigator as { share?: unknown }).share
  })

  it('ne se croit jamais dans l application depuis un navigateur', () => {
    expect(isNativeApp()).toBe(false)
    expect(nativePlatform()).toBe('web')
  })

  it('les fonctions natives sont sans effet et ne levent pas', async () => {
    await expect(hapticSuccess()).resolves.toBeUndefined()
    await expect(syncStatusBar(true)).resolves.toBeUndefined()
  })

  it('shareNative utilise navigator.share quand il existe, et dit faux sinon', async () => {
    const data = { title: 'Trajet', text: 'Cotonou → Bohicon', url: 'https://ekuiseo.com/trips/1' }
    await expect(shareNative(data)).resolves.toBe(false)

    const share = vi.fn(async () => undefined)
    Object.defineProperty(navigator, 'share', { configurable: true, value: share })
    await expect(shareNative(data)).resolves.toBe(true)
    expect(share).toHaveBeenCalledWith(data)

    // Feuille fermee par l utilisateur : ce n est pas une erreur.
    share.mockRejectedValueOnce(new DOMException('Share canceled', 'AbortError'))
    await expect(shareNative(data)).resolves.toBe(true)
    // Autre echec : remonte a l appelant, qui copiera le lien.
    share.mockRejectedValueOnce(new Error('boom'))
    await expect(shareNative(data)).rejects.toThrow('boom')
  })
})
