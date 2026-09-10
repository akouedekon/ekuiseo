import { describe, expect, it } from 'vitest'
import { deviceClockDiffersFromBenin, formatDateTime, formatDistanceKm, formatTime, toInputDate, toInputTime } from './format'

/*
 * Audit F424 : les heures sont formatees en heure du Benin (UTC+1), jamais dans
 * le fuseau de la machine. Ces tests doivent passer en CI (UTC) comme sur un
 * poste regle sur Paris ou Cotonou.
 */
describe('format en heure du Benin', () => {
  it('formate un instant UTC en heure du Benin', () => {
    expect(formatTime('2026-09-07T06:30:00Z')).toBe('07:30')
    expect(toInputTime('2026-09-07T23:30:00Z')).toBe('00:30')
    expect(toInputDate('2026-09-07T23:30:00Z')).toBe('2026-09-08')
    expect(formatDateTime('2026-09-07T06:30:00Z')).toMatch(/lun\.? 7 sept\.? à 07:30/)
  })

  it('sait si l horloge de l appareil suit le Benin (compare les decalages, pas les noms)', () => {
    const offset = new Date().getTimezoneOffset()
    // Une machine reglee sur UTC+1 (Cotonou, Lagos, Niamey) n'appelle aucune mention.
    expect(deviceClockDiffersFromBenin()).toBe(offset !== -60)
  })
})

describe('formatDistanceKm', () => {
  it('donne des metres sous 1 km (par pas de 10 m), un dixieme de km jusqu a 10 km, entier au-dela', () => {
    expect(formatDistanceKm(0.812)).toBe('810 m')
    expect(formatDistanceKm(0.004)).toBe('10 m')
    expect(formatDistanceKm(0)).toBe('10 m')
    expect(formatDistanceKm(2.44)).toBe('2,4 km')
    expect(formatDistanceKm(1)).toBe('1 km')
    expect(formatDistanceKm(9.96)).toBe('10 km')
    expect(formatDistanceKm(23.7)).toBe('24 km')
  })
})
