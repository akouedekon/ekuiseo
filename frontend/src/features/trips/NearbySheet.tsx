import { BellPlus, LocateOff, MapPinOff, Search, Users } from 'lucide-react'
import { useEffect, useRef, type ReactNode } from 'react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState, OfflineState } from '@/components/ui/states'
import { VehicleTypeIcon } from '@/components/trip/VehicleTypeIcon'
import { cn } from '@/lib/cn'
import { formatDistanceKm, formatFcfa, formatFromNow, formatTime } from '@/lib/format'
import { VEHICLE_TYPE_LABEL } from '@/lib/labels'
import type { NearbyTripResponse, VehicleType } from '@/api/types'

/** Ce que la feuille affiche, decide par l ecran (position, requete, reseau). */
export type NearbySheetState =
  | { kind: 'locating' }
  /** Position refusee, indisponible ou non supportee : explication + saisie d un lieu (repli). */
  | { kind: 'position-error'; message: string }
  /** Une position est connue, la requete est en cours. */
  | { kind: 'loading' }
  | { kind: 'offline'; onRetry: () => void }
  | { kind: 'error'; message: string; onRetry?: () => void }
  | { kind: 'ready'; trips: NearbyTripResponse[] }

interface NearbySheetProps {
  state: NearbySheetState
  /** Lieu autour duquel on cherche (« votre position » ou la ville choisie en repli). */
  aroundLabel: string
  selectedId: string | null
  onSelect: (id: string | null) => void
  /** Champ de repli (CityAutocomplete), rendu sous l explication quand la position manque. */
  fallback?: ReactNode
  className?: string
}

/**
 * Feuille des departs proches (bas de l ecran sur mobile, colonne sur grand ecran) : titre
 * avec le nombre de departs, liste compacte, et les etats vides. Presentationnelle et testee
 * seule : la position, la requete et le reseau sont geres par NearbyPage.
 */
export function NearbySheet({ state, aroundLabel, selectedId, onSelect, fallback, className }: NearbySheetProps) {
  const count = state.kind === 'ready' ? state.trips.length : null
  const title =
    count === null
      ? 'Autour de moi'
      : count === 0
        ? 'Aucun départ à proximité'
        : `${count} départ${count > 1 ? 's' : ''} près de ${aroundLabel}`

  return (
    <section aria-label="Départs à proximité" className={cn('flex min-h-0 flex-col', className)}>
      {/* Poignee visuelle : la feuille se lit comme un panneau glissant, meme sans geste. */}
      <div aria-hidden className="mx-auto mt-2.5 h-1 w-10 shrink-0 rounded-full bg-rule-strong md:hidden" />
      <header className="shrink-0 px-4 pb-2 pt-3 sm:px-5">
        <h1 tabIndex={-1} className="font-display text-title font-bold tracking-[-0.02em] outline-none">
          {title}
        </h1>
        {state.kind === 'ready' && count ? (
          <p className="mt-0.5 text-caption text-muted">Départs planifiés à réserver ; distance jusqu’au point de montée.</p>
        ) : null}
      </header>

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-4 pb-4 sm:px-5">
        {state.kind === 'locating' ? (
          <div role="status" aria-live="polite" className="space-y-3 py-1">
            <p className="text-body text-ink-2">Recherche de votre position…</p>
            <Skeleton className="h-[76px] rounded-[var(--radius-card)]" />
            <Skeleton className="h-[76px] rounded-[var(--radius-card)]" />
          </div>
        ) : state.kind === 'position-error' ? (
          <div className="space-y-4 py-1">
            <div className="flex items-start gap-3 rounded-[var(--radius-card)] border border-rule bg-surface-2 p-4">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-accent-soft text-accent-ink">
                <LocateOff className="size-5" aria-hidden />
              </span>
              <p className="text-body text-ink-2">{state.message}</p>
            </div>
            {fallback ? <div>{fallback}</div> : null}
          </div>
        ) : state.kind === 'loading' ? (
          <div role="status" aria-label="Chargement des départs" className="space-y-3 py-1">
            <Skeleton className="h-[76px] rounded-[var(--radius-card)]" />
            <Skeleton className="h-[76px] rounded-[var(--radius-card)]" />
            <Skeleton className="h-[76px] rounded-[var(--radius-card)]" />
          </div>
        ) : state.kind === 'offline' ? (
          <OfflineState
            title="Hors ligne"
            description="Les départs proches demandent une connexion. Réessayez dès que le réseau revient."
            onRetry={state.onRetry}
            headingLevel="h2"
            className="py-6"
          />
        ) : state.kind === 'error' ? (
          <ErrorState title="Départs indisponibles" description={state.message} onRetry={state.onRetry} headingLevel="h2" className="py-6" />
        ) : state.trips.length === 0 ? (
          <EmptyState
            icon={MapPinOff}
            title="Personne ne part d’ici pour l’instant"
            description={`Aucun départ à moins de 10 km de ${aroundLabel}. Cherchez par ville et date, ou créez une alerte : nous vous prévenons dès qu’un conducteur publie.`}
            headingLevel="h2"
            className="py-6"
            action={
              <div className="flex flex-wrap justify-center gap-2">
                <Button asChild variant="secondary" size="sm">
                  <Link to="/">
                    <Search aria-hidden />
                    Recherche classique
                  </Link>
                </Button>
                <Button asChild variant="outlineBrand" size="sm">
                  <Link to="/me?tab=alerts">
                    <BellPlus aria-hidden />
                    Créer une alerte
                  </Link>
                </Button>
              </div>
            }
          />
        ) : (
          <ul className="space-y-2.5" aria-label="Départs à proximité, du plus proche au plus lointain">
            {state.trips.map((item) => (
              <NearbyTripItem key={item.trip.id} item={item} selected={item.trip.id === selectedId} onSelect={onSelect} />
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

/**
 * Carte compacte d un depart proche : type, heure (relative et absolue), origine -> destination,
 * prix, places et distance au point de montee. Le survol ou le focus la met en avant sur la
 * carte ; toucher ouvre la fiche trajet existante.
 */
export function NearbyTripItem({
  item,
  selected,
  onSelect,
}: {
  item: NearbyTripResponse
  selected: boolean
  onSelect: (id: string | null) => void
}) {
  const { trip } = item
  const ref = useRef<HTMLLIElement>(null)
  const type: VehicleType = trip.vehicle.vehicleType ?? 'CAR'
  const full = trip.seatsAvailable === 0
  const price = trip.segmentPriceFcfa != null && trip.segmentPriceFcfa > 0 ? trip.segmentPriceFcfa : trip.pricePerSeat
  // Le point de montee differe de l origine quand c est un arret intermediaire : on le dit.
  const boardsAtStop = item.boardingLabel !== trip.originLabel

  // Selection venue de la carte : la carte compacte se met a portee de vue.
  useEffect(() => {
    if (selected) ref.current?.scrollIntoView({ block: 'nearest' })
  }, [selected])

  return (
    <li ref={ref}>
      <Link
        to={`/trips/${trip.id}`}
        aria-current={selected ? 'true' : undefined}
        aria-label={`${VEHICLE_TYPE_LABEL[type]}, ${trip.originLabel} vers ${trip.destLabel}, départ ${formatTime(trip.departureAt)}, ${formatFcfa(price)} par place, montée à ${item.boardingLabel} à ${formatDistanceKm(item.distanceKm)}${full ? ', complet' : ''}`}
        onMouseEnter={() => onSelect(trip.id)}
        onFocus={() => onSelect(trip.id)}
        className={cn(
          'ek-lift block rounded-[var(--radius-card)] border bg-surface p-3.5 shadow-e1 transition-[border-color,box-shadow]',
          selected ? 'border-primary shadow-e2 ring-1 ring-primary' : 'border-rule',
          full && 'border-l-[3px] border-l-danger',
        )}
      >
        <div className="flex items-start gap-3">
          <span
            aria-hidden
            className={cn(
              'flex size-10 shrink-0 items-center justify-center rounded-full',
              type === 'CAR' ? 'bg-primary-soft text-primary-ink' : type === 'MOTO' ? 'bg-accent-soft text-accent-ink' : 'bg-surface-2 text-ink',
            )}
          >
            <VehicleTypeIcon type={type} className="size-5" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex items-baseline justify-between gap-2">
              <span className="tnum font-display text-body font-bold">
                {formatTime(trip.departureAt)}
                <span className="ml-1.5 font-sans text-caption font-normal text-muted">{formatFromNow(trip.departureAt)}</span>
              </span>
              <span className="tnum shrink-0 font-display text-body font-extrabold text-ink">{formatFcfa(price)}</span>
            </span>
            <span className="mt-0.5 block truncate text-body font-semibold text-ink">
              {trip.originLabel} <span className="text-muted">→</span> {trip.destLabel}
            </span>
            <span className="mt-1.5 flex flex-wrap items-center gap-1.5">
              <Badge tone="neutral">{VEHICLE_TYPE_LABEL[type]}</Badge>
              <Badge tone={full ? 'danger' : trip.seatsAvailable <= 1 ? 'warning' : 'neutral'}>
                <Users aria-hidden />
                {full ? 'Complet' : `${trip.seatsAvailable} pl.`}
              </Badge>
              <span className="tnum text-caption text-muted">
                à {formatDistanceKm(item.distanceKm)}
                {boardsAtStop ? ` · montée à ${item.boardingLabel}` : ''}
              </span>
            </span>
          </span>
        </div>
      </Link>
    </li>
  )
}
