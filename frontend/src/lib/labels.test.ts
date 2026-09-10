import { describe, expect, it } from 'vitest'
import type { BookingStatus } from '@/api/types'
import { BOOKING_STATUS_LABEL, DRIVER_APPROVAL_BADGE, VEHICLE_TYPE_LABEL, VEHICLE_TYPE_MAX_SEATS, VEHICLE_TYPE_OPTIONS } from './labels'

/** Chaque statut du serveur a un libelle francais lisible ; le nouveau statut V19 est nomme sans jargon. */
describe('BOOKING_STATUS_LABEL', () => {
  const STATUSES: BookingStatus[] = [
    'PENDING_PAYMENT',
    'PENDING_DRIVER_APPROVAL',
    'CONFIRMED',
    'CANCELLED_BY_PASSENGER',
    'CANCELLED_BY_DRIVER',
    'COMPLETED',
    'NO_SHOW',
    'DRIVER_NO_SHOW',
    'EXPIRED',
  ]

  it('couvre chaque statut avec un libelle non vide et sans code technique', () => {
    for (const status of STATUSES) {
      const label = BOOKING_STATUS_LABEL[status]
      expect(label, status).toBeTruthy()
      expect(label, status).not.toMatch(/[A-Z_]{4,}/)
    }
  })

  it('nomme les types de vehicule et borne leurs places comme le serveur (VehicleType.java)', () => {
    expect(VEHICLE_TYPE_LABEL).toEqual({ CAR: 'Voiture', MOTO: 'Moto', TRICYCLE: 'Tricycle' })
    expect(VEHICLE_TYPE_MAX_SEATS).toEqual({ CAR: 8, MOTO: 1, TRICYCLE: 6 })
    expect(VEHICLE_TYPE_OPTIONS.map((o) => o.value)).toEqual(['CAR', 'MOTO', 'TRICYCLE'])
  })

  it('nomme l attente du conducteur et le badge « sur accord » de facon coherente', () => {
    expect(BOOKING_STATUS_LABEL.PENDING_DRIVER_APPROVAL).toBe('En attente du conducteur')
    expect(DRIVER_APPROVAL_BADGE).toBe('Sur accord du conducteur')
  })
})
