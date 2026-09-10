import { describe, expect, it } from 'vitest'
import { formatFcfa } from './format'
import { canContestNoShow, describeNoShowDisputeForDriver, describeNoShowDisputeForPassenger, describeRefund, noShowDisputeState } from './noShowDispute'

describe('noShowDisputeState', () => {
  it('attend le conducteur tant que rien n est conteste ni tranche', () => {
    expect(noShowDisputeState({ driverNoShowRefundDueAt: '2026-09-11T06:00:00Z' })).toBe('awaiting-driver')
    expect(canContestNoShow({ driverNoShowRefundDueAt: '2026-09-11T06:00:00Z' })).toBe(true)
  })

  it('passe en contestation, puis reflete l issue quelle que soit la contestation', () => {
    expect(noShowDisputeState({ driverNoShowContestedAt: '2026-09-10T20:00:00Z' })).toBe('contested')
    expect(canContestNoShow({ driverNoShowContestedAt: '2026-09-10T20:00:00Z' })).toBe(false)
    expect(noShowDisputeState({ driverNoShowContestedAt: '2026-09-10T20:00:00Z', driverNoShowResolution: 'REFUND_PASSENGER' })).toBe('refunded')
    expect(noShowDisputeState({ driverNoShowResolution: 'PAY_DRIVER' })).toBe('driver-paid')
    expect(canContestNoShow({ driverNoShowResolution: 'PAY_DRIVER' })).toBe(false)
  })
})

describe('descriptions', () => {
  it('donne l echeance et le montant au passager, et l issue', () => {
    const awaiting = describeNoShowDisputeForPassenger({ driverNoShowRefundDueAt: '2026-09-11T06:00:00Z' }, 1000)
    // Espace fine insecable de fr-FR : on compare a la meme fonction de formatage.
    expect(awaiting).toContain(formatFcfa(1000))
    expect(awaiting).toContain('remboursé automatiquement')
    expect(describeNoShowDisputeForPassenger({ driverNoShowContestedAt: '2026-09-10T20:00:00Z' }, 1000)).toContain('conteste')
    expect(describeNoShowDisputeForPassenger({ driverNoShowResolution: 'REFUND_PASSENGER' }, 1000)).toContain('remboursé')
    expect(describeNoShowDisputeForPassenger({ driverNoShowResolution: 'PAY_DRIVER' }, 0)).toContain('reste acquis au conducteur')
  })

  it('explique au conducteur ce qu il risque et ce qu il peut faire', () => {
    expect(describeNoShowDisputeForDriver({ driverNoShowRefundDueAt: '2026-09-11T06:00:00Z' })).toContain('Contestez avant le')
    expect(describeNoShowDisputeForDriver({ driverNoShowContestedAt: '2026-09-10T20:00:00Z' })).toContain('modération')
    expect(describeNoShowDisputeForDriver({ driverNoShowResolution: 'REFUND_PASSENGER' })).toContain('remboursé au passager')
    expect(describeNoShowDisputeForDriver({ driverNoShowResolution: 'PAY_DRIVER' })).toContain('prochain reversement')
  })

  it('decrit un remboursement selon son etat', () => {
    expect(describeRefund({ status: 'PENDING', amountFcfa: 1000, requestedAt: null, refundedAt: null })).toContain('en cours')
    expect(describeRefund({ status: 'MANUAL', amountFcfa: 500, requestedAt: null, refundedAt: null })).toContain('5 jours ouvrés')
    expect(describeRefund({ status: 'REFUNDED', amountFcfa: 1000, requestedAt: null, refundedAt: '2026-09-12T10:00:00Z' })).toContain('effectué le')
  })
})
