import { describe, expect, it } from 'vitest'
import { findCityByLabel, haversineKm, searchRadiusKm } from './cities'

function axisKm(from: string, to: string): number {
  const a = findCityByLabel(from)
  const b = findCityByLabel(to)
  if (!a || !b) throw new Error(`Ville inconnue : ${from} / ${to}`)
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

  it('ne renvoie jamais un rayon nul pour deux lieux confondus', () => {
    expect(searchRadiusKm(0)).toBeGreaterThan(0)
  })
})
