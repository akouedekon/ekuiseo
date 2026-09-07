import { describe, expect, it } from 'vitest'
import { deviceClockDiffersFromBenin, formatDateTime, formatTime, toInputDate, toInputTime } from './format'

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
