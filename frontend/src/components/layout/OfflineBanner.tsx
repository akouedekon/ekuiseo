import { AnimatePresence, m } from 'motion/react'
import { CloudOff, RefreshCw } from 'lucide-react'
import { useIsFetching } from '@tanstack/react-query'
import { useOnlineStatus } from '@/hooks/useNetwork'
import { cn } from '@/lib/cn'

/**
 * Bandeau d'etat reseau : hors ligne, on consulte ce qui a ete enregistre et
 * les envois sont refuses tant que la connexion ne revient pas - aucune file
 * d'attente n'est promise (audit F340). Annonce par aria-live, il n'occupe
 * aucune place quand il est inutile. Se place sous l'en-tete (64 px + filet de 3 px).
 */
export function StatusBanners({ className }: { className?: string }) {
  const online = useOnlineStatus()
  const fetching = useIsFetching()

  return (
    <div className={cn('sticky top-[67px] z-30', className)} aria-live="polite">
      <AnimatePresence initial={false}>
        {!online ? (
          <m.div
            key="offline"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden bg-[var(--ocre-soft)]"
          >
            <div className="mx-auto flex max-w-6xl items-center gap-2 px-4 py-2 text-[13px] font-medium text-[var(--ocre-ink)]">
              <CloudOff className="size-4 shrink-0" aria-hidden />
              <span className="min-w-0 flex-1">
                Hors ligne — vous consultez les données enregistrées. Réservations, messages et publications
                attendront le retour du réseau.
              </span>
              {fetching > 0 ? <RefreshCw className="size-4 shrink-0 animate-spin" aria-hidden /> : null}
            </div>
          </m.div>
        ) : null}
      </AnimatePresence>
    </div>
  )
}
