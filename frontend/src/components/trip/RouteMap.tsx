import { useEffect, useRef, useState } from 'react'
import { Map as MapIcon } from 'lucide-react'
import { cn } from '@/lib/cn'
import { estimateDurationMinutes, haversineKm } from '@/lib/cities'
import { formatDuration } from '@/lib/format'

export interface RouteMapPoint {
  label: string
  lat: number
  lng: number
  kind: 'origin' | 'stop' | 'destination'
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
}: {
  points: RouteMapPoint[]
  className?: string
  interactive?: boolean
}) {
  if (!MAP_STYLE_URL || points.length < 2) {
    return <StylisedRoute points={points} className={className} />
  }
  return <LiveMap points={points} className={className} interactive={interactive} />
}

/* ------------------------------------------------------------- Vraie carte */

function LiveMap({
  points,
  className,
  interactive,
}: {
  points: RouteMapPoint[]
  className?: string
  interactive: boolean
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let disposed = false
    let map: { remove: () => void } | null = null

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
            paint: { 'line-color': '#2E3FA8', 'line-width': 4 },
          })
        })

        for (const point of points) {
          const el = document.createElement('span')
          el.setAttribute('aria-label', point.label)
          el.style.cssText = `width:${point.kind === 'stop' ? 10 : 14}px;height:${point.kind === 'stop' ? 10 : 14}px;border-radius:${point.kind === 'destination' ? '3px' : '50%'};background:${point.kind === 'destination' ? 'var(--vermillon)' : point.kind === 'origin' ? 'var(--indigo)' : 'var(--rule-strong)'};border:2px solid var(--surface);box-shadow:0 1px 3px rgba(0,0,0,.35)`
          new Marker({ element: el }).setLngLat([point.lng, point.lat]).addTo(instance)
        }

        const bounds = points.reduce(
          (acc, point) => acc.extend([point.lng, point.lat] as [number, number]),
          new LngLatBounds([points[0].lng, points[0].lat], [points[0].lng, points[0].lat]),
        )
        instance.fitBounds(bounds, { padding: 48, duration: 0 })
      } catch {
        if (!disposed) setFailed(true)
      }
    })()

    return () => {
      disposed = true
      map?.remove()
    }
  }, [points, interactive])

  if (failed) return <StylisedRoute points={points} className={className} />

  return (
    <div
      ref={containerRef}
      role="img"
      aria-label={`Carte du trajet de ${points[0]?.label} à ${points[points.length - 1]?.label}`}
      className={cn('overflow-hidden rounded-[var(--radius-card)] border border-rule bg-[var(--surface-calm)]', className)}
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

function StylisedRoute({ points, className }: { points: RouteMapPoint[]; className?: string }) {
  const W = 360
  const H = 220
  const placed = points.length >= 2 ? project(points, W, H, 26, 52) : []
  const km =
    points.length >= 2
      ? haversineKm(points[0].lat, points[0].lng, points[points.length - 1].lat, points[points.length - 1].lng)
      : 0

  return (
    <div
      className={cn(
        'relative overflow-hidden rounded-[var(--radius-card)] border border-rule bg-[var(--surface-calm)]',
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
              stroke="var(--indigo)"
              strokeWidth="3"
              strokeLinecap="round"
              strokeLinejoin="round"
              opacity="0.25"
            />
            <polyline
              points={placed.map((p) => `${p.x},${p.y}`).join(' ')}
              fill="none"
              stroke="var(--indigo)"
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
                    fill="var(--vermillon)"
                    stroke="var(--surface)"
                    strokeWidth="2"
                  />
                ) : (
                  <circle
                    cx={point.x}
                    cy={point.y}
                    r={point.kind === 'origin' ? 5.5 : 4}
                    fill={point.kind === 'origin' ? 'var(--indigo)' : 'var(--rule-strong)'}
                    stroke="var(--surface)"
                    strokeWidth="2"
                  />
                )}
              </g>
            ))}
          </>
        ) : null}
      </svg>

      {/* Legende : la carte reelle n'etant pas disponible, on annonce l'ordre de grandeur. */}
      <div className="absolute inset-x-0 bottom-0 flex items-center gap-2 border-t border-rule bg-[color-mix(in_srgb,var(--surface)_92%,transparent)] px-3 py-2 text-[12px] text-muted backdrop-blur-sm">
        <MapIcon className="size-3.5 shrink-0" aria-hidden />
        <span className="tnum">
          ≈ {Math.round(km)} km · {formatDuration(estimateDurationMinutes(km))}
        </span>
        <span className="ml-auto truncate">Tracé schématique</span>
      </div>
    </div>
  )
}
