import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { Map as MapLibreMap, Marker as MapLibreMarker } from 'maplibre-gl'
import { Map as MapIcon } from 'lucide-react'
import { cn } from '@/lib/cn'
import { VEHICLE_TYPE_LABEL } from '@/lib/labels'
import { readToken } from '@/lib/theme'
import { VehicleTypeIcon } from '@/components/trip/VehicleTypeIcon'
import type { VehicleType } from '@/api/types'

/** Point de montee epingle sur la carte « Autour de moi ». */
export interface NearbyMapPin {
  id: string
  lat: number
  lng: number
  /** Libelle du point de montee (origine ou arret), affiche sur l epingle selectionnee. */
  label: string
  vehicleType: VehicleType | undefined | null
}

export interface NearbyMapUser {
  lat: number
  lng: number
  /** Precision annoncee (m) : dessinee en halo translucide quand elle est connue. */
  accuracyM?: number
}

/** Commandes de la carte exposees a l ecran (boutons « Recentrer »). */
export interface NearbyMapHandle {
  /** Recadre sur l utilisateur (ou le centre) et toutes les epingles, comme au premier affichage. */
  recenter: () => void
}

interface NearbyMapProps {
  /** Centre initial quand la position n est pas connue (Cotonou par defaut). */
  center: { lat: number; lng: number }
  user: NearbyMapUser | null
  pins: NearbyMapPin[]
  selectedId: string | null
  onSelect: (id: string | null) => void
  /**
   * Zone de la carte recouverte par la feuille des departs : fraction de la hauteur en bas
   * (mobile) ou pixels a gauche (colonne sur grand ecran). Les recadrages en tiennent compte.
   */
  inset: { bottomFraction?: number; leftPx?: number; topPx?: number }
  className?: string
}

/** Meme source que RouteMap : aucun fond de carte sans VITE_MAP_STYLE_URL. */
const MAP_STYLE_URL = import.meta.env.VITE_MAP_STYLE_URL as string | undefined

/** Zoom maximal du recadrage automatique : au-dela, un quartier entier disparait de l ecran. */
const FIT_MAX_ZOOM = 15
/** Zoom minimal quand on vole vers une epingle choisie dans la liste. */
const FOCUS_MIN_ZOOM = 14

/**
 * Carte plein ecran de l ecran « Autour de moi » : position de l utilisateur (point primaire
 * avec halo), epingles des points de montee colorees par type de vehicule, selection
 * synchronisee avec la feuille. MapLibre est charge a la demande (meme chunk `map` que
 * RouteMap). Sans style de tuiles, un panneau « carte indisponible » remplace la carte : la
 * liste reste utilisable seule.
 */
export const NearbyMap = forwardRef<NearbyMapHandle, NearbyMapProps>(function NearbyMap(props, ref) {
  if (!MAP_STYLE_URL) {
    return <MapUnavailable className={props.className} />
  }
  return <LiveNearbyMap ref={ref} styleUrl={MAP_STYLE_URL} {...props} />
})

/* ------------------------------------------------------------- Vraie carte */

interface MarkerEntry {
  marker: MapLibreMarker
  element: HTMLElement
}

const LiveNearbyMap = forwardRef<NearbyMapHandle, NearbyMapProps & { styleUrl: string }>(function LiveNearbyMap(
  { styleUrl, center, user, pins, selectedId, onSelect, inset, className },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const markerClassRef = useRef<typeof MapLibreMarker | null>(null)
  const boundsClassRef = useRef<typeof import('maplibre-gl').LngLatBounds | null>(null)
  const pinMarkersRef = useRef<Map<string, MarkerEntry>>(new Map())
  const userMarkerRef = useRef<MarkerEntry | null>(null)
  const fittedRef = useRef(false)
  // Toujours la derniere valeur des props, lue par les rappels asynchrones (chargement, clics).
  const latestRef = useRef({ center, user, pins, selectedId, onSelect, inset })
  latestRef.current = { center, user, pins, selectedId, onSelect, inset }

  const [failed, setFailed] = useState(false)
  // Incremente quand des marqueurs sont crees ou retires : les portails se re-rendent.
  const [markerVersion, setMarkerVersion] = useState(0)

  /**
   * Marges de recadrage : la feuille en bas sur mobile, la colonne a gauche au-dela de 768 px
   * (meme point de rupture `md` que les classes de l ecran), plus les puces en haut.
   */
  const paddingFor = (map: MapLibreMap) => {
    const { inset: current } = latestRef.current
    const height = map.getContainer().clientHeight
    const column = typeof window !== 'undefined' && window.matchMedia('(min-width: 768px)').matches
    return {
      top: (current.topPx ?? 0) + 24,
      bottom: !column && current.bottomFraction ? Math.round(height * current.bottomFraction) + 24 : 24,
      left: column ? (current.leftPx ?? 0) + 24 : 24,
      right: 24,
    }
  }

  const fitAll = (map: MapLibreMap, animate: boolean) => {
    const LngLatBounds = boundsClassRef.current
    if (!LngLatBounds) return
    const { center: c, user: u, pins: p } = latestRef.current
    const anchor = u ?? c
    const points: [number, number][] = [[anchor.lng, anchor.lat], ...p.map((pin) => [pin.lng, pin.lat] as [number, number])]
    if (points.length === 1) {
      map.easeTo({ center: points[0], zoom: 13, padding: paddingFor(map), duration: animate ? 500 : 0 })
      return
    }
    const bounds = points.reduce((acc, point) => acc.extend(point), new LngLatBounds(points[0], points[0]))
    map.fitBounds(bounds, { padding: paddingFor(map), maxZoom: FIT_MAX_ZOOM, duration: animate ? 500 : 0 })
  }

  useImperativeHandle(ref, () => ({
    recenter: () => {
      const map = mapRef.current
      if (map) fitAll(map, true)
    },
  }))

  /** Cree, deplace ou retire les epingles pour refleter `pins` (sans jamais recadrer). */
  const syncPins = () => {
    const map = mapRef.current
    const MarkerClass = markerClassRef.current
    if (!map || !MarkerClass) return
    const entries = pinMarkersRef.current
    const wanted = new Set<string>()
    let changed = false
    for (const pin of latestRef.current.pins) {
      wanted.add(pin.id)
      const existing = entries.get(pin.id)
      if (existing) {
        existing.marker.setLngLat([pin.lng, pin.lat])
        continue
      }
      const element = document.createElement('div')
      element.dataset.pinId = pin.id
      // Ecouteur natif : MapLibre ecoute le conteneur, sous la racine React ; on arrete la
      // propagation ici pour que le clic sur l epingle ne compte pas comme un clic sur la carte.
      element.addEventListener('click', (event) => {
        event.stopPropagation()
        latestRef.current.onSelect(pin.id)
      })
      const marker = new MarkerClass({ element, anchor: 'center' }).setLngLat([pin.lng, pin.lat]).addTo(map)
      entries.set(pin.id, { marker, element })
      changed = true
    }
    for (const [id, entry] of entries) {
      if (!wanted.has(id)) {
        entry.marker.remove()
        entries.delete(id)
        changed = true
      }
    }
    if (changed) setMarkerVersion((v) => v + 1)
  }

  /** Marqueur de l utilisateur et halo de precision (source GeoJSON, couleur du theme). */
  const syncUser = () => {
    const map = mapRef.current
    const MarkerClass = markerClassRef.current
    if (!map || !MarkerClass) return
    const current = latestRef.current.user
    if (!current) {
      userMarkerRef.current?.marker.remove()
      userMarkerRef.current = null
      setMarkerVersion((v) => v + 1)
      return
    }
    if (!userMarkerRef.current) {
      const element = document.createElement('div')
      element.dataset.userMarker = 'true'
      const marker = new MarkerClass({ element, anchor: 'center' }).setLngLat([current.lng, current.lat]).addTo(map)
      userMarkerRef.current = { marker, element }
      setMarkerVersion((v) => v + 1)
    } else {
      userMarkerRef.current.marker.setLngLat([current.lng, current.lat])
    }
    if (map.isStyleLoaded()) applyAccuracy(map, current)
  }

  useEffect(() => {
    let disposed = false
    let map: MapLibreMap | null = null
    let observer: MutationObserver | null = null
    // Copies locales pour le nettoyage : les refs elles-memes sont remises a zero ci-dessous.
    const pinMarkers = pinMarkersRef.current
    const userMarker = userMarkerRef

    void (async () => {
      try {
        const [{ Map, Marker, LngLatBounds }] = await Promise.all([
          import('maplibre-gl'),
          import('maplibre-gl/dist/maplibre-gl.css'),
        ])
        if (disposed || !containerRef.current) return
        const { center: c, user: u } = latestRef.current
        const start = u ?? c
        const instance = new Map({
          container: containerRef.current,
          style: styleUrl,
          center: [start.lng, start.lat],
          zoom: 13,
          attributionControl: { compact: true },
        })
        map = instance
        mapRef.current = instance
        markerClassRef.current = Marker
        boundsClassRef.current = LngLatBounds

        // Toucher la carte hors d une epingle : plus de selection.
        instance.on('click', () => latestRef.current.onSelect(null))

        instance.on('load', () => {
          if (disposed) return
          const currentUser = latestRef.current.user
          if (currentUser) applyAccuracy(instance, currentUser)
          // Couleur du halo relue au changement clair / sombre (audit F319).
          observer = new MutationObserver(() => {
            if (instance.getLayer('user-accuracy')) {
              instance.setPaintProperty('user-accuracy', 'circle-color', readToken('--primary', '#0e7c4a'))
            }
          })
          observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
        })

        syncUser()
        syncPins()
        fitAll(instance, false)
        fittedRef.current = true
      } catch {
        if (!disposed) setFailed(true)
      }
    })()

    return () => {
      disposed = true
      observer?.disconnect()
      for (const entry of pinMarkers.values()) entry.marker.remove()
      pinMarkers.clear()
      userMarker.current?.marker.remove()
      userMarker.current = null
      mapRef.current = null
      markerClassRef.current = null
      boundsClassRef.current = null
      fittedRef.current = false
      map?.remove()
    }
    // La carte n est creee qu une fois ; les changements de props passent par les effets ci-dessous.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [styleUrl])

  // Epingles : mises a jour sans recadrer, sauf la toute premiere fois qu il y en a (la
  // requete arrive apres la carte).
  useEffect(() => {
    syncPins()
    const map = mapRef.current
    if (map && pins.length > 0 && !fittedRef.current) {
      fitAll(map, false)
      fittedRef.current = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pins])

  // Premiere position connue apres l affichage : recadrage complet une fois.
  const hadUserRef = useRef(!!user)
  useEffect(() => {
    syncUser()
    const map = mapRef.current
    if (map && user && !hadUserRef.current) {
      hadUserRef.current = true
      fitAll(map, true)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user])

  // Selection depuis la liste : la carte glisse vers l epingle, en gardant la feuille libre.
  useEffect(() => {
    const map = mapRef.current
    if (!map || !selectedId) return
    const pin = pins.find((p) => p.id === selectedId)
    if (!pin) return
    map.easeTo({
      center: [pin.lng, pin.lat],
      zoom: Math.max(map.getZoom(), FOCUS_MIN_ZOOM),
      padding: paddingFor(map),
      duration: 450,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  if (failed) return <MapUnavailable className={className} />

  const pinById = new Map(pins.map((pin) => [pin.id, pin] as const))
  void markerVersion

  return (
    <>
      <div
        ref={containerRef}
        role="img"
        aria-label={`Carte des départs autour de vous, ${pins.length} point${pins.length > 1 ? 's' : ''} de montée`}
        className={cn('size-full bg-surface-2', className)}
      />
      {/* Les marqueurs sont des elements DOM de MapLibre ; leur contenu reste rendu par React (portails). */}
      {userMarkerRef.current ? createPortal(<UserDot />, userMarkerRef.current.element, 'user') : null}
      {Array.from(pinMarkersRef.current.entries()).map(([id, entry]) => {
        const pin = pinById.get(id)
        return pin ? createPortal(<Pin pin={pin} selected={id === selectedId} />, entry.element, id) : null
      })}
    </>
  )
})

/** Halo de precision : cercle en metres converti en pixels selon le zoom (0,075 m/px a l equateur au zoom 20). */
function applyAccuracy(map: MapLibreMap, user: NearbyMapUser) {
  const accuracy = user.accuracyM ?? 0
  const data = {
    type: 'FeatureCollection' as const,
    features:
      accuracy > 15
        ? [{ type: 'Feature' as const, properties: {}, geometry: { type: 'Point' as const, coordinates: [user.lng, user.lat] } }]
        : [],
  }
  const metersPerPixelAtZoom20 = 0.0746 * Math.cos((user.lat * Math.PI) / 180)
  const radiusAtZoom20 = accuracy / Math.max(metersPerPixelAtZoom20, 0.0001)
  const source = map.getSource('user-accuracy') as { setData: (d: unknown) => void } | undefined
  if (source) {
    source.setData(data)
  } else {
    map.addSource('user-accuracy', { type: 'geojson', data })
    map.addLayer({
      id: 'user-accuracy',
      type: 'circle',
      source: 'user-accuracy',
      paint: {
        'circle-color': readToken('--primary', '#0e7c4a'),
        'circle-opacity': 0.14,
        'circle-radius': ['interpolate', ['exponential', 2], ['zoom'], 0, 0, 20, radiusAtZoom20],
      },
    })
    return
  }
  map.setPaintProperty('user-accuracy', 'circle-radius', ['interpolate', ['exponential', 2], ['zoom'], 0, 0, 20, radiusAtZoom20])
}

/* ---------------------------------------------------------------- Marqueurs */

/** Point de l utilisateur : pastille primaire cerclee, halo qui respire (sauf mouvement reduit). */
function UserDot() {
  return (
    <span role="img" aria-label="Votre position" className="relative flex size-6 items-center justify-center">
      <span aria-hidden className="absolute inset-0 rounded-full bg-primary opacity-30 motion-safe:animate-ping" />
      <span aria-hidden className="relative size-4 rounded-full border-[3px] border-surface bg-primary shadow-e2" />
    </span>
  )
}

/** Teinte de l epingle par type de vehicule : voiture primaire, moto accent, tricycle encre. */
const PIN_TONE: Record<VehicleType, string> = {
  CAR: 'bg-primary text-on-primary',
  MOTO: 'bg-accent text-on-accent',
  TRICYCLE: 'bg-ink text-surface',
}

function Pin({ pin, selected }: { pin: NearbyMapPin; selected: boolean }) {
  const type: VehicleType = pin.vehicleType ?? 'CAR'
  return (
    <button
      type="button"
      aria-label={`${VEHICLE_TYPE_LABEL[type]}, départ à ${pin.label}`}
      aria-pressed={selected}
      className={cn(
        'relative flex size-11 items-center justify-center rounded-full outline-none',
        'transition-transform duration-150 hover:scale-105 focus-visible:ring-2 focus-visible:ring-focus-ring',
        selected && 'z-10 scale-110',
      )}
    >
      <span
        aria-hidden
        className={cn(
          'flex size-9 items-center justify-center rounded-full border-2 border-surface shadow-e2',
          PIN_TONE[type],
          selected && 'ring-2 ring-focus-ring',
        )}
      >
        <VehicleTypeIcon type={type} className="size-[18px]" />
      </span>
      {selected ? (
        <span
          aria-hidden
          className="absolute bottom-full left-1/2 mb-1 max-w-[180px] -translate-x-1/2 truncate whitespace-nowrap rounded-[var(--radius-chip)] border border-rule bg-surface px-2 py-1 text-caption font-semibold text-ink shadow-e2"
        >
          {pin.label}
        </span>
      ) : null}
    </button>
  )
}

/* ------------------------------------------------------------------- Repli */

/** Aucun fond de carte (VITE_MAP_STYLE_URL vide, ou chargement echoue) : la liste seule fait l ecran. */
function MapUnavailable({ className }: { className?: string }) {
  return (
    <div className={cn('relative size-full overflow-hidden bg-surface-2', className)} role="img" aria-label="Carte indisponible">
      <svg className="absolute inset-0 size-full" aria-hidden>
        <defs>
          <pattern id="ek-nearby-grid" width="24" height="24" patternUnits="userSpaceOnUse">
            <path d="M24 0H0v24" fill="none" stroke="var(--rule)" strokeWidth="1" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#ek-nearby-grid)" />
      </svg>
      <div className="absolute inset-x-4 top-20 mx-auto flex max-w-md items-center gap-2.5 rounded-[var(--radius-card)] border border-rule bg-surface px-4 py-3 text-label text-ink-2 shadow-e2">
        <MapIcon className="size-4 shrink-0 text-muted" aria-hidden />
        <span>Carte indisponible. Les départs proches restent listés ci-dessous.</span>
      </div>
    </div>
  )
}
