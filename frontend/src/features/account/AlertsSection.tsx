import { m } from 'motion/react'
import { BellRing, CircleDot, Flag, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { SectionTitle } from '@/components/layout/PageContainer'
import { useDeleteTripAlert, useMyTripAlerts } from '@/hooks/useAlerts'
import { describeError } from '@/lib/errors'
import { formatDayShort } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { TripAlertResponse } from '@/api/extended'

const MAX_ACTIVE_ALERTS = 10

/** Lien vers la recherche que l alerte surveille : memes parametres que l accueil. */
function searchPath(alert: TripAlertResponse): string {
  const params = new URLSearchParams({
    from: alert.originLabel,
    fromLat: String(alert.originLat),
    fromLng: String(alert.originLng),
    to: alert.destLabel,
    toLat: String(alert.destLat),
    toLng: String(alert.destLng),
    seats: String(alert.seats),
  })
  // Alerte « tous les modes » : la recherche part sans `type`.
  if (alert.tripType) params.set('type', alert.tripType)
  if (alert.date) params.set('date', alert.date)
  return `/search?${params.toString()}`
}

/**
 * « Mes alertes » (audit F514) : liste des alertes de recherche du compte et
 * suppression. Le plafond de 10 alertes actives est celui du serveur.
 */
export function AlertsSection() {
  const alerts = useMyTripAlerts()
  const remove = useDeleteTripAlert()
  const [toDelete, setToDelete] = useState<TripAlertResponse | null>(null)

  const list = alerts.data ?? []
  const active = list.filter((a) => a.active).length

  const confirmDelete = () => {
    if (!toDelete) return
    const alert = toDelete
    remove.mutate(alert.id, {
      onSuccess: () => toast.success('Alerte supprimée', { description: `${alert.originLabel} → ${alert.destLabel}` }),
      onError: (error) => toast.error(describeError(error, "L'alerte n'a pas pu être supprimée.")),
      onSettled: () => setToDelete(null),
    })
  }

  return (
    <section aria-labelledby="alerts-title">
      <SectionTitle
        action={
          alerts.isSuccess ? (
            <span className="tnum text-caption text-muted">
              {active} / {MAX_ACTIVE_ALERTS} active{active > 1 ? 's' : ''}
            </span>
          ) : null
        }
      >
        <span id="alerts-title">Mes alertes de recherche</span>
      </SectionTitle>

      {alerts.isPending ? (
        <div className="space-y-2">
          <Skeleton className="h-[84px] rounded-[var(--radius-card)]" />
          <Skeleton className="h-[84px] rounded-[var(--radius-card)]" />
        </div>
      ) : alerts.isError ? (
        <ErrorState onRetry={() => alerts.refetch()} />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon={BellRing}
            title="Aucune alerte"
            description="Quand une recherche ne donne rien, créez une alerte : vous serez prévenu dès qu'un trajet correspondant est publié."
            action={
              <Button asChild variant="secondary">
                <Link to="/">Rechercher un trajet</Link>
              </Button>
            }
            className="py-8"
          />
        </Card>
      ) : (
        <m.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((alert) => (
            <m.li key={alert.id} variants={listItem}>
              <Card className={alert.active ? 'border-l-[3px] border-l-primary p-4' : 'border-l-[3px] border-l-rule-strong p-4'}>
                <div className="flex items-start gap-3">
                  <div className="min-w-0 flex-1">
                    <p className="flex min-w-0 items-center gap-1.5 font-display text-base font-bold">
                      <CircleDot className="size-3.5 shrink-0 text-primary-ink" aria-hidden />
                      <span className="truncate">{alert.originLabel}</span>
                      <span aria-hidden className="text-muted">→</span>
                      <Flag className="size-3.5 shrink-0 text-danger-ink" aria-hidden />
                      <span className="truncate">{alert.destLabel}</span>
                    </p>
                    <p className="tnum mt-1 text-label text-muted">
                      {alert.date ? formatDayShort(alert.date) : 'Toutes dates'} · {alert.seats} place
                      {alert.seats > 1 ? 's' : ''} ·{' '}
                      {alert.tripType === 'QUOTIDIEN' ? 'quotidien' : alert.tripType === 'INTERURBAIN' ? 'interurbain' : 'tous modes'} ·
                      rayon {alert.radiusKm} km
                    </p>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <Badge tone={alert.active ? 'success' : 'neutral'}>{alert.active ? 'Active' : 'Terminée'}</Badge>
                      <Link
                        to={searchPath(alert)}
                        className="text-label font-medium text-primary-ink underline-offset-4 hover:underline"
                      >
                        Voir les départs
                      </Link>
                    </div>
                  </div>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={`Supprimer l'alerte ${alert.originLabel} → ${alert.destLabel}`}
                    onClick={() => setToDelete(alert)}
                    className="text-muted hover:bg-danger-soft hover:text-danger-ink"
                  >
                    <Trash2 className="size-4" aria-hidden />
                  </Button>
                </div>
              </Card>
            </m.li>
          ))}
        </m.ul>
      )}

      <p className="mt-3 text-label leading-relaxed text-muted">
        Une alerte surveille un axe (et une date, si vous en avez choisi une) et vous notifie dans l'application. Elle
        cesse d'elle-même une fois la date passée ; {MAX_ACTIVE_ALERTS} alertes actives au maximum.
      </p>

      <ConfirmDialog
        open={toDelete !== null}
        onOpenChange={(open) => !open && setToDelete(null)}
        title="Supprimer cette alerte ?"
        description={
          toDelete
            ? `Vous ne serez plus prévenu des trajets ${toDelete.originLabel} → ${toDelete.destLabel}. Vous pourrez en recréer une depuis une recherche.`
            : undefined
        }
        tone="danger"
        confirmLabel="Supprimer"
        loading={remove.isPending}
        onConfirm={confirmDelete}
      />
    </section>
  )
}
