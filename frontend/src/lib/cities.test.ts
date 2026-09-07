import { describe, expect, it } from 'vitest'
import { FALLBACK_PLACES, haversineKm, searchPlaces, searchRadiusKm, type CityOption } from './cities'

function place(label: string): CityOption {
  const found = FALLBACK_PLACES.find((city) => city.label === label)
  if (!found) throw new Error(`Ville inconnue : ${label}`)
  return found
}

function axisKm(from: string, to: string): number {
  const a = place(from)
  const b = place(to)
  return haversineKm(a.lat, a.lng, b.lat, b.lng)
}

describe('searchRadiusKm', () => {
  it('serre le rayon a 5 km sur un axe urbain, plafonne a la moitie de l axe', () => {
    // Cotonou - Abomey-Calavi : 9,6 km, l'axe quotidien du modele economique.
    const calavi = axisKm('Cotonou', 'Abomey-Calavi')
    expect(calavi).toBeLessThan(30)
    const radius = searchRadiusKm(calavi)
    expect(radius).toBeLessThanOrEqual(5)
    expect(radius).toBeLessThanOrEqual(calavi / 2)
    // Les deux extremites ne peuvent plus tomber toutes deux dans le rayon : sens preserve.
    expect(radius * 2).toBeLessThanOrEqual(calavi)
  })

  it('garde 15 km en interurbain', () => {
    expect(searchRadiusKm(axisKm('Cotonou', 'Bohicon'))).toBe(15)
    expect(searchRadiusKm(axisKm('Cotonou', 'Parakou'))).toBe(15)
  })

  it('reste sous la moitie de l axe entre 30 et 60 km', () => {
    expect(searchRadiusKm(40)).toBe(15)
    expect(searchRadiusKm(29.9)).toBe(5)
    expect(searchRadiusKm(8)).toBe(4)
  })

  it('serre a 4 km quand une extremite est un quartier ou une gare (F422)', () => {
    expect(searchRadiusKm(120, { origin: 'DISTRICT' })).toBe(4)
    expect(searchRadiusKm(120, { destination: 'STATION' })).toBe(4)
    expect(searchRadiusKm(120, { origin: 'CITY', destination: 'CITY' })).toBe(15)
    // Le plafond a la moitie de l axe s applique aussi : Agla -> Cadjehoun (3 km) garde un rayon < 1,5 km.
    expect(searchRadiusKm(3, { origin: 'DISTRICT', destination: 'DISTRICT' })).toBeLessThanOrEqual(1.5)
  })

  it('ne renvoie jamais un rayon nul pour deux lieux confondus', () => {
    expect(searchRadiusKm(0)).toBeGreaterThan(0)
  })
})

describe('searchPlaces', () => {
  const agla: CityOption = { id: 'a', label: 'Agla — Cotonou', region: 'Littoral', lat: 6.3931, lng: 2.3892, kind: 'DISTRICT', parentName: 'Cotonou' }
  const places = [...FALLBACK_PLACES, agla]

  it('ignore accents et casse, et place la ville avant ses quartiers', () => {
    const results = searchPlaces(places, 'coto', 5)
    expect(results[0]?.label).toBe('Cotonou')
    expect(results.map((c) => c.label)).toContain('Agla — Cotonou')
  })

  it('trouve un quartier par son nom court', () => {
    expect(searchPlaces(places, 'agl', 3)[0]?.label).toBe('Agla — Cotonou')
  })

  it('accepte les alias usuels', () => {
    expect(searchPlaces(places, 'calavi', 3)[0]?.label).toBe('Abomey-Calavi')
  })
})
