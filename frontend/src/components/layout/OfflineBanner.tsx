import { AnimatePresence, motion } from 'motion/react'
import { CloudOff, RefreshCw, TriangleAlert } from 'lucide-react'
import { useSyncExternalStore } from 'react'
import { useIsFetching, useIsMutating } from '@tanstack/react-query'
import { useOnlineStatus } from '@/hooks/useNetwork'
import { DEMO_FALLBACK_ENABLED, isDemoActive, subscribeDemoActive } from '@/api/resilient'
import { cn } from '@/lib/cn'

/**
 * S'abonne au signal global du mode demonstration : des qu'un appel est
 * retombe sur des donnees factices, tous les ecrans l'annoncent.
 */
function useDemoActive(): boolean {
  return useSyncExternalStore(subscribeDemoActive, isDemoActive, () => false)
}

/**
 * Bandeau « données de démonstration ».
 *
 * Volontairement voyant — vermillon, majuscules, hachures — parce qu'il
 * signale que RIEN de ce qui est affiche n'est reel. C'est la seule entorse
 * assumee a la sobriete de la charte : un avertissement discret serait un
 * defaut, pas une qualite.
 */
export function DemoBanner() {
  const demo = useDemoActive()
  if (!DEMO_FALLBACK_ENABLED || !demo) return null

  return (
    <div
      role="alert"
      className="border-y-2 border-[var(--vermillon)] bg-[var(--vermillon-soft)]"
    >
      <div className="mx-auto flex max-w-6xl items-center gap-2.5 px-4 py-2">
        <TriangleAlert className="size-[18px] shrink-0 text-[var(--vermillon)]" aria-hidden />
        <p className="min-w-0 flex-1 text-[13px] leading-snug text-[var(--vermillon)]">
          <span className="font-extrabold uppercase tracking-[0.06em]">Données de démonstration</span>
          <span className="mx-1.5" aria-hidden>
            ·
          </span>
          <span className="font-medium">
            L'API est injoignable. Trajets, prix, réservations et statistiques affichés sont fictifs — ne vous y
            fiez pas.
          </span>
        </p>
      </div>
      {/* Hachures : marque visuelle non textuelle, reconnaissable au coup d'oeil. */}
      <div
        aria-hidden
        className="h-1.5"
        style={{
          backgroundImage:
            'repeating-linear-gradient(135deg, var(--vermillon) 0 8px, transparent 8px 16px)',
        }}
      />
    </div>
  )
}

/**
 * Bandeaux d'etat systeme : hors ligne (avec le nombre d'ecritures en attente
 * de synchronisation) puis mode demonstration. Annonces par aria-live, ils
 * n'occupent aucune place quand ils sont inutiles.
 */
export function StatusBanners({ className }: { className?: string }) {
  const online = useOnlineStatus()
  const pendingMutations = useIsMutating()
  const fetching = useIsFetching()

  return (
    <div className={cn('sticky top-[57px] z-30', className)} aria-live="polite">
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

      <DemoBanner />
    </div>
  )
}
