import { describe, expect, it } from 'vitest'
import type { BookingStatus } from '@/api/types'
import { BOOKING_STATUS_LABEL, DRIVER_APPROVAL_BADGE } from './labels'

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
    'EXPIRED',
  ]

  it('couvre chaque statut avec un libelle non vide et sans code technique', () => {
    for (const status of STATUSES) {
      const label = BOOKING_STATUS_LABEL[status]
      expect(label, status).toBeTruthy()
      expect(label, status).not.toMatch(/[A-Z_]{4,}/)
    }
  })

  it('nomme l attente du conducteur et le badge « sur accord » de facon coherente', () => {
    expect(BOOKING_STATUS_LABEL.PENDING_DRIVER_APPROVAL).toBe('En attente du conducteur')
    expect(DRIVER_APPROVAL_BADGE).toBe('Sur accord du conducteur')
  })
})
