import { m } from 'motion/react'
import {
  Ban,
  Banknote,
  Bell,
  BellOff,
  CalendarClock,
  CheckCheck,
  CheckCircle2,
  CreditCard,
  Flag,
  MapPinCheck,
  MessageSquare,
  Navigation,
  ScrollText,
  SearchCheck,
  ShieldCheck,
  ShieldOff,
  Star,
  TimerOff,
  UserCheck,
  UserX,
  Wallet,
  XCircle,
  type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState, OfflineState, isOfflineWithoutData } from '@/components/ui/states'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  useUnreadNotificationCount,
} from '@/hooks/useNotifications'
import { describeError } from '@/lib/errors'
import { formatDateTime, formatFcfa, formatFromNow } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { NotificationResponse, NotificationType } from '@/api/types'

const TONE = {
  success: 'bg-success-soft text-success-ink',
  danger: 'bg-danger-soft text-danger-ink',
  info: 'bg-primary-soft text-primary-ink',
  warning: 'bg-accent-soft text-accent-ink',
  neutral: 'bg-surface-2 text-ink-2',
} as const

/**
 * `Partial` (pas `Record` total) deliberement : une valeur de NotificationType
 * absente d'ici retombe sur DEFAULT_PRESENTATION plutot que de planter le rendu
 * (voir l'incident sur SEARCH_ALERT_MATCH/SUBSCRIPTION_ACTIVATED, qui manquaient
 * purement et simplement du type NotificationType cote front alors que le
 * serveur les emet deja).
 */
const PRESENTATION: Partial<Record<NotificationType, { icon: LucideIcon; tone: keyof typeof TONE; title: string }>> = {
  BOOKING_CONFIRMED: { icon: CheckCircle2, tone: 'success', title: 'Nouvelle réservation' },
  BOOKING_REQUESTED: { icon: UserCheck, tone: 'warning', title: 'Demande de réservation' },
  BOOKING_DECLINED: { icon: XCircle, tone: 'danger', title: 'Demande refusée' },
  BOOKING_CANCELLED: { icon: XCircle, tone: 'danger', title: 'Réservation annulée' },
  BOOKING_EXPIRED: { icon: TimerOff, tone: 'neutral', title: 'Réservation expirée' },
  PAYMENT_SUCCEEDED: { icon: CreditCard, tone: 'success', title: 'Paiement reçu' },
  PAYMENT_FAILED: { icon: CreditCard, tone: 'danger', title: 'Paiement échoué' },
  PAYMENT_REFUND_PENDING: { icon: Banknote, tone: 'warning', title: 'Remboursement en cours' },
  PAYMENT_REFUNDED: { icon: Banknote, tone: 'success', title: 'Remboursement effectué' },
  NEW_MESSAGE: { icon: MessageSquare, tone: 'info', title: 'Nouveau message' },
  TRIP_REMINDER: { icon: CalendarClock, tone: 'warning', title: 'Départ demain' },
  TRIP_UPDATED: { icon: CalendarClock, tone: 'warning', title: 'Horaire modifié' },
  NEW_REVIEW: { icon: Star, tone: 'warning', title: 'Nouvel avis' },
  SEARCH_ALERT_MATCH: { icon: SearchCheck, tone: 'info', title: 'Un trajet correspond à votre alerte' },
  SUBSCRIPTION_ACTIVATED: { icon: CheckCircle2, tone: 'success', title: 'Abonnement activé' },
  SUBSCRIPTION_EXPIRING: { icon: CalendarClock, tone: 'warning', title: 'Abonnement bientôt expiré' },
  SUBSCRIPTION_EXPIRED: { icon: TimerOff, tone: 'neutral', title: 'Abonnement expiré' },
  PAYOUT_ACCOUNT_MISSING: { icon: Wallet, tone: 'warning', title: 'Reversement en attente d’un compte' },
  PAYOUT_SETTLED: { icon: Wallet, tone: 'success', title: 'Reversement effectué' },
  PAYOUT_FAILED: { icon: Wallet, tone: 'danger', title: 'Reversement en échec' },
  REPORT_RECEIVED: { icon: Bell, tone: 'warning', title: 'Signalement reçu' },
  REPORT_RESOLVED: { icon: Flag, tone: 'info', title: 'Signalement traité' },
  BOOKING_NO_SHOW: { icon: UserX, tone: 'danger', title: 'Absence signalée' },
  DRIVER_NO_SHOW_REPORTED: { icon: UserX, tone: 'danger', title: 'Un passager signale votre absence' },
  NO_SHOW_CONTESTED: { icon: Flag, tone: 'warning', title: 'Le conducteur conteste' },
  NO_SHOW_DISPUTE_RESOLVED: { icon: Flag, tone: 'info', title: 'Dossier « conducteur absent » tranché' },
  PAYOUT_PREPARED: { icon: Wallet, tone: 'info', title: 'Reversement préparé' },
  IDENTITY_APPROVED: { icon: ShieldCheck, tone: 'success', title: 'Identité vérifiée' },
  IDENTITY_REJECTED: { icon: ShieldOff, tone: 'danger', title: 'Vérification refusée' },
  IDENTITY_REVOKED: { icon: ShieldOff, tone: 'danger', title: 'Badge d’identité retiré' },
  ACCOUNT_SUSPENDED: { icon: Ban, tone: 'danger', title: 'Compte suspendu' },
  TERMS_UPDATED: { icon: ScrollText, tone: 'info', title: 'Conditions mises à jour' },
  /* Suivi en direct (V28). */
  DRIVER_NEARBY: { icon: Navigation, tone: 'info', title: 'Votre conducteur arrive' },
  DRIVER_ARRIVED: { icon: MapPinCheck, tone: 'success', title: 'Votre conducteur est arrivé' },
}

const DEFAULT_PRESENTATION = { icon: Bell, tone: 'neutral' as const, title: 'Notification' }

/** Lecture tolerante de la charge utile : le serveur envoie parfois les nombres en chaine (`rating`). */
function readPayload(notification: NotificationResponse) {
  const payload = notification.payload ?? {}
  const str = (key: string): string | undefined => {
    const value = payload[key]
    return typeof value === 'string' && value.trim() ? value : undefined
  }
  const num = (key: string): number | undefined => {
    const value = payload[key]
    if (typeof value === 'number' && Number.isFinite(value)) return value
    if (typeof value === 'string' && value.trim() && Number.isFinite(Number(value))) return Number(value)
    return undefined
  }
  const bool = (key: string): boolean => payload[key] === true || payload[key] === 'true'
  return { str, num, bool }
}

/** « Cotonou -> Bohicon » tel qu envoye par le serveur, rendu avec une vraie fleche. */
function routeLabel(route: string | undefined): string | undefined {
  return route?.replace(/\s*->\s*/g, ' → ')
}

function when(iso: string | undefined): string {
  if (!iso) return ''
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : formatDateTime(date)
}

/** Resume lisible construit a partir de la charge utile REELLEMENT emise par le serveur (audit F230). */
function describe(notification: NotificationResponse): string {
  const { str, num, bool } = readPayload(notification)
  const route = routeLabel(str('route'))
  const seats = num('seats')
  const seatsLabel = seats ? `${seats} place${seats > 1 ? 's' : ''}` : undefined

  switch (notification.type) {
    case 'BOOKING_CONFIRMED': {
      if (bool('forPassenger')) {
        // Recue par le passager : accord du conducteur (V19) ou demande en especes enregistree.
        const balance = num('balanceDueOnBoardFcfa')
        return `${bool('acceptedByDriver') ? 'Le conducteur a accepté votre demande' : 'Votre réservation est confirmée'}${
          route ? ` sur ${route}` : ''
        }${str('departureAt') ? ` (départ ${when(str('departureAt'))})` : ''}.${
          balance ? ` À régler à bord : ${formatFcfa(balance)}.` : ''
        }`
      }
      // Recue par le conducteur : un passager vient de confirmer (acompte recu ou espèces).
      const passenger = str('passengerName') ?? 'Un passager'
      return `${passenger} a réservé ${seatsLabel ?? 'une place'}${route ? ` sur ${route}` : ''}${
        str('departureAt') ? ` (départ ${when(str('departureAt'))})` : ''
      }.`
    }
    case 'BOOKING_REQUESTED': {
      const deadline = str('approvalDeadlineAt')
      if (bool('forPassenger')) {
        return `Votre demande${seatsLabel ? ` de ${seatsLabel}` : ''}${route ? ` sur ${route}` : ''} est transmise au conducteur${
          deadline ? ` ; réponse attendue avant le ${when(deadline)}` : ''
        }. Sans accord dans ce délai, votre acompte vous est remboursé intégralement.`
      }
      const passenger = str('passengerName') ?? 'Un passager'
      return `${passenger} demande ${seatsLabel ?? 'une place'}${route ? ` sur ${route}` : ''}. ${
        deadline ? `Répondez avant le ${when(deadline)} : passé ce délai, la demande sera refusée et le passager remboursé.` : 'Acceptez ou refusez depuis la liste des passagers.'
      }`
    }
    case 'BOOKING_DECLINED': {
      if (bool('forDriver')) {
        const passenger = str('passengerName') ?? "d'un passager"
        return `La demande de ${passenger}${route ? ` sur ${route}` : ''} est restée sans réponse dans le délai : elle a été refusée automatiquement et le passager remboursé.`
      }
      const refund = num('refundAmountFcfa')
      const reason = str('reason')
      return `${bool('timedOut') ? "Le conducteur n'a pas répondu à votre demande dans le délai" : "Le conducteur n'a pas pu accepter votre demande"}${
        route ? ` sur ${route}` : ''
      }.${reason ? ` Motif : ${reason}.` : ''} ${refund ? `Votre acompte de ${formatFcfa(refund)} vous est remboursé intégralement.` : 'Tout acompte versé vous est remboursé intégralement.'}`
    }
    case 'BOOKING_CANCELLED': {
      const by = str('cancelledBy')
      const refund = num('refundAmountFcfa')
      const retained = num('retainedAmountFcfa')
      if (by === 'DRIVER' || by === 'PLATFORM') {
        return `${by === 'PLATFORM' ? 'Le trajet' : 'Le conducteur a annulé le trajet'}${route ? ` ${route}` : ''}${
          by === 'PLATFORM' ? ' a été annulé' : ''
        }.${refund ? ` Votre acompte de ${formatFcfa(refund)} vous est remboursé.` : ' Tout acompte versé vous est remboursé.'}`
      }
      const parts = [`Réservation annulée par le passager${route ? ` sur ${route}` : ''}${seatsLabel ? ` (${seatsLabel})` : ''}.`]
      if (refund) parts.push(`Acompte remboursé : ${formatFcfa(refund)}.`)
      if (retained) parts.push(`Montant retenu : ${formatFcfa(retained)}.`)
      return parts.join(' ')
    }
    case 'BOOKING_EXPIRED':
      return `L'acompte n'a pas été reçu dans les 20 minutes${route ? ` pour ${route}` : ''} : la place a été libérée. Vous pouvez réserver à nouveau.`
    case 'PAYMENT_SUCCEEDED': {
      const amount = num('amountFcfa')
      const balance = num('balanceDueOnBoardFcfa')
      const reference = str('reference')
      return `${amount ? `Paiement de ${formatFcfa(amount)} reçu` : 'Paiement reçu'}${route ? ` pour ${route}` : ''}. Votre place est confirmée${
        balance ? ` ; ${formatFcfa(balance)} restent à régler en espèces à bord` : ''
      }.${reference ? ` Référence ${reference}.` : ''}`
    }
    case 'PAYMENT_FAILED':
      return "Le paiement de l'acompte n'a pas abouti. La place n'est pas réservée : réessayez avec un autre numéro ou opérateur."
    case 'PAYMENT_REFUND_PENDING': {
      const amount = num('amountFcfa')
      return bool('manual')
        ? `Remboursement${amount ? ` de ${formatFcfa(amount)}` : ''} pris en charge par l'équipe Ekuiseo : il sera fait à la main, vous serez prévenu.`
        : `Remboursement${amount ? ` de ${formatFcfa(amount)}` : ''} en cours auprès de l'opérateur mobile money.`
    }
    case 'PAYMENT_REFUNDED': {
      const amount = num('amountFcfa')
      return `${amount ? formatFcfa(amount) : 'Le montant'} vous ${amount ? 'ont' : 'a'} été remboursé${amount ? 's' : ''} sur votre compte mobile money.`
    }
    case 'NEW_MESSAGE':
      return 'Vous avez reçu un message à propos d’une réservation.'
    case 'TRIP_REMINDER':
      return `Votre trajet${route ? ` ${route}` : ''} part demain${str('departureAt') ? `, ${when(str('departureAt'))}` : ''}. Pensez au solde en espèces.`
    case 'TRIP_UPDATED': {
      const departureAt = str('departureAt')
      return `Le conducteur a déplacé le départ${route ? ` de ${route}` : ''}${
        departureAt ? ` au ${when(departureAt)}` : ''
      }. Vous pouvez annuler sans frais pendant 24 h.`
    }
    case 'NEW_REVIEW': {
      const rating = num('rating')
      return rating ? `Un passager vous a laissé ${rating} étoile${rating > 1 ? 's' : ''}.` : 'Un passager vous a laissé un avis.'
    }
    case 'SEARCH_ALERT_MATCH':
      return `Un trajet${route ? ` ${route}` : ''}${str('departureAt') ? ` (${when(str('departureAt'))})` : ''} correspond à votre alerte.`
    case 'SUBSCRIPTION_ACTIVATED':
      return 'Votre abonnement conducteur est actif : aucune commission sur vos trajets pendant 30 jours.'
    case 'SUBSCRIPTION_EXPIRING':
      return 'Votre abonnement conducteur arrive à échéance. Renouvelez-le pour garder 0 % de commission.'
    case 'SUBSCRIPTION_EXPIRED':
      return 'Votre abonnement conducteur a expiré : la commission de 8 % s’applique de nouveau.'
    case 'PAYOUT_ACCOUNT_MISSING': {
      const amount = num('amountFcfa')
      return `${amount ? `${formatFcfa(amount)} vous attendent` : 'Un reversement vous attend'} : ajoutez un compte mobile money vérifié pour le recevoir.`
    }
    case 'PAYOUT_SETTLED': {
      const amount = num('amountFcfa')
      return `${amount ? `${formatFcfa(amount)} ont` : 'Votre reversement a'} été viré${amount ? 's' : ''} sur votre compte mobile money.`
    }
    case 'PAYOUT_FAILED':
      return `Le virement de votre reversement n'a pas abouti${str('reason') ? ` : ${str('reason')}` : ''}. Vérifiez votre compte mobile money ou écrivez au support.`
    case 'DRIVER_NO_SHOW_REPORTED': {
      const until = str('contestUntil')
      const deposit = num('depositAmountFcfa')
      return `Un passager déclare que vous n'étiez pas au départ${route ? ` de ${route}` : ''}. ${
        until ? `Contestez avant le ${when(until)}` : 'Contestez sous 24 h'
      } depuis la liste des passagers si vous avez bien effectué le trajet ; sinon son acompte${deposit ? ` de ${formatFcfa(deposit)}` : ''} lui est remboursé.`
    }
    case 'NO_SHOW_CONTESTED':
      return `Le conducteur${route ? ` du trajet ${route}` : ''} conteste l'absence que vous avez déclarée. Le remboursement de votre acompte est suspendu le temps que la modération examine les deux versions.`
    case 'NO_SHOW_DISPUTE_RESOLVED': {
      const refund = str('decision') === 'REFUND_PASSENGER'
      const deposit = num('depositAmountFcfa')
      const amount = deposit ? ` de ${formatFcfa(deposit)}` : ''
      if (bool('forPassenger')) {
        return refund
          ? `Absence du conducteur retenue${route ? ` sur ${route}` : ''} : votre acompte${amount} vous est remboursé.`
          : `La modération retient que le trajet${route ? ` ${route}` : ''} a bien eu lieu : votre acompte${amount} reste acquis au conducteur.`
      }
      return refund
        ? `Absence retenue${route ? ` sur ${route}` : ''} : l'acompte${amount} est remboursé au passager, cette place ne vous est pas reversée.`
        : `Trajet${route ? ` ${route}` : ''} maintenu : la réservation contestée rejoint votre prochain reversement.`
    }
    case 'PAYOUT_PREPARED': {
      const amount = num('amountFcfa')
      return `${amount ? `Un reversement de ${formatFcfa(amount)}` : 'Un reversement'} est préparé${
        str('destination') ? ` vers votre compte mobile money ${str('destination')}` : ''
      }. Le virement suit dans les jours qui viennent.`
    }
    case 'REPORT_RECEIVED':
      return 'Un signalement vous concernant a été reçu par la modération.'
    case 'REPORT_RESOLVED':
      return str('status') === 'DISMISSED'
        ? 'Votre signalement a été examiné et classé sans suite.'
        : 'Votre signalement a été examiné et une mesure a été prise. Merci d’avoir contribué à la sécurité de la communauté.'
    case 'BOOKING_NO_SHOW': {
      const retained = num('retainedAmountFcfa')
      return retained
        ? `Le conducteur a signalé votre absence au départ : l’acompte de ${formatFcfa(retained)} est retenu.`
        : 'Le conducteur a signalé votre absence au départ : l’acompte est retenu.'
    }
    case 'IDENTITY_APPROVED':
      return 'Votre pièce d’identité a été contrôlée : le badge « Vérifié » apparaît sur votre profil.'
    case 'IDENTITY_REJECTED': {
      const reason = str('reason')
      return reason
        ? `Votre dossier d’identité a été refusé : ${reason}. Vous pouvez renvoyer un document.`
        : 'Votre dossier d’identité a été refusé. Vous pouvez renvoyer un document.'
    }
    case 'IDENTITY_REVOKED': {
      const reason = str('reason')
      return reason
        ? `Votre badge d’identité vérifiée a été retiré : ${reason}. Vous pouvez soumettre un nouveau dossier.`
        : 'Votre badge d’identité vérifiée a été retiré. Vous pouvez soumettre un nouveau dossier.'
    }
    case 'ACCOUNT_SUSPENDED': {
      const reason = str('reason')
      return reason
        ? `Votre compte est suspendu : ${reason}. Écrivez-nous pour contester.`
        : 'Votre compte est suspendu. Écrivez-nous pour contester.'
    }
    case 'TERMS_UPDATED':
      return 'Nos conditions générales d’utilisation ont changé. Elles vous seront proposées à votre prochaine ouverture.'
    case 'DRIVER_NEARBY': {
      const driver = str('driverFirstName') ?? 'Votre conducteur'
      return `${driver} est à moins d’un kilomètre de votre point de rendez-vous${route ? ` (${route})` : ''}. Tenez-vous prêt.`
    }
    case 'DRIVER_ARRIVED': {
      const driver = str('driverFirstName') ?? 'Votre conducteur'
      return `${driver} est arrivé à votre point de rendez-vous${route ? ` (${route})` : ''}. Rejoignez le véhicule.`
    }
    default:
      return ''
  }
}

/** Lien de destination deduit du type ET de la charge utile : la bonne page pour la bonne personne. */
function targetOf(notification: NotificationResponse): string | null {
  const { str, bool } = readPayload(notification)
  const bookingId = str('bookingId')
  const tripId = str('tripId')
  switch (notification.type) {
    case 'NEW_MESSAGE':
      return bookingId ? `/bookings/${bookingId}/messages` : '/messages'
    case 'BOOKING_CONFIRMED':
    case 'BOOKING_REQUESTED':
      // Recue par le conducteur : sa liste d'appel, pas « Mes reservations » ; l'accuse du passager renvoie a sa reservation.
      return bool('forPassenger') ? '/bookings' : '/trips/mine'
    case 'BOOKING_DECLINED':
      return bool('forDriver') ? '/trips/mine' : tripId ? `/trips/${tripId}` : '/bookings'
    case 'BOOKING_CANCELLED':
      return str('cancelledBy') === 'PASSENGER' ? '/trips/mine' : '/bookings'
    case 'BOOKING_EXPIRED':
      return tripId ? `/trips/${tripId}` : '/'
    case 'PAYMENT_FAILED':
      return bookingId && tripId ? `/book/${tripId}?booking=${bookingId}` : '/bookings'
    case 'PAYMENT_SUCCEEDED':
    case 'PAYMENT_REFUND_PENDING':
    case 'PAYMENT_REFUNDED':
    case 'TRIP_REMINDER':
    case 'TRIP_UPDATED':
    case 'BOOKING_NO_SHOW':
      return '/bookings'
    case 'NEW_REVIEW':
      return '/me'
    case 'SEARCH_ALERT_MATCH':
      return tripId ? `/trips/${tripId}` : '/me?tab=alerts'
    case 'SUBSCRIPTION_ACTIVATED':
    case 'SUBSCRIPTION_EXPIRING':
    case 'SUBSCRIPTION_EXPIRED':
    case 'PAYOUT_SETTLED':
    case 'PAYOUT_FAILED':
    case 'PAYOUT_PREPARED':
      return '/me?tab=earnings'
    case 'DRIVER_NO_SHOW_REPORTED':
      // Le conducteur conteste depuis la liste des passagers de son trajet.
      return '/trips/mine'
    case 'NO_SHOW_CONTESTED':
      return '/bookings'
    case 'NO_SHOW_DISPUTE_RESOLVED':
      return bool('forPassenger') ? '/bookings' : '/trips/mine'
    case 'PAYOUT_ACCOUNT_MISSING':
      return '/me?tab=payment'
    case 'IDENTITY_APPROVED':
    case 'IDENTITY_REJECTED':
    case 'IDENTITY_REVOKED':
      return '/me?tab=identity'
    case 'TERMS_UPDATED':
      return '/cgu'
    case 'ACCOUNT_SUSPENDED':
    case 'REPORT_RECEIVED':
    case 'REPORT_RESOLVED':
      // Rien a faire dans l'application : la contestation passe par le support.
      return null
    case 'DRIVER_NEARBY':
    case 'DRIVER_ARRIVED':
      // La fiche du trajet, ou la carte du suivi est l ecran principal pendant le trajet (V28).
      return tripId ? `/trips/${tripId}` : '/bookings'
    default:
      if (bookingId) return '/bookings'
      if (tripId) return `/trips/${tripId}`
      return null
  }
}

export function NotificationsPage() {
  const notifications = useNotifications()
  const unread = useUnreadNotificationCount()
  const markRead = useMarkNotificationRead()
  const markAll = useMarkAllNotificationsRead()

  const list = notifications.data?.pages.flatMap((page) => page.content) ?? []
  const total = notifications.data?.pages[0]?.totalElements ?? list.length

  return (
    <PageContainer width="md">
      <PageMeta title="Notifications" noindex />
      <PageHeader
        title="Notifications"
        back={false}
        subtitle={unread > 0 ? `${unread} non lue${unread > 1 ? 's' : ''}` : 'Tout est à jour'}
        actions={
          unread > 0 ? (
            <Button
              variant="ghost"
              size="sm"
              loading={markAll.isPending}
              onClick={() =>
                markAll.mutate(undefined, {
                  onSuccess: () => toast.success('Toutes les notifications sont marquées comme lues'),
                  onError: (error) => toast.error(describeError(error, "Le marquage n'a pas abouti.")),
                })
              }
            >
              <CheckCheck className="size-4" aria-hidden />
              <span className="hidden sm:inline">Tout marquer comme lu</span>
            </Button>
          ) : undefined
        }
      />

      {isOfflineWithoutData(notifications) ? (
        <OfflineState onRetry={() => notifications.refetch()} />
      ) : notifications.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2, 3].map((i) => (
            <Card key={i} className="flex gap-3 p-4">
              <Skeleton className="size-9 rounded-[var(--radius-control)]" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-32" />
                <Skeleton className="h-3 w-56" />
              </div>
            </Card>
          ))}
        </div>
      ) : notifications.isError ? (
        <ErrorState onRetry={() => notifications.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={BellOff}
          title="Aucune notification"
          description="Les confirmations, messages et rappels de départ arriveront ici."
        />
      ) : (
        <>
          <m.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
            {list.map((notification) => {
              const presentation = PRESENTATION[notification.type] ?? DEFAULT_PRESENTATION
              const Icon = presentation.icon
              const target = targetOf(notification)
              const unreadItem = !notification.readAt

              const body = (
                <div className="flex gap-3 p-4">
                  <span
                    className={`flex size-9 shrink-0 items-center justify-center rounded-[var(--radius-control)] ${TONE[presentation.tone]}`}
                  >
                    <Icon className="size-[18px]" aria-hidden />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-baseline justify-between gap-2">
                      <p className={unreadItem ? 'font-display text-base font-bold' : 'font-display text-base font-bold text-ink-2'}>
                        {presentation.title}
                      </p>
                      <span className="shrink-0 text-caption text-muted">{formatFromNow(notification.createdAt)}</span>
                    </div>
                    <p className="mt-0.5 text-body leading-relaxed text-ink-2">{describe(notification)}</p>
                  </div>
                  {/* Point visuel decoratif + texte lisible par les lecteurs d'ecran (audit F333). */}
                  {unreadItem ? (
                    <>
                      <span className="mt-1.5 size-2 shrink-0 rounded-full bg-primary" aria-hidden />
                      <span className="sr-only">Non lue</span>
                    </>
                  ) : null}
                </div>
              )

              return (
                <m.li key={notification.id} variants={listItem}>
                  <Card
                    className={
                      unreadItem
                        ? 'ek-press overflow-hidden border-l-[3px] border-l-primary bg-surface'
                        : 'ek-press overflow-hidden border-l-[3px] border-l-transparent'
                    }
                  >
                    {target ? (
                      <Link
                        to={target}
                        onClick={() => unreadItem && markRead.mutate(notification.id)}
                        className="block transition-colors hover:bg-surface-2 active:bg-surface-2"
                      >
                        {body}
                      </Link>
                    ) : (
                      <button
                        type="button"
                        onClick={() => unreadItem && markRead.mutate(notification.id)}
                        disabled={!unreadItem}
                        aria-label={unreadItem ? 'Marquer comme lue' : undefined}
                        className="block w-full text-left transition-colors enabled:hover:bg-surface-2 disabled:cursor-default"
                      >
                        {body}
                      </button>
                    )}
                  </Card>
                </m.li>
              )
            })}
          </m.ul>

          {notifications.hasNextPage ? (
            <Button
              variant="secondary"
              block
              className="mt-4"
              loading={notifications.isFetchingNextPage}
              onClick={() => notifications.fetchNextPage()}
            >
              Voir plus ({Math.max(0, total - list.length)} restantes)
            </Button>
          ) : null}
        </>
      )}
    </PageContainer>
  )
}
