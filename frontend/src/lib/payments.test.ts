import { describe, expect, it } from 'vitest'
import { estimatePaymentPlan, roundUpToStep } from './payments'

/*
 * Regles metier n.1 a n.3 (CLAUDE.md) : arrondi aux 5 FCFA superieurs,
 * commission de 8 %, acompte = min(total, arrondi_5_sup(max(1000, frais))).
 * Ces valeurs doivent rester alignees sur FeePolicy / MoneyUtils cote backend.
 */
describe('roundUpToStep', () => {
  it('arrondit aux 5 FCFA superieurs', () => {
    expect(roundUpToStep(321, 5)).toBe(325)
    expect(roundUpToStep(325, 5)).toBe(325)
    expect(roundUpToStep(0, 5)).toBe(0)
  })
})

describe('estimatePaymentPlan', () => {
  it('applique le plancher de 1 000 F quand les frais sont inferieurs', () => {
    const plan = estimatePaymentPlan(4000, 'MOMO_DEPOSIT')
    expect(plan.serviceFee).toBe(320)
    expect(plan.depositAmount).toBe(1000)
    expect(plan.balanceAmount).toBe(3000)
    expect(plan.paymentStatus).toBe('ESTIMATED')
  })

  it('prend les frais de service quand ils depassent le plancher, arrondis a 5 F', () => {
    const plan = estimatePaymentPlan(15_010, 'MOMO_DEPOSIT')
    // 8 % de 15 010 = 1 200,8 -> 1 205
    expect(plan.serviceFee).toBe(1205)
    expect(plan.depositAmount).toBe(1205)
    expect(plan.balanceAmount).toBe(15_010 - 1205)
  })

  it('plafonne l’acompte au total pour un petit trajet', () => {
    const plan = estimatePaymentPlan(800, 'MOMO_DEPOSIT')
    expect(plan.depositAmount).toBe(800)
    expect(plan.balanceAmount).toBe(0)
  })

  it('encaisse tout en ligne en MOMO_FULL et rien en CASH', () => {
    const full = estimatePaymentPlan(4000, 'MOMO_FULL')
    expect(full.depositAmount).toBe(4000)
    expect(full.balanceAmount).toBe(0)
    const cash = estimatePaymentPlan(4000, 'CASH')
    expect(cash.depositAmount).toBe(0)
    expect(cash.balanceAmount).toBe(4000)
  })
})
