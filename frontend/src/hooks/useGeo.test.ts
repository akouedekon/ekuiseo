import { describe, expect, it } from 'vitest'
import type { GeoPlaceResponse } from '@/api/extended'
import { dedupePlaces, toCityOption } from './useGeo'

/* Audits F411 et F422 : referentiel serveur unique, quartiers rattaches a leur ville, jamais de doublon. */
describe('toCityOption', () => {
  it('compose « Quartier — Ville » pour un quartier et garde la nature du lieu', () => {
    const place: GeoPlaceResponse = {
      id: 'q1',
      name: 'Agla',
      region: 'Littoral',
      countryCode: 'BJ',
      kind: 'DISTRICT',
      lat: 6.3931,
      lng: 2.3892,
      parentId: 'c1',
      parentName: 'Cotonou',
    }
    const option = toCityOption(place)
    expect(option.label).toBe('Agla — Cotonou')
    expect(option.kind).toBe('DISTRICT')
    expect(option.parentName).toBe('Cotonou')
  })

  it('laisse une ville sans suffixe et remplace une region absente par le pays', () => {
    const lome = toCityOption({ id: 'l', name: 'Lomé', region: null, countryCode: 'TG', kind: 'CITY', lat: 6.13, lng: 1.22 })
    expect(lome.label).toBe('Lomé')
    expect(lome.region).toBe('TG')
    const cotonou = toCityOption({ id: 'c', name: 'Cotonou', region: null, countryCode: 'BJ', kind: 'CITY', lat: 6.37, lng: 2.39 })
    expect(cotonou.region).toBe('Bénin')
  })
})

describe('dedupePlaces', () => {
  it('fusionne meme identifiant, et meme nom a moins de 500 m', () => {
    const list = dedupePlaces([
      { id: 'a', label: 'Cotonou', lat: 6.3703, lng: 2.3912, region: 'Littoral' },
      { id: 'a', label: 'Cotonou', lat: 6.3703, lng: 2.3912, region: 'Littoral' },
      // Meme lieu venu du repli local (sans id), a 100 m : un doublon.
      { label: 'Cotonou', lat: 6.371, lng: 2.3915, region: 'Littoral' },
      // Homonyme a 30 km : un autre lieu.
      { label: 'Cotonou', lat: 6.6, lng: 2.39, region: 'Ailleurs' },
    ])
    expect(list).toHaveLength(2)
  })
})
