import { beforeEach, describe, expect, it } from 'vitest'
import { MAX_RECENT_PLACES, readRecentPlaces, rememberPlace } from './recentPlaces'

describe('recentPlaces', () => {
  beforeEach(() => localStorage.clear())

  it('garde les cinq derniers lieux, le plus recent en tete, sans doublon', () => {
    for (let i = 1; i <= 7; i += 1) {
      rememberPlace({ label: `Ville ${i}`, lat: 6 + i, lng: 2, region: '' })
    }
    rememberPlace({ label: 'Ville 4', lat: 10, lng: 2, region: '' })
    const recents = readRecentPlaces()
    expect(recents).toHaveLength(MAX_RECENT_PLACES)
    expect(recents[0]?.label).toBe('Ville 4')
    expect(recents.filter((c) => c.label === 'Ville 4')).toHaveLength(1)
    expect(recents.map((c) => c.label)).toEqual(['Ville 4', 'Ville 7', 'Ville 6', 'Ville 5', 'Ville 3'])
  })

  it('ignore un stockage corrompu', () => {
    localStorage.setItem('ekuiseo.recentPlaces', '{not json')
    expect(readRecentPlaces()).toEqual([])
  })
})
