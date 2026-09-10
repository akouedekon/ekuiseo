import { describe, expect, it } from 'vitest'
import { FALLBACK_PLACES, haversineKm, myPositionOption, nearestPlace, searchPlaces, searchRadiusKm, type CityOption } from './cities'

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

describe('myPositionOption', () => {
  const places: CityOption[] = [
    ...FALLBACK_PLACES,
    { id: 'agla', label: 'Agla — Cotonou', region: 'Littoral', lat: 6.3801, lng: 2.3712, kind: 'DISTRICT', parentName: 'Cotonou' },
  ]

  it('libelle la position par le lieu du referentiel le plus proche, quartier et ville, sans la memoriser', () => {
    const option = myPositionOption(places, 6.3805, 2.3705)
    expect(option.label).toBe('Ma position (Agla — Cotonou)')
    // Les coordonnees restent celles de l appareil, pas celles du quartier ; sa nature serre le rayon.
    expect(option.lat).toBe(6.3805)
    expect(option.lng).toBe(2.3705)
    expect(option.kind).toBe('DISTRICT')
    expect(option.transient).toBe(true)
  })

  it('se contente de la ville quand aucun quartier n est plus proche, et reste lisible sans referentiel', () => {
    expect(myPositionOption(places, 9.34, 2.63).label).toBe('Ma position (Parakou)')
    expect(nearestPlace([], 6.37, 2.39)).toBeNull()
    expect(myPositionOption([], 6.37, 2.39).label).toBe('Ma position')
  })
})
