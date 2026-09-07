import type { PaymentMode, PaymentPlanResponse, PaymentProvider } from '@/api/extended'

/** Operateurs mobile money acceptes au Benin, avec leur code USSD usuel. */
export const PROVIDERS: { value: PaymentProvider; label: string; hint: string }[] = [
  { value: 'MTN_MOMO', label: 'MTN MoMo', hint: '*880#' },
  { value: 'MOOV_MONEY', label: 'Moov Money', hint: '*855#' },
  { value: 'CELTIIS_CASH', label: 'Celtiis Cash', hint: '*888#' },
]

export function providerLabel(provider: PaymentProvider): string {
  return PROVIDERS.find((item) => item.value === provider)?.label ?? provider
}

/* ------------------------------------------------------------------ Regle */

/**
 * Plancher d'acompte, en FCFA. Ce n'est PAS le montant de l'acompte : sur une
 * reservation dont les frais de service depassent ce plancher, l'acompte vaut
 * les frais de service. Ne jamais afficher cette constante comme un prix.
 */
export const DEPOSIT_FLOOR = 1000

/** Les montants encaissables sont arrondis aux 5 FCFA superieurs. */
const ROUNDING_STEP = 5

/**
 * Part du total prelevee en frais de service, en pourcentage entier. Doit
 * rester alignee sur la regle metier appliquee par le serveur (8 %, arrondis
 * aux 5 FCFA superieurs - FeePolicy / MoneyUtils).
 */
const SERVICE_FEE_PERCENT = 8

export function roundUpToStep(amount: number, step = ROUNDING_STEP): number {
  return Math.ceil(amount / step) * step
}

/**
 * Frais de service estimes. Le montant qui fait foi est celui renvoye par
 * l'API dans `paymentPlan.serviceFee` : cette fonction ne sert qu'a afficher
 * un ordre de grandeur AVANT que la reservation n'existe.
 *
 * Calcul en entiers, comme MoneyUtils cote serveur : ceil(total x 8 / 500) x 5.
 * `total x 8` est un entier, la division par 500 est exacte en flottant pour
 * tout total realiste, et l'arrondi aux 5 F superieurs se fait en une fois
 * (1 255 -> 105, 12 505 -> 1 005). Un `Math.round` intermediaire donnait 5 F
 * de moins que le serveur sur les totaux non multiples de 25 (audit F045).
 */
export function estimateServiceFee(total: number): number {
  return Math.ceil((total * SERVICE_FEE_PERCENT) / 500) * ROUNDING_STEP
}

/**
 * Regle officielle de l'acompte paye en ligne :
 *   acompte = max(1 000 FCFA, frais de service de la reservation),
 *   arrondi aux 5 FCFA superieurs, plafonne au total du voyage.
 *
 * Reimplementee ici uniquement pour l'ESTIMATION affichee avant reservation.
 * Des que la reservation existe, c'est `paymentPlan.depositAmount` renvoye par
 * le serveur qui est affiche, sans recalcul.
 */
function computeDeposit(total: number, serviceFee: number): number {
  const raw = Math.max(DEPOSIT_FLOOR, serviceFee)
  return Math.min(roundUpToStep(raw), Math.max(0, total))
}

/* ------------------------------------------------------- Modes de paiement */

export const PAYMENT_MODES: {
  value: PaymentMode
  label: string
  /** Ce que le mode implique concretement en cas d'annulation. */
  cancellation: (freeHours: number) => string
  recommended?: boolean
}[] = [
  {
    value: 'MOMO_DEPOSIT',
    label: 'Acompte en mobile money',
    recommended: true,
    cancellation: (h) =>
      `Votre place est garantie. Acompte remboursé intégralement jusqu'à ${h} h avant le départ ; ensuite la moitié de l'acompte est retenue par la plateforme, et la totalité après l'heure de départ.`,
  },
  {
    value: 'MOMO_FULL',
    label: 'Paiement intégral en ligne',
    cancellation: (h) =>
      `Rien à régler à bord. Remboursement intégral jusqu'à ${h} h avant le départ ; ensuite la moitié du montant payé en ligne (le prix complet) est retenue, et la totalité après l'heure de départ.`,
  },
  {
    value: 'CASH',
    label: 'Tout en espèces à bord',
    cancellation: () =>
      "Aucun paiement en ligne, donc aucune place garantie : le conducteur peut la réattribuer. Annulation libre et sans frais.",
  },
]


/**
 * Plan de paiement ESTIME, utilise tant que la reservation n'existe pas.
 * Toujours affiche avec la mention « estimation » (voir PaymentSplit).
 */
export function estimatePaymentPlan(
  total: number,
  mode: PaymentMode,
  freeCancellationHours = 24,
): PaymentPlanResponse {
  const serviceFee = estimateServiceFee(total)

  let depositAmount: number
  if (mode === 'CASH') depositAmount = 0
  else if (mode === 'MOMO_FULL') depositAmount = total
  else depositAmount = computeDeposit(total, serviceFee)

  return {
    totalAmount: total,
    serviceFee,
    depositAmount,
    balanceAmount: Math.max(0, total - depositAmount),
    paymentMethod: mode,
    paymentStatus: 'ESTIMATED',
    depositDueAt: null,
    freeCancellationHours,
  }
}
