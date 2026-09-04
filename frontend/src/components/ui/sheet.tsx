import * as DialogPrimitive from '@radix-ui/react-dialog'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { springSoft } from '@/lib/motion'

interface SheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: ReactNode
  description?: ReactNode
  children: ReactNode
  /** Barre d'action collee en bas de la feuille (bouton principal). */
  footer?: ReactNode
  className?: string
}

/**
 * Feuille de bas d'ecran (bottom sheet) : geste naturel sur mobile, boite
 * de dialogue centree au-dela de 640 px. Radix fournit le piegeage du focus,
 * le retour au declencheur et la fermeture par Echap.
 */
export function Sheet({ open, onOpenChange, title, description, children, footer, className }: SheetProps) {
  const reduce = useReducedMotion()

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <AnimatePresence>
        {open ? (
          <DialogPrimitive.Portal forceMount>
            <DialogPrimitive.Overlay asChild forceMount>
              <motion.div
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.18 }}
                className="fixed inset-0 z-50 bg-[rgb(21_26_33/0.55)] backdrop-blur-[2px]"
              />
            </DialogPrimitive.Overlay>

            <DialogPrimitive.Content asChild forceMount>
              <motion.div
                initial={reduce ? { opacity: 0 } : { y: '100%' }}
                animate={reduce ? { opacity: 1 } : { y: 0 }}
                exit={reduce ? { opacity: 0 } : { y: '100%' }}
                transition={reduce ? { duration: 0.15 } : springSoft}
                className={cn(
                  'fixed inset-x-0 bottom-0 z-50 flex max-h-[92dvh] flex-col rounded-t-[20px] border border-rule bg-surface shadow-sheet focus:outline-none',
                  'sm:inset-x-auto sm:left-1/2 sm:bottom-auto sm:top-1/2 sm:w-[520px] sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-[var(--radius-panel)]',
                  className,
                )}
              >
                {/* Poignee visuelle : signale l'affordance de glissement sur mobile. */}
                <div aria-hidden className="mx-auto mt-2.5 h-1 w-10 shrink-0 rounded-full bg-rule-strong sm:hidden" />

                <header className="flex shrink-0 items-start gap-3 px-5 pb-3 pt-4">
                  <div className="min-w-0 flex-1">
                    <DialogPrimitive.Title className="font-display text-lg font-bold tracking-[-0.02em]">
                      {title}
                    </DialogPrimitive.Title>
                    {description ? (
                      <DialogPrimitive.Description className="mt-0.5 text-[13px] text-muted">
                        {description}
                      </DialogPrimitive.Description>
                    ) : (
                      <DialogPrimitive.Description className="sr-only">
                        Panneau {typeof title === 'string' ? title : ''}
                      </DialogPrimitive.Description>
                    )}
                  </div>
                  <DialogPrimitive.Close
                    aria-label="Fermer"
                    className="-mr-1 -mt-1 flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] text-muted transition-colors hover:bg-[var(--surface-calm)] hover:text-ink"
                  >
                    <X className="size-5" aria-hidden />
                  </DialogPrimitive.Close>
                </header>

                <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-5 pb-4">{children}</div>

                {footer ? (
                  <div className="safe-bottom shrink-0 border-t border-rule bg-surface px-5 py-3">{footer}</div>
                ) : (
                  <div className="safe-bottom shrink-0" />
                )}
              </motion.div>
            </DialogPrimitive.Content>
          </DialogPrimitive.Portal>
        ) : null}
      </AnimatePresence>
    </DialogPrimitive.Root>
  )
}
