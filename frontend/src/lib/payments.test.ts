import { describe, expect, it } from 'vitest'
import { estimatePaymentPlan, estimateServiceFee, roundUpToStep } from './payments'

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

describe('estimateServiceFee', () => {
  // Miroir de MoneyUtilsTest : le front et le back doivent tomber sur le meme franc.
  it('calcule en entiers comme le serveur, sans arrondi intermediaire', () => {
    expect(estimateServiceFee(1_255)).toBe(105) // 100,4 -> 105 (Math.round aurait donne 100)
    expect(estimateServiceFee(12_505)).toBe(1_005) // 1 000,4 -> 1 005
    expect(estimateServiceFee(12_500)).toBe(1_000) // exact, pas de residu flottant
    expect(estimateServiceFee(4_000)).toBe(320)
    expect(estimateServiceFee(0)).toBe(0)
  })

  it('porte l acompte a 1 005 F quand les frais depassent le plancher', () => {
    const plan = estimatePaymentPlan(12_505, 'MOMO_DEPOSIT')
    expect(plan.serviceFee).toBe(1_005)
    expect(plan.depositAmount).toBe(1_005)
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
