import { useEffect, useRef, useState } from 'react'
import type { Map as MapLibreMap, Marker as MapLibreMarker } from 'maplibre-gl'
import { Map as MapIcon, Maximize2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'
import { estimateDurationMinutes, haversineKm } from '@/lib/cities'
import { formatDuration } from '@/lib/format'
import { projectOntoRoute } from '@/lib/liveTracking'

export interface RouteMapPoint {
  label: string
  lat: number
  lng: number
  kind: 'origin' | 'stop' | 'destination'
}

/** Vehicule suivi en direct (V23) : position, cap eventuel, et fraicheur (`stale` = position perimee). */
export interface RouteMapVehicle {
  lat: number
  lng: number
  heading?: number | null
  stale?: boolean
}

/**
 * Style MapLibre.
 *
 * Aucune cle de tuiles n'est fournie par defaut : les fonds raster libres
 * (openstreetmap.org) interdisent l'usage applicatif, et un fond casse serait
 * pire que pas de fond du tout. On n'active donc la vraie carte que si
 * VITE_MAP_STYLE_URL est renseigne (MapTiler, Stadia, serveur de tuiles
 * interne…), sinon on dessine un trace stylise, qui reste informatif.
 *
 * TODO(infra) : provisionner un fournisseur de tuiles et renseigner
 * VITE_MAP_STYLE_URL dans l'environnement de deploiement.
 */
const MAP_STYLE_URL = import.meta.env.VITE_MAP_STYLE_URL as string | undefined

export function RouteMap({
  points,
  className,
  interactive = true,
  activation = 'always',
  vehicle,
}: {
  points: RouteMapPoint[]
  className?: string
  interactive?: boolean
  /** Position du vehicule en direct : marqueur deplace sans recreer la carte. */
  vehicle?: RouteMapVehicle | null
  /**
   * `always` : la carte repond au doigt des l'affichage. `on-demand` (mobile,
   * audit L8) : elle n'intercepte ni le defilement ni le pincement tant que
   * l'utilisateur n'a pas touche « Agrandir la carte ».
   */
  activation?: 'always' | 'on-demand'
}) {
  const [activated, setActivated] = useState(activation === 'always')
  if (!MAP_STYLE_URL || points.length < 2) {
    return <StylisedRoute points={points} className={className} vehicle={vehicle} />
  }
  return (
    <div className={cn('relative', className)}>
      <LiveMap points={points} className="size-full" interactive={interactive && activated} vehicle={vehicle} />
      {!activated ? (
        <div className="absolute inset-x-0 bottom-3 flex justify-center">
          <Button variant="secondary" size="sm" onClick={() => setActivated(true)}>
            <Maximize2 className="size-4" aria-hidden />
            Agrandir la carte
          </Button>
        </div>
      ) : null}
    </div>
  )
}

/* ------------------------------------------------------------- Vraie carte */

/** Lit un token de couleur du theme courant (ex. `--primary`), tel que resolu sur <html>. */
function readToken(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

/** Marqueur du vehicule : pastille primaire, fleche orientee selon le cap, attenuee si la position est perimee. */
function vehicleMarkerElement(): HTMLSpanElement {
  const el = document.createElement('span')
  el.setAttribute('role', 'img')
  el.style.cssText =
    'display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:var(--primary);border:2px solid var(--surface);box-shadow:0 1px 4px rgba(0,0,0,.4);transition:opacity .3s'
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  svg.setAttribute('viewBox', '0 0 24 24')
  svg.setAttribute('width', '14')
  svg.setAttribute('height', '14')
  svg.setAttribute('aria-hidden', 'true')
  svg.style.transition = 'transform .3s'
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
  path.setAttribute('d', 'M12 3l7 17-7-4-7 4z')
  path.setAttribute('fill', 'var(--surface)')
  svg.appendChild(path)
  el.appendChild(svg)
  return el
}

function applyVehicleStyle(el: HTMLElement, vehicle: RouteMapVehicle) {
  const hasHeading = vehicle.heading !== null && vehicle.heading !== undefined
  el.setAttribute('aria-label', vehicle.stale ? 'Véhicule (dernière position connue)' : 'Véhicule')
  el.style.opacity = vehicle.stale ? '0.45' : '1'
  const arrow = el.firstElementChild as HTMLElement | null
  if (arrow) {
    arrow.style.transform = hasHeading ? `rotate(${vehicle.heading}deg)` : 'none'
    arrow.style.visibility = hasHeading ? 'visible' : 'hidden'
  }
}

function LiveMap({
  points,
  className,
  interactive,
  vehicle,
}: {
  points: RouteMapPoint[]
  className?: string
  interactive: boolean
  vehicle?: RouteMapVehicle | null
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [failed, setFailed] = useState(false)
  // La carte et la classe Marker (import dynamique) sont gardees en ref : le vehicule se
  // deplace sans recreer la carte ni relancer fitBounds (un fitBounds a chaque position
  // rendrait la carte inutilisable au doigt).
  const mapRef = useRef<MapLibreMap | null>(null)
  const markerClassRef = useRef<typeof MapLibreMarker | null>(null)
  const vehicleMarkerRef = useRef<MapLibreMarker | null>(null)
  const vehicleRef = useRef<RouteMapVehicle | null | undefined>(null)

  const syncVehicle = () => {
    const map = mapRef.current
    const MarkerClass = markerClassRef.current
    if (!map || !MarkerClass) return
    const current = vehicleRef.current
    if (!current) {
      vehicleMarkerRef.current?.remove()
      vehicleMarkerRef.current = null
      return
    }
    if (!vehicleMarkerRef.current) {
      vehicleMarkerRef.current = new MarkerClass({ element: vehicleMarkerElement() })
        .setLngLat([current.lng, current.lat])
        .addTo(map)
    } else {
      vehicleMarkerRef.current.setLngLat([current.lng, current.lat])
    }
    applyVehicleStyle(vehicleMarkerRef.current.getElement(), current)
  }

  useEffect(() => {
    vehicleRef.current = vehicle
    syncVehicle()
  }, [vehicle])

  useEffect(() => {
    let disposed = false
    let map: { remove: () => void } | null = null
    let observer: MutationObserver | null = null

    // Chargement paresseux : maplibre-gl pese lourd, il ne doit pas entrer
    // dans le paquet initial de l'ecran d'accueil.
    void (async () => {
      try {
        const [{ Map, Marker, LngLatBounds }] = await Promise.all([
          import('maplibre-gl'),
          import('maplibre-gl/dist/maplibre-gl.css'),
        ])
        if (disposed || !containerRef.current) return

        const instance = new Map({
          container: containerRef.current,
          style: MAP_STYLE_URL!,
          interactive,
          attributionControl: { compact: true },
        })
        map = instance
        mapRef.current = instance
        markerClassRef.current = Marker

        // Couleur du trace lue dans le theme (audit F319), reappliquee au changement clair / sombre.
        const applyLineColor = () => {
          if (instance.getLayer('route-line')) {
            instance.setPaintProperty('route-line', 'line-color', readToken('--primary', '#0e7c4a'))
          }
        }

        instance.on('load', () => {
          instance.addSource('route', {
            type: 'geojson',
            data: {
              type: 'Feature',
              properties: {},
              geometry: { type: 'LineString', coordinates: points.map((p) => [p.lng, p.lat]) },
            },
          })
          instance.addLayer({
            id: 'route-line',
            type: 'line',
            source: 'route',
            layout: { 'line-cap': 'round', 'line-join': 'round' },
            paint: { 'line-color': readToken('--primary', '#0e7c4a'), 'line-width': 4 },
          })
          observer = new MutationObserver(applyLineColor)
          observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
        })

        for (const point of points) {
          const el = document.createElement('span')
          el.setAttribute('aria-label', point.label)
          el.style.cssText = `width:${point.kind === 'stop' ? 10 : 14}px;height:${point.kind === 'stop' ? 10 : 14}px;border-radius:${point.kind === 'destination' ? '3px' : '50%'};background:${point.kind === 'destination' ? 'var(--danger)' : point.kind === 'origin' ? 'var(--primary)' : 'var(--rule-strong)'};border:2px solid var(--surface);box-shadow:0 1px 3px rgba(0,0,0,.35)`
          new Marker({ element: el }).setLngLat([point.lng, point.lat]).addTo(instance)
        }

        const bounds = points.reduce(
          (acc, point) => acc.extend([point.lng, point.lat] as [number, number]),
          new LngLatBounds([points[0].lng, points[0].lat], [points[0].lng, points[0].lat]),
        )
        instance.fitBounds(bounds, { padding: 48, duration: 0 })
        // Vehicule deja connu au moment ou la carte devient prete (lu via la ref).
        syncVehicle()
      } catch {
        if (!disposed) setFailed(true)
      }
    })()

    return () => {
      disposed = true
      observer?.disconnect()
      vehicleMarkerRef.current?.remove()
      vehicleMarkerRef.current = null
      mapRef.current = null
      markerClassRef.current = null
      map?.remove()
    }
  }, [points, interactive])

  if (failed) return <StylisedRoute points={points} className={className} vehicle={vehicle} />

  return (
    <div
      ref={containerRef}
      role="img"
      aria-label={`Carte du trajet de ${points[0]?.label} à ${points[points.length - 1]?.label}`}
      className={cn('overflow-hidden rounded-[var(--radius-card)] border border-rule bg-surface-2', className)}
    />
  )
}

/* --------------------------------------------------- Trace stylise (repli) */

/**
 * Projette les points sur le cadre SVG en preservant les proportions.
 * `padBottom` reserve la place du bandeau de legende, sinon l'origine
 * (toujours au sud sur nos axes) se retrouve masquee derriere.
 */
function project(
  points: RouteMapPoint[],
  width: number,
  height: number,
  pad: number,
  padBottom: number,
) {
  const lats = points.map((p) => p.lat)
  const lngs = points.map((p) => p.lng)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const minLng = Math.min(...lngs)
  const maxLng = Math.max(...lngs)
  const spanLat = Math.max(maxLat - minLat, 0.01)
  const spanLng = Math.max(maxLng - minLng, 0.01)
  const usableW = width - pad * 2
  const usableH = height - pad - padBottom
  const scale = Math.min(usableW / spanLng, usableH / spanLat)
  const offsetX = (width - spanLng * scale) / 2
  const offsetY = pad + (usableH - spanLat * scale) / 2
  return points.map((point) => ({
    ...point,
    x: offsetX + (point.lng - minLng) * scale,
    // L'axe SVG descend, la latitude monte : on inverse.
    y: height - padBottom - (offsetY - pad) - (point.lat - minLat) * scale,
  }))
}

function StylisedRoute({
  points,
  className,
  vehicle,
}: {
  points: RouteMapPoint[]
  className?: string
  vehicle?: RouteMapVehicle | null
}) {
  const W = 360
  const H = 220
  const placed = points.length >= 2 ? project(points, W, H, 26, 52) : []
  // Sans fond de carte, une position brute n aurait aucun sens : on la ramene sur le trace
  // (projection geographique, lib/liveTracking), puis dans le meme cadre que les points.
  const placedVehicle =
    vehicle && points.length >= 2
      ? project([...points, { label: 'Véhicule', ...projectOntoRoute(vehicle, points), kind: 'stop' }], W, H, 26, 52)[
          points.length
        ]
      : null
  const km =
    points.length >= 2
      ? haversineKm(points[0].lat, points[0].lng, points[points.length - 1].lat, points[points.length - 1].lng)
      : 0

  return (
    <div
      className={cn(
        'relative overflow-hidden rounded-[var(--radius-card)] border border-rule bg-surface-2',
        className,
      )}
    >
      <svg
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="xMidYMid slice"
        className="size-full"
        role="img"
        aria-label={
          points.length >= 2
            ? `Tracé schématique du trajet de ${points[0].label} à ${points[points.length - 1].label}, environ ${Math.round(km)} kilomètres`
            : 'Tracé du trajet indisponible'
        }
      >
        <defs>
          <pattern id="ek-grid" width="24" height="24" patternUnits="userSpaceOnUse">
            <path d="M24 0H0v24" fill="none" stroke="var(--rule)" strokeWidth="1" />
          </pattern>
        </defs>
        <rect width={W} height={H} fill="url(#ek-grid)" />

        {placed.length >= 2 ? (
          <>
            <polyline
              points={placed.map((p) => `${p.x},${p.y}`).join(' ')}
              fill="none"
              stroke="var(--primary)"
              strokeWidth="3"
              strokeLinecap="round"
              strokeLinejoin="round"
              opacity="0.25"
            />
            <polyline
              points={placed.map((p) => `${p.x},${p.y}`).join(' ')}
              fill="none"
              stroke="var(--primary)"
              strokeWidth="3"
              strokeDasharray="6 8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            {placed.map((point, index) => (
              <g key={`${point.label}-${index}`}>
                {point.kind !== 'stop' ? (
                  <text
                    x={point.x + 10}
                    y={point.y + (point.kind === 'origin' ? 14 : -8)}
                    fill="var(--ink-2)"
                    fontSize="11"
                    fontWeight="600"
                  >
                    {point.label}
                  </text>
                ) : null}
                {point.kind === 'destination' ? (
                  <rect
                    x={point.x - 5}
                    y={point.y - 5}
                    width="10"
                    height="10"
                    rx="2"
                    fill="var(--danger)"
                    stroke="var(--surface)"
                    strokeWidth="2"
                  />
                ) : (
                  <circle
                    cx={point.x}
                    cy={point.y}
                    r={point.kind === 'origin' ? 5.5 : 4}
                    fill={point.kind === 'origin' ? 'var(--primary)' : 'var(--rule-strong)'}
                    stroke="var(--surface)"
                    strokeWidth="2"
                  />
                )}
              </g>
            ))}
            {placedVehicle ? (
              <g
                transform={`translate(${placedVehicle.x} ${placedVehicle.y})`}
                opacity={vehicle?.stale ? 0.45 : 1}
                role="img"
                aria-label={vehicle?.stale ? 'Véhicule (dernière position connue)' : 'Véhicule'}
              >
                <circle r="10" fill="var(--primary)" opacity="0.2">
                  {!vehicle?.stale ? <animate attributeName="r" values="8;14;8" dur="2s" repeatCount="indefinite" /> : null}
                </circle>
                <circle r="7" fill="var(--primary)" stroke="var(--surface)" strokeWidth="2" />
                {vehicle?.heading !== null && vehicle?.heading !== undefined ? (
                  <path d="M0 -4.5l3.5 8-3.5-2-3.5 2z" fill="var(--surface)" transform={`rotate(${vehicle.heading})`} />
                ) : null}
              </g>
            ) : null}
          </>
        ) : null}
      </svg>

      {/* Legende : la carte reelle n'etant pas disponible, on annonce l'ordre de grandeur. */}
      <div className="absolute inset-x-0 bottom-0 flex items-center gap-2 border-t border-rule bg-[color-mix(in_srgb,var(--surface)_92%,transparent)] px-3 py-2 text-caption text-muted backdrop-blur-sm">
        <MapIcon className="size-3.5 shrink-0" aria-hidden />
        <span className="tnum" title="Distance et durée estimées à vol d'oiseau">
          ≈ {Math.round(km)} km · {formatDuration(estimateDurationMinutes(km))}
        </span>
        <span className="ml-auto truncate">Tracé schématique</span>
      </div>
    </div>
  )
}
