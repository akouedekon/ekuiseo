import { Crosshair, LocateFixed } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { CityAutocomplete } from '@/components/trip/CityAutocomplete'
import { NearbyMap, type NearbyMapHandle, type NearbyMapPin } from '@/components/trip/NearbyMap'
import { PageMeta } from '@/components/layout/PageMeta'
import { NearbySheet, type NearbySheetState } from '@/features/trips/NearbySheet'
import { useNearbyTrips } from '@/hooks/useTrips'
import { useOnlineStatus } from '@/hooks/useNetwork'
import { cn } from '@/lib/cn'
import { FALLBACK_PLACES, shortName, type CityOption } from '@/lib/cities'
import { describeError, isDefinitiveError } from '@/lib/errors'
import { GEOLOCATION_MESSAGES, GeolocationError, getCurrentPosition, isGeolocationSupported, type DevicePosition } from '@/lib/geolocation'
import { VEHICLE_TYPE_LABEL } from '@/lib/labels'
import type { NearbyTripResponse, VehicleType } from '@/api/types'

/** Rayon de l ecran : 10 km, la portee d un zemidjan ou d un taxi de quartier. Meme valeur dans les textes. */
const NEARBY_RADIUS_KM = 10
/** Centre par defaut sans position : Cotonou (premiere entree du repli, memes coordonnees que le referentiel). */
const DEFAULT_CENTER = { lat: FALLBACK_PLACES[0].lat, lng: FALLBACK_PLACES[0].lng }
/**
 * Hauteur de la feuille sur mobile (fraction de l ecran) et largeur de la colonne au-dela de
 * 768 px : memes valeurs que les classes `h-[42%]` et `md:w-[400px]` de la feuille, transmises
 * a la carte pour que ses recadrages evitent la zone couverte.
 */
const SHEET_FRACTION = 0.42
const SHEET_COLUMN_PX = 400

type VehicleFilter = VehicleType | 'ALL'

const FILTERS: { value: VehicleFilter; label: string }[] = [
  { value: 'ALL', label: 'Tous' },
  { value: 'CAR', label: VEHICLE_TYPE_LABEL.CAR },
  { value: 'MOTO', label: VEHICLE_TYPE_LABEL.MOTO },
  { value: 'TRICYCLE', label: VEHICLE_TYPE_LABEL.TRICYCLE },
]

type PositionStatus = { kind: 'locating' } | { kind: 'ok' } | { kind: 'error'; error: GeolocationError }

/** Reference stable : `nearby.data ?? []` recreerait un tableau a chaque rendu. */
const NO_TRIPS: NearbyTripResponse[] = []

/**
 * Ecran « Autour de moi » (/autour, public) : carte plein ecran centree sur la position,
 * departs planifies proches epingles (voiture, moto, tricycle), feuille en bas qui les
 * liste, puces de filtre par type et bouton « Ma position ». Toucher un depart ouvre la
 * fiche trajet. Sans position (refus, appareil, delai), l ecran s ouvre sur Cotonou et
 * propose de saisir un lieu de depart. Le modele reste celui des trajets planifies : pas de
 * course a la demande.
 */
export function NearbyPage() {
  const [position, setPosition] = useState<DevicePosition | null>(null)
  // La demande de position part des l ouverture : l etat initial le dit deja, sans effet ni rendu en cascade.
  const [positionStatus, setPositionStatus] = useState<PositionStatus>(() =>
    isGeolocationSupported()
      ? { kind: 'locating' }
      : { kind: 'error', error: new GeolocationError('unsupported', GEOLOCATION_MESSAGES.unsupported) },
  )
  const [fallbackPlace, setFallbackPlace] = useState<CityOption | null>(null)
  const [filter, setFilter] = useState<VehicleFilter>('ALL')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const mapRef = useRef<NearbyMapHandle>(null)
  const online = useOnlineStatus()

  /** Reponse de l appareil : la position remplace le lieu de repli. */
  const applyPosition = useCallback((next: DevicePosition) => {
    setPosition(next)
    setFallbackPlace(null)
    setPositionStatus({ kind: 'ok' })
  }, [])
  const applyPositionError = useCallback((error: unknown) => {
    setPositionStatus({
      kind: 'error',
      error: error instanceof GeolocationError ? error : new GeolocationError('unavailable', GEOLOCATION_MESSAGES.unavailable),
    })
  }, [])

  // Une demande a l ouverture, jamais en boucle : l utilisateur relance par « Ma position ».
  // Une reponse arrivee apres le demontage est ignoree.
  useEffect(() => {
    if (!isGeolocationSupported()) return undefined
    let active = true
    getCurrentPosition().then(
      (next) => {
        if (active) applyPosition(next)
      },
      (error: unknown) => {
        if (active) applyPositionError(error)
      },
    )
    return () => {
      active = false
    }
  }, [applyPosition, applyPositionError])

  /** Bouton « Ma position » : relance explicite, avec l etat d attente. */
  const locate = () => {
    if (!isGeolocationSupported()) {
      setPositionStatus({ kind: 'error', error: new GeolocationError('unsupported', GEOLOCATION_MESSAGES.unsupported) })
      return
    }
    setPositionStatus({ kind: 'locating' })
    getCurrentPosition().then(applyPosition, applyPositionError)
  }

  // Point cherche : la position, sinon le lieu saisi en repli. Rien tant qu on n a ni l un ni l autre.
  const anchor = position ?? (fallbackPlace ? { lat: fallbackPlace.lat, lng: fallbackPlace.lng } : null)
  const aroundLabel = position ? 'vous' : fallbackPlace ? shortName(fallbackPlace) : 'vous'
  const nearby = useNearbyTrips(
    anchor ? { lat: anchor.lat, lng: anchor.lng, radiusKm: NEARBY_RADIUS_KM, vehicleType: filter === 'ALL' ? undefined : filter } : null,
  )
  const trips = nearby.data ?? NO_TRIPS

  const pins = useMemo<NearbyMapPin[]>(
    () =>
      trips.map((item) => ({
        id: item.trip.id,
        lat: item.boardingLat,
        lng: item.boardingLng,
        label: item.boardingLabel,
        vehicleType: item.trip.vehicle.vehicleType,
      })),
    [trips],
  )

  // Une epingle qui disparait (depart parti, filtre change) ne reste pas selectionnee : derive, pas synchronise.
  const selected = selectedId && trips.some((item) => item.trip.id === selectedId) ? selectedId : null

  const sheetState: NearbySheetState = (() => {
    if (positionStatus.kind === 'locating' && !anchor) return { kind: 'locating' }
    if (!anchor) {
      return {
        kind: 'position-error',
        message: positionStatus.kind === 'error' ? positionStatus.error.message : GEOLOCATION_MESSAGES.unavailable,
      }
    }
    if (nearby.data) return { kind: 'ready', trips }
    if (!online && nearby.isPending) return { kind: 'offline', onRetry: () => nearby.refetch() }
    if (nearby.isError) {
      return {
        kind: 'error',
        message: describeError(nearby.error, 'Impossible de charger les départs proches.'),
        onRetry: isDefinitiveError(nearby.error) ? undefined : () => nearby.refetch(),
      }
    }
    return { kind: 'loading' }
  })()

  const fallbackField = (
    <CityAutocomplete
      label="Lieu de départ"
      value={fallbackPlace}
      onChange={(place) => {
        setFallbackPlace(place)
        setSelectedId(null)
      }}
      placeholder="Ville ou quartier"
    />
  )

  return (
    <div className="relative -mb-8 h-[calc(100dvh-67px)] min-h-[480px] overflow-hidden bg-surface-2 md:-mb-12">
      <PageMeta
        title="Autour de moi"
        description="Les départs en voiture, moto ou tricycle proches de vous, sur la carte, à réserver en un geste."
      />

      {/* Carte en fond : sur mobile la feuille recouvre le bas, sur grand ecran la colonne prend la gauche. */}
      <div className="absolute inset-0">
        <NearbyMap
          ref={mapRef}
          center={anchor ?? DEFAULT_CENTER}
          user={position ? { lat: position.lat, lng: position.lng, accuracyM: position.accuracyM } : null}
          pins={pins}
          selectedId={selected}
          onSelect={setSelectedId}
          inset={{ bottomFraction: SHEET_FRACTION, leftPx: SHEET_COLUMN_PX, topPx: 64 }}
        />
      </div>

      {/* Puces de filtre et commandes, par-dessus la carte. */}
      <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex flex-col gap-2 p-3 md:left-[400px]">
        <div className="pointer-events-auto flex items-start justify-between gap-2">
          <div
            role="group"
            aria-label="Type de véhicule"
            className="ek-glass scroll-thin flex max-w-full items-center gap-1 overflow-x-auto rounded-[var(--radius-pill)] border border-rule p-1 shadow-e1"
          >
            {FILTERS.map((option) => {
              const active = option.value === filter
              return (
                <button
                  key={option.value}
                  type="button"
                  aria-pressed={active}
                  onClick={() => {
                    setFilter(option.value)
                    setSelectedId(null)
                  }}
                  className={cn(
                    'min-h-11 shrink-0 whitespace-nowrap rounded-[var(--radius-pill)] px-3.5 text-label font-semibold transition-[background-color,color]',
                    active ? 'bg-primary text-on-primary shadow-e1' : 'text-ink-2 hover:bg-surface-2 hover:text-ink',
                  )}
                >
                  {option.label}
                </button>
              )
            })}
          </div>
          <div className="flex shrink-0 flex-col gap-2">
            <Button
              variant="secondary"
              size="icon"
              aria-label={positionStatus.kind === 'locating' ? 'Recherche de votre position' : 'Ma position'}
              title="Ma position"
              loading={positionStatus.kind === 'locating'}
              onClick={locate}
              className="rounded-full"
            >
              <LocateFixed aria-hidden />
            </Button>
            {pins.length > 0 ? (
              <Button
                variant="secondary"
                size="icon"
                aria-label="Recentrer la carte"
                title="Recentrer"
                onClick={() => mapRef.current?.recenter()}
                className="rounded-full"
              >
                <Crosshair aria-hidden />
              </Button>
            ) : null}
          </div>
        </div>
      </div>

      {/* Feuille : 42 % de la hauteur en bas sur mobile (au-dessus de la barre basse), colonne a gauche au-dela. */}
      <NearbySheet
        state={sheetState}
        aroundLabel={aroundLabel}
        selectedId={selected}
        onSelect={setSelectedId}
        fallback={fallbackField}
        className={cn(
          'absolute inset-x-0 bottom-0 z-10 h-[42%] rounded-t-[20px] border-t border-rule bg-surface shadow-sheet',
          'pb-[calc(60px+env(safe-area-inset-bottom,0px))] md:pb-0',
          'md:inset-y-0 md:right-auto md:h-full md:w-[400px] md:rounded-none md:border-r md:border-t-0',
        )}
      />
    </div>
  )
}
