import { useReducedMotion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { interpolatePosition, type AnimatedPoint } from '@/lib/liveTracking'

/**
 * Position affichee d un marqueur qui glisse vers sa cible (V28) : a chaque nouvelle
 * position recue, le marqueur part de la ou il est et rejoint la cible en `durationMs`
 * par interpolation lineaire (requestAnimationFrame), sans jamais sauter. Sous
 * prefers-reduced-motion, la cible est appliquee directement. Une cible nulle efface
 * le marqueur.
 */
export function useAnimatedPosition(target: AnimatedPoint | null, durationMs: number): AnimatedPoint | null {
  const reduce = useReducedMotion()
  const [displayed, setDisplayed] = useState<AnimatedPoint | null>(target)
  const displayedRef = useRef<AnimatedPoint | null>(target)
  const lat = target?.lat
  const lng = target?.lng
  const heading = target?.heading ?? null

  useEffect(() => {
    const to: AnimatedPoint | null = lat === undefined || lng === undefined ? null : { lat, lng, heading }
    const from = displayedRef.current
    if (!to || reduce || !from || typeof requestAnimationFrame !== 'function') {
      // Application directe, a la prochaine image : jamais de setState synchrone dans l effet.
      displayedRef.current = to
      const immediate = typeof requestAnimationFrame === 'function' ? requestAnimationFrame(() => setDisplayed(to)) : null
      const fallback = immediate === null ? setTimeout(() => setDisplayed(to), 0) : null
      return () => {
        if (immediate !== null) cancelAnimationFrame(immediate)
        if (fallback !== null) clearTimeout(fallback)
      }
    }
    const start = performance.now()
    let frame = requestAnimationFrame(function tick(now) {
      const t = (now - start) / durationMs
      const next = interpolatePosition(from, to, t)
      displayedRef.current = next
      setDisplayed(next)
      if (t < 1) frame = requestAnimationFrame(tick)
    })
    return () => cancelAnimationFrame(frame)
  }, [lat, lng, heading, durationMs, reduce])

  return displayed
}
