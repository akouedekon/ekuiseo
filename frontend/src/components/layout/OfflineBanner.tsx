import { AnimatePresence, motion } from 'motion/react'
import { CloudOff, RefreshCw } from 'lucide-react'
import { useIsFetching, useIsMutating } from '@tanstack/react-query'
import { useOnlineStatus } from '@/hooks/useNetwork'
import { cn } from '@/lib/cn'

/**
 * Bandeau d'etat reseau : hors ligne, avec le nombre d'ecritures en attente
 * de synchronisation. Annonce par aria-live, il n'occupe aucune place quand
 * il est inutile. Se place sous l'en-tete (64 px + filet de 3 px).
 */
export function StatusBanners({ className }: { className?: string }) {
  const online = useOnlineStatus()
  const pendingMutations = useIsMutating()
  const fetching = useIsFetching()

  return (
    <div className={cn('sticky top-[67px] z-30', className)} aria-live="polite">
      <AnimatePresence initial={false}>
        {!online ? (
          <motion.div
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
                Hors ligne — vous consultez les données enregistrées.
                {pendingMutations > 0 ? ` ${pendingMutations} action(s) en attente d'envoi.` : ''}
              </span>
              {fetching > 0 ? <RefreshCw className="size-4 shrink-0 animate-spin" aria-hidden /> : null}
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </div>
  )
}
