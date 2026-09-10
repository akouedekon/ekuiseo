import type { NoShowResolution } from '@/api/types'
import type { RefundSummary } from '@/api/extended'
import { formatDateTime, formatFcfa } from '@/lib/format'

/*
 * Dossier « conducteur absent » (V25) et sort de l argent, cote affichage. Memes regles que
 * BookingService#resolveDriverNoShow et RefundService : le serveur reste seul juge, ces
 * fonctions ne font que traduire son etat en phrases.
 */

/** Champs du dossier tels que renvoyes sur une reservation (passager) ou une ligne de passager (conducteur). */
export interface NoShowDisputeFields {
  driverNoShowRefundDueAt?: string | null
  driverNoShowContestedAt?: string | null
  driverNoShowResolution?: NoShowResolution | null
  driverNoShowResolvedAt?: string | null
}

export type NoShowDisputeState =
  /** Declare, pas encore conteste : le remboursement automatique tombe a l echeance. */
  | 'awaiting-driver'
  /** Le conducteur a conteste : la moderation tranche, le remboursement est gele. */
  | 'contested'
  /** Issue : acompte rembourse au passager. */
  | 'refunded'
  /** Issue : trajet maintenu, la reservation est reversee au conducteur. */
  | 'driver-paid'

export function noShowDisputeState(fields: NoShowDisputeFields): NoShowDisputeState {
  if (fields.driverNoShowResolution === 'REFUND_PASSENGER') return 'refunded'
  if (fields.driverNoShowResolution === 'PAY_DRIVER') return 'driver-paid'
  return fields.driverNoShowContestedAt ? 'contested' : 'awaiting-driver'
}

/** Le conducteur peut encore contester : dossier ouvert et pas deja conteste (le serveur accepte meme apres l echeance tant que rien n est tranche). */
export function canContestNoShow(fields: NoShowDisputeFields): boolean {
  return noShowDisputeState(fields) === 'awaiting-driver'
}

/** Phrase pour le passager, sous la puce « Conducteur absent (signalé) ». */
export function describeNoShowDisputeForPassenger(fields: NoShowDisputeFields, depositFcfa: number): string {
  const deposit = depositFcfa > 0 ? ` de ${formatFcfa(depositFcfa)}` : ''
  switch (noShowDisputeState(fields)) {
    case 'awaiting-driver':
      return fields.driverNoShowRefundDueAt
        ? `Le conducteur peut contester jusqu'au ${formatDateTime(fields.driverNoShowRefundDueAt)}. Sans contestation, votre acompte${deposit} vous est remboursé automatiquement à cette échéance.`
        : `Sans contestation du conducteur sous 24 h, votre acompte${deposit} vous est remboursé automatiquement.`
    case 'contested':
      return `Le conducteur conteste votre signalement${fields.driverNoShowContestedAt ? ` (le ${formatDateTime(fields.driverNoShowContestedAt)})` : ''}. La modération examine les deux versions ; votre acompte${deposit} est gelé jusqu'à sa décision.`
    case 'refunded':
      return `Absence du conducteur retenue${fields.driverNoShowResolvedAt ? ` le ${formatDateTime(fields.driverNoShowResolvedAt)}` : ''} : votre acompte${deposit} vous est remboursé.`
    case 'driver-paid':
      return `Après examen, la modération retient que le trajet a eu lieu${fields.driverNoShowResolvedAt ? ` (décision du ${formatDateTime(fields.driverNoShowResolvedAt)})` : ''} : votre acompte${deposit} reste acquis au conducteur.`
  }
}

/** Phrase pour le conducteur, sur la ligne du passager qui l a declare absent. */
export function describeNoShowDisputeForDriver(fields: NoShowDisputeFields): string {
  switch (noShowDisputeState(fields)) {
    case 'awaiting-driver':
      return fields.driverNoShowRefundDueAt
        ? `Ce passager déclare que vous n'étiez pas au départ. Contestez avant le ${formatDateTime(fields.driverNoShowRefundDueAt)} si vous avez bien effectué le trajet ; sinon son acompte lui est remboursé et cette place ne vous est pas reversée.`
        : "Ce passager déclare que vous n'étiez pas au départ. Contestez sous 24 h si vous avez bien effectué le trajet ; sinon son acompte lui est remboursé."
    case 'contested':
      return 'Vous avez contesté : la modération examine les deux versions avant de décider du reversement.'
    case 'refunded':
      return "Absence retenue : l'acompte est remboursé au passager, cette place n'est pas reversée."
    case 'driver-paid':
      return 'Trajet maintenu par la modération : cette place rejoint votre prochain reversement.'
  }
}

/** Ligne « Remboursement … » affichee sur une reservation annulee, expiree ou dont le conducteur etait absent. */
export function describeRefund(refund: RefundSummary): string {
  const amount = formatFcfa(refund.amountFcfa)
  switch (refund.status) {
    case 'REFUNDED':
      return `Remboursement de ${amount} effectué${refund.refundedAt ? ` le ${formatDateTime(refund.refundedAt)}` : ''} sur votre compte mobile money.`
    case 'MANUAL':
      return `Remboursement de ${amount} pris en charge par l'équipe Ekuiseo : il est fait à la main, sous 5 jours ouvrés.`
    case 'PENDING':
      return `Remboursement de ${amount} en cours auprès de votre opérateur mobile money (généralement sous 48 h).`
  }
}
