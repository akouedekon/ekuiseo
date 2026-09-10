import { useEffect, useState } from 'react'

/**
 * Horloge qui bat toutes les `intervalMs` (1 s par defaut) tant que `running` est vrai :
 * sert a afficher un age qui avance (« il y a 12 s ») sans redemander au serveur.
 * Arretee, elle garde sa derniere valeur et ne provoque plus de rendu.
 */
export function useNow(running = true, intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!running) return
    const id = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(id)
  }, [running, intervalMs])
  return now
}
