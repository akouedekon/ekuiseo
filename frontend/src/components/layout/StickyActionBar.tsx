import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

/** Hauteur de la barre de navigation basse (mobile), hors zone sure. */
export const BOTTOM_NAV_HEIGHT_PX = 60

/**
 * Barre d'action collante du bas d'ecran (fiche trajet, tunnel) : posee juste
 * au-dessus de la barre de navigation basse, en tenant compte de la zone sure
 * des telephones a encoche (audit F325). Elle disparait au-dela de `lg`, ou
 * l'action vit dans la colonne laterale. Le contenu qu'elle recouvre doit
 * reserver `STICKY_BAR_CLEARANCE` en bas (voir `stickyClearanceClass`).
 */
export function StickyActionBar({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn('ek-glass fixed inset-x-0 z-30 border-t border-rule px-4 py-3 lg:hidden', className)}
      style={{ bottom: `calc(${BOTTOM_NAV_HEIGHT_PX}px + env(safe-area-inset-bottom, 0px))` }}
    >
      <div className="mx-auto flex max-w-3xl items-center gap-2">{children}</div>
    </div>
  )
}

/**
 * Espace a reserver sous un contenu recouvert par la barre d'action ET la
 * navigation basse : barre (~76 px) + navigation (60 px) + zone sure.
 */
export const stickyClearanceClass = 'pb-[calc(140px+env(safe-area-inset-bottom,0px))] lg:pb-10'
