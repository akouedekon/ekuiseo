import { useCallback, useEffect, useState } from 'react'
import { applyTheme, readStoredTheme, storeTheme, type ThemeMode } from '@/lib/theme'

/** Theme clair/sombre/systeme, applique immediatement et memorise. */
export function useTheme() {
  const [mode, setMode] = useState<ThemeMode>(() => readStoredTheme())

  useEffect(() => {
    applyTheme(mode)
    if (mode !== 'system') return
    // En mode « systeme », on suit les changements de preference en direct.
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => applyTheme('system')
    media.addEventListener('change', handler)
    return () => media.removeEventListener('change', handler)
  }, [mode])

  const setTheme = useCallback((next: ThemeMode) => {
    setMode(next)
    storeTheme(next)
  }, [])

  return { mode, setTheme }
}
