import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

/**
 * Barre d'action collante du bas d'ecran (fiche trajet, tunnel de reservation,
 * publication) : posee juste au-dessus de la barre de navigation basse, en tenant
 * compte de la zone sure des telephones a encoche (audit F325). La hauteur de la
 * barre basse est lue dans index.css (--bottom-nav-h, 0 au-dela de 768 px). Elle
 * disparait au-dela de `lg`, ou l'action vit dans la colonne laterale, sauf `always`
 * (tunnels a une colonne) ou elle reste jusqu a `md`. Le contenu qu'elle recouvre
 * doit reserver `stickyClearanceClass` en bas.
 */
export function StickyActionBar({
  children,
  className,
  always = false,
}: {
  children: ReactNode
  className?: string
  /** Garde la barre jusqu a 768 px (ecrans sans colonne laterale). */
  always?: boolean
}) {
  return (
    <div
      className={cn(
        'ek-glass fixed inset-x-0 z-30 border-t border-rule px-4 py-3',
        always ? 'md:hidden' : 'lg:hidden',
        className,
      )}
      style={{ bottom: 'calc(var(--bottom-nav-h) + env(safe-area-inset-bottom, 0px))' }}
    >
      <div className="mx-auto flex max-w-3xl items-center gap-2">{children}</div>
    </div>
  )
}

/**
 * Espace a reserver sous un contenu recouvert par la barre d'action : la barre basse
 * est deja reservee par la coque (AppShell), il ne reste que la barre elle-meme
 * (~76 px) et un peu d air.
 */
export const stickyClearanceClass = 'pb-24 lg:pb-10'
export const stickyClearanceAlwaysClass = 'pb-24 md:pb-10'
