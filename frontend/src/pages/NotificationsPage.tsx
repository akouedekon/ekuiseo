import { motion } from 'motion/react'
import {
  Bell,
  BellOff,
  CalendarClock,
  CheckCheck,
  CheckCircle2,
  CreditCard,
  MessageSquare,
  SearchCheck,
  Star,
  XCircle,
  type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
} from '@/hooks/useNotifications'
import { formatFcfa, formatFromNow } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { NotificationResponse, NotificationType } from '@/api/types'

/**
 * `Partial` (pas `Record` total) deliberement : une valeur de NotificationType
 * absente d'ici retombe sur DEFAULT_PRESENTATION plutot que de planter le rendu
 * (voir l'incident sur SEARCH_ALERT_MATCH/SUBSCRIPTION_ACTIVATED, qui manquaient
 * purement et simplement du type NotificationType cote front alors que le
 * serveur les emet deja).
 */
const PRESENTATION: Partial<Record<NotificationType, { icon: LucideIcon; tone: string; title: string }>> = {
  BOOKING_CONFIRMED: { icon: CheckCircle2, tone: 'bg-[var(--vert-soft)] text-[var(--vert)]', title: 'Réservation confirmée' },
  BOOKING_CANCELLED: { icon: XCircle, tone: 'bg-[var(--vermillon-soft)] text-[var(--vermillon)]', title: 'Réservation annulée' },
  PAYMENT_SUCCEEDED: { icon: CreditCard, tone: 'bg-[var(--vert-soft)] text-[var(--vert)]', title: 'Acompte reçu' },
  PAYMENT_FAILED: { icon: CreditCard, tone: 'bg-[var(--vermillon-soft)] text-[var(--vermillon)]', title: 'Paiement échoué' },
  NEW_MESSAGE: { icon: MessageSquare, tone: 'bg-[var(--indigo-soft)] text-[var(--indigo)]', title: 'Nouveau message' },
  TRIP_REMINDER: { icon: CalendarClock, tone: 'bg-[var(--ocre-soft)] text-[var(--ocre-ink)]', title: 'Départ imminent' },
  NEW_REVIEW: { icon: Star, tone: 'bg-[var(--ocre-soft)] text-[var(--ocre-ink)]', title: 'Nouvel avis' },
  SEARCH_ALERT_MATCH: { icon: SearchCheck, tone: 'bg-[var(--indigo-soft)] text-[var(--indigo)]', title: 'Trajet correspondant trouvé' },
  SUBSCRIPTION_ACTIVATED: { icon: CheckCircle2, tone: 'bg-[var(--vert-soft)] text-[var(--vert)]', title: 'Abonnement activé' },
  REPORT_RECEIVED: { icon: Bell, tone: 'bg-[var(--ocre-soft)] text-[var(--ocre-ink)]', title: 'Signalement reçu' },
}

const DEFAULT_PRESENTATION = { icon: Bell, tone: 'bg-[var(--surface-calm)] text-ink-2', title: 'Notification' }

/** Resume lisible construit a partir de la charge utile de la notification. */
function describe(notification: NotificationResponse): string {
  const payload = notification.payload ?? {}
  const str = (key: string) => (typeof payload[key] === 'string' ? (payload[key] as string) : undefined)
  const num = (key: string) => (typeof payload[key] === 'number' ? (payload[key] as number) : undefined)

  switch (notification.type) {
    case 'PAYMENT_SUCCEEDED': {
      const amount = num('amount')
      return amount ? `Acompte de ${formatFcfa(amount)} bien reçu. Votre place est bloquée.` : 'Acompte bien reçu.'
    }
    case 'PAYMENT_FAILED':
      return "Le paiement de l'acompte n'a pas abouti. La place n'est pas encore réservée."
    case 'NEW_MESSAGE': {
      const from = str('from')
      return from ? `${from} vous a écrit à propos de votre trajet.` : 'Vous avez reçu un message.'
    }
    case 'BOOKING_CONFIRMED': {
      const origin = str('origin')
      const destination = str('destination')
      return origin && destination
        ? `Votre place ${origin} → ${destination} est confirmée.`
        : 'Votre réservation est confirmée.'
    }
    case 'BOOKING_CANCELLED':
      return str('by') === 'driver'
        ? 'Le conducteur a annulé le trajet. Votre acompte est remboursé.'
        : 'La réservation a été annulée.'
    case 'TRIP_REMINDER':
      return 'Votre trajet part bientôt. Pensez à prévoir le solde en espèces.'
    case 'NEW_REVIEW': {
      const rating = num('rating')
      const from = str('from')
      return `${from ?? 'Un passager'} vous a laissé ${rating ?? 5} étoiles.`
    }
    case 'SEARCH_ALERT_MATCH':
      return 'Un trajet correspond à une de vos alertes de recherche.'
    case 'SUBSCRIPTION_ACTIVATED':
      return 'Votre abonnement conducteur est actif : plus de commission ce mois-ci.'
    case 'REPORT_RECEIVED':
      return 'Un signalement vous concernant a été reçu par la modération.'
    default:
      return ''
  }
}

/** Lien de destination deduit de la charge utile. */
function targetOf(notification: NotificationResponse): string | null {
  const payload = notification.payload ?? {}
  const bookingId = typeof payload.bookingId === 'string' ? payload.bookingId : null
  const tripId = typeof payload.tripId === 'string' ? payload.tripId : null
  if (notification.type === 'NEW_MESSAGE' && bookingId) return `/bookings/${bookingId}/messages`
  if (bookingId) return '/bookings'
  if (tripId) return `/trips/${tripId}`
  return null
}

export function NotificationsPage() {
  const notifications = useNotifications()
  const markRead = useMarkNotificationRead()
  const markAll = useMarkAllNotificationsRead()

  const list = notifications.data?.data ?? []
  const unread = list.filter((n) => !n.readAt).length

  return (
    <PageContainer width="md">

      <PageHeader
        title="Notifications"
        back={false}
        subtitle={unread > 0 ? `${unread} non lue${unread > 1 ? 's' : ''}` : 'Tout est à jour'}
        actions={
          unread > 0 ? (
            <Button variant="ghost" size="sm" onClick={() => markAll.mutate()}>
              <CheckCheck className="size-4" aria-hidden />
              <span className="hidden sm:inline">Tout marquer comme lu</span>
            </Button>
          ) : undefined
        }
      />

      {notifications.isPending ? (
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
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((notification) => {
            const presentation = PRESENTATION[notification.type] ?? DEFAULT_PRESENTATION
            const Icon = presentation.icon
            const target = targetOf(notification)
            const unreadItem = !notification.readAt

            const body = (
              <div className="flex gap-3 p-4">
                <span
                  className={`flex size-9 shrink-0 items-center justify-center rounded-[var(--radius-control)] ${presentation.tone}`}
                >
                  <Icon className="size-[18px]" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <p className={unreadItem ? 'font-display text-[15px] font-bold' : 'font-display text-[15px] font-bold text-ink-2'}>
                      {presentation.title}
                    </p>
                    <span className="shrink-0 text-[12px] text-muted">{formatFromNow(notification.createdAt)}</span>
                  </div>
                  <p className="mt-0.5 text-[14px] leading-relaxed text-ink-2">{describe(notification)}</p>
                </div>
                {unreadItem ? (
                  <span className="mt-1.5 size-2 shrink-0 rounded-full bg-[var(--indigo)]" aria-label="Non lue" />
                ) : null}
              </div>
            )

            return (
              <motion.li key={notification.id} variants={listItem}>
                <Card
                  className={
                    unreadItem
                      ? 'border-l-[3px] border-l-[var(--indigo)] bg-surface'
                      : 'border-l-[3px] border-l-transparent'
                  }
                >
                  {target ? (
                    <Link
                      to={target}
                      onClick={() => unreadItem && markRead.mutate(notification.id)}
                      className="block transition-colors hover:bg-[var(--surface-calm)]"
                    >
                      {body}
                    </Link>
                  ) : (
                    <button
                      type="button"
                      onClick={() => unreadItem && markRead.mutate(notification.id)}
                      className="block w-full text-left transition-colors hover:bg-[var(--surface-calm)]"
                    >
                      {body}
                    </button>
                  )}
                </Card>
              </motion.li>
            )
          })}
        </motion.ul>
      )}
    </PageContainer>
  )
}
