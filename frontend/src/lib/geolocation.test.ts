import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_POSITION_TIMEOUT_MS,
  GEOLOCATION_MESSAGES,
  GeolocationError,
  getCurrentPosition,
  isGeolocationSupported,
  translateGeolocationError,
} from './geolocation'

type SuccessCb = (position: { coords: { latitude: number; longitude: number; accuracy: number } }) => void
type ErrorCb = (error: { code: number; message: string }) => void

function stubGeolocation(impl: (ok: SuccessCb, ko: ErrorCb, options: PositionOptions | undefined) => void) {
  const getCurrentPositionMock = vi.fn(impl)
  vi.stubGlobal('navigator', { geolocation: { getCurrentPosition: getCurrentPositionMock } })
  return getCurrentPositionMock
}

describe('translateGeolocationError', () => {
  it('traduit les trois codes du navigateur en messages francais, et l inconnu en « indisponible »', () => {
    expect(translateGeolocationError({ code: 1 })).toMatchObject({ code: 'denied', message: GEOLOCATION_MESSAGES.denied })
    expect(translateGeolocationError({ code: 2 })).toMatchObject({ code: 'unavailable', message: GEOLOCATION_MESSAGES.unavailable })
    expect(translateGeolocationError({ code: 3 })).toMatchObject({ code: 'timeout', message: GEOLOCATION_MESSAGES.timeout })
    expect(translateGeolocationError(undefined)).toMatchObject({ code: 'unavailable' })
    expect(translateGeolocationError({ code: 99 })).toMatchObject({ code: 'unavailable' })
    expect(translateGeolocationError({ code: 1 })).toBeInstanceOf(GeolocationError)
    expect(translateGeolocationError({ code: 1 })).toBeInstanceOf(Error)
  })

  it('redige chaque message en francais avec un repli explicite quand la position est refusee ou en retard', () => {
    expect(GEOLOCATION_MESSAGES.denied).toContain('refusé')
    expect(GEOLOCATION_MESSAGES.denied).toContain('lieu de départ')
    expect(GEOLOCATION_MESSAGES.timeout).toContain('lieu de départ')
    expect(GEOLOCATION_MESSAGES.unsupported).toContain('appareil')
  })
})

describe('getCurrentPosition', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('rejette « unsupported » quand l API est absente', async () => {
    vi.stubGlobal('navigator', {})
    expect(isGeolocationSupported()).toBe(false)
    await expect(getCurrentPosition()).rejects.toMatchObject({ code: 'unsupported', message: GEOLOCATION_MESSAGES.unsupported })
  })

  it('resout la position avec la precision, en haute precision et 10 s de delai par defaut', async () => {
    const mock = stubGeolocation((ok) => ok({ coords: { latitude: 6.3703, longitude: 2.3912, accuracy: 25 } }))
    expect(isGeolocationSupported()).toBe(true)

    await expect(getCurrentPosition()).resolves.toEqual({ lat: 6.3703, lng: 2.3912, accuracyM: 25 })
    expect(mock.mock.calls[0][2]).toEqual({ enableHighAccuracy: true, timeout: DEFAULT_POSITION_TIMEOUT_MS, maximumAge: 0 })
  })

  it('transmet les options demandees', async () => {
    const mock = stubGeolocation((ok) => ok({ coords: { latitude: 6.45, longitude: 2.35, accuracy: Number.NaN } }))

    await expect(getCurrentPosition({ timeoutMs: 3_000, maximumAgeMs: 60_000, highAccuracy: false })).resolves.toEqual({
      lat: 6.45,
      lng: 2.35,
      accuracyM: 0,
    })
    expect(mock.mock.calls[0][2]).toEqual({ enableHighAccuracy: false, timeout: 3_000, maximumAge: 60_000 })
  })

  it('rejette avec le message traduit quand le navigateur refuse ou echoue', async () => {
    stubGeolocation((_ok, ko) => ko({ code: 1, message: 'User denied Geolocation' }))
    await expect(getCurrentPosition()).rejects.toMatchObject({ code: 'denied', message: GEOLOCATION_MESSAGES.denied })

    stubGeolocation((_ok, ko) => ko({ code: 3, message: 'Timeout expired' }))
    await expect(getCurrentPosition()).rejects.toMatchObject({ code: 'timeout' })
  })

  it('traite une coordonnee non finie comme une position indisponible', async () => {
    stubGeolocation((ok) => ok({ coords: { latitude: Number.NaN, longitude: 2.39, accuracy: 10 } }))
    await expect(getCurrentPosition()).rejects.toMatchObject({ code: 'unavailable' })
  })
})
