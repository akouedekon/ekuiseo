import { useEffect, useState } from 'react'
import { onlineManager } from '@tanstack/react-query'

/**
 * Etat de connectivite. On s'appuie sur onlineManager de TanStack Query pour
 * rester coherent avec le comportement des requetes (networkMode offlineFirst).
 */
export function useOnlineStatus(): boolean {
  const [online, setOnline] = useState(() => onlineManager.isOnline())
  useEffect(() => onlineManager.subscribe(setOnline), [])
  return online
}

/** Compte a rebours en secondes, arrete a zero. Utile OTP, acompte, webhook. */
export function useCountdown(deadline: number | null): number {
  const [remaining, setRemaining] = useState(() =>
    deadline ? Math.max(0, Math.floor((deadline - Date.now()) / 1000)) : 0,
  )

  /*
   * Synchronisation avec une source externe (l'horloge) : c'est exactement le
   * cas d'usage d'un effet. Le premier `tick()` remet la valeur a jour quand
   * l'echeance change, avant meme le premier battement de l'intervalle.
   */
  useEffect(() => {
    if (!deadline) {
      setRemaining(0)
      return
    }
    const tick = () => setRemaining(Math.max(0, Math.floor((deadline - Date.now()) / 1000)))
    tick()
    const id = window.setInterval(tick, 1000)
    return () => window.clearInterval(id)
  }, [deadline])

  return remaining
}

/** Horodatage de derniere mise a jour reussie, pour le bandeau « hors ligne ». */
export function useStaleAge(updatedAt: number | undefined): number | null {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(id)
  }, [])
  if (!updatedAt) return null
  return Math.max(0, Math.round((now - updatedAt) / 60_000))
}
