import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '@/api/client'
import { describeError, errorStatus, isTransientError } from './errors'

describe('describeError', () => {
  it('reprend le detail metier du serveur pour un 4xx', () => {
    const error = new ApiError(409, { status: 409, detail: 'Plus de place disponible' }, 'Erreur HTTP 409')
    expect(describeError(error)).toBe('Plus de place disponible')
  })

  it('masque le detail technique d’un 500', () => {
    const error = new ApiError(500, { status: 500, detail: 'NullPointerException at …' }, 'Erreur HTTP 500')
    expect(describeError(error)).toMatch(/momentanément indisponible/)
  })

  it('explique une session expiree et un acces refuse', () => {
    expect(describeError(new ApiError(401, null, 'x'))).toMatch(/session a expiré/)
    expect(describeError(new ApiError(403, null, 'x'))).toMatch(/droits/)
  })

  it('distingue hors ligne, delai depasse et serveur injoignable', () => {
    expect(describeError(new NetworkError('offline', 'x'))).toMatch(/hors ligne/)
    expect(describeError(new NetworkError('timeout', 'x'))).toMatch(/trop de temps/)
    expect(describeError(new NetworkError('unreachable', 'x'))).toMatch(/Impossible de joindre/)
  })

  it('retombe sur le message de repli pour une erreur inconnue', () => {
    expect(describeError(new Error('boom'), 'Repli')).toBe('Repli')
  })
})

describe('isTransientError', () => {
  it('ne reessaie que sur reseau ou 502/503/504', () => {
    expect(isTransientError(new NetworkError('timeout', 'x'))).toBe(true)
    expect(isTransientError(new ApiError(503, null, 'x'))).toBe(true)
    expect(isTransientError(new ApiError(500, null, 'x'))).toBe(false)
    expect(isTransientError(new ApiError(404, null, 'x'))).toBe(false)
    expect(isTransientError(new ApiError(403, null, 'x'))).toBe(false)
  })
})

describe('errorStatus', () => {
  it('extrait le statut HTTP ou rien', () => {
    expect(errorStatus(new ApiError(422, null, 'x'))).toBe(422)
    expect(errorStatus(new Error('x'))).toBeUndefined()
  })
})
