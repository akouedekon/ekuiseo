import { useSyncExternalStore } from 'react'

/**
 * Etat reactif d'une media query. Sert a ne MONTER un composant lourd (carte
 * MapLibre, 1 Mo) que lorsqu'il est reellement visible, au lieu de le cacher en
 * CSS une fois charge (audit F345). Rendu serveur / test : `false`.
 */
function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return () => undefined
      const list = window.matchMedia(query)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    () => (typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : false),
    () => false,
  )
}

/** Deux colonnes (liste + carte) : le point de rupture `lg` de Tailwind. */
export function useIsDesktop(): boolean {
  return useMediaQuery('(min-width: 1024px)')
}

/**
 * Coque « application » (barre haute + barre basse) en dessous de `md`, en-tete web au-dela :
 * le meme seuil que `md:hidden` sur la barre basse, pour qu une seule logique decide.
 */
export function useIsCompactShell(): boolean {
  return !useMediaQuery('(min-width: 768px)')
}
