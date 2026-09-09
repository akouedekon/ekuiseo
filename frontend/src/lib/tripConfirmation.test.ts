import { describe, expect, it } from 'vitest'
import { PASSENGER_CONFIRMATION_WINDOW_MS, tripConfirmationState } from './tripConfirmation'

const H = 60 * 60 * 1000
const now = Date.UTC(2026, 8, 10, 12, 0, 0)
const at = (offsetMs: number) => new Date(now + offsetMs).toISOString()

describe('tripConfirmationState', () => {
  it('pose la question apres le depart, dans les 24 h, sur une reservation honoree sans constat', () => {
    expect(tripConfirmationState({ status: 'CONFIRMED', passengerConfirmation: 'PENDING', trip: { departureAt: at(-2 * H) } }, now)).toBe('ask')
    expect(tripConfirmationState({ status: 'COMPLETED', passengerConfirmation: 'PENDING', trip: { departureAt: at(-23 * H) } }, now)).toBe('ask')
    // Reponse ancienne du serveur, sans le champ : meme lecture qu un PENDING.
    expect(tripConfirmationState({ status: 'COMPLETED', trip: { departureAt: at(-5 * H) } }, now)).toBe('ask')
  })

  it('devient tacite passe 24 h, et ne se pose jamais avant le depart', () => {
    expect(tripConfirmationState({ status: 'COMPLETED', passengerConfirmation: 'PENDING', trip: { departureAt: at(-PASSENGER_CONFIRMATION_WINDOW_MS - 1) } }, now)).toBe('tacit')
    expect(tripConfirmationState({ status: 'CONFIRMED', passengerConfirmation: 'PENDING', trip: { departureAt: at(1 * H) } }, now)).toBe('none')
  })

  it('reflete un constat deja donne, quel que soit le delai', () => {
    expect(tripConfirmationState({ status: 'COMPLETED', passengerConfirmation: 'TRIP_DONE', trip: { departureAt: at(-40 * H) } }, now)).toBe('done')
    expect(tripConfirmationState({ status: 'DRIVER_NO_SHOW', passengerConfirmation: 'DRIVER_NO_SHOW', trip: { departureAt: at(-3 * H) } }, now)).toBe('driver-no-show')
  })

  it('ignore les reservations annulees, expirees ou signalees absentes par le conducteur', () => {
    for (const status of ['CANCELLED_BY_PASSENGER', 'CANCELLED_BY_DRIVER', 'EXPIRED', 'NO_SHOW', 'PENDING_PAYMENT'] as const) {
      expect(tripConfirmationState({ status, passengerConfirmation: 'PENDING', trip: { departureAt: at(-2 * H) } }, now)).toBe('none')
    }
  })
})
