import { ChevronLeft } from 'lucide-react'
import { useEffect, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { setScreenBackTo } from '@/components/layout/screenStore'
import { cn } from '@/lib/cn'

/** Colonne de contenu standard : mobile plein cadre, centree au-dela. */
export function PageContainer({
  children,
  className,
  width = 'md',
}: {
  children: ReactNode
  className?: string
  width?: 'sm' | 'md' | 'lg' | 'full'
}) {
  const max = { sm: 'max-w-lg', md: 'max-w-3xl', lg: 'max-w-[1200px]', full: 'max-w-none' }[width]
  return <div className={cn('mx-auto w-full px-4 py-5 sm:px-6 sm:py-8', max, className)}>{children}</div>
}

/**
 * En-tete d'ecran. Au-dela de 768 px : titre de l ecran avec retour explicite (cible
 * 44 px). En dessous, la barre haute de l application (AppTopBar) porte le retour et
 * le titre de l ecran : le bouton inline disparait, et le titre d un ecran racine
 * (`back={false}` : Mes trajets, Messages, Compte…) n est plus repete visuellement,
 * seuls le sous-titre et les actions restent. Sur un ecran secondaire, le titre reste
 * le contenu (axe du trajet, nom de l interlocuteur) et se garde, plus compact.
 *
 * Sur un lien direct (partage WhatsApp, favori), il n'y a pas d'ecran precedent dans
 * l'application : le retour mene alors a `backTo` (transmis a la barre haute).
 */
export function PageHeader({
  title,
  subtitle,
  back = true,
  backTo = '/',
  actions,
  className,
  mobileTitle,
}: {
  title: ReactNode
  subtitle?: ReactNode
  back?: boolean
  /** Destination de repli quand l'historique de l'application est vide. */
  backTo?: string
  actions?: ReactNode
  className?: string
  /** Afficher le titre en dessous de 768 px (par defaut : seulement sur un ecran secondaire). */
  mobileTitle?: boolean
}) {
  const navigate = useNavigate()
  const location = useLocation()
  // react-router marque la toute premiere entree de session par la cle « default ».
  const canGoBack = location.key !== 'default'
  const showTitleOnMobile = mobileTitle ?? back
  const hasExtras = Boolean(subtitle || actions)

  useEffect(() => {
    if (!back) return undefined
    setScreenBackTo(backTo)
    return () => setScreenBackTo(null)
  }, [back, backTo])

  return (
    <div
      className={cn(
        'flex items-start gap-2 md:mb-6',
        // Rien de visible sur mobile (titre porte par la barre haute, ni sous-titre ni action) : pas de marge.
        showTitleOnMobile || hasExtras ? 'mb-4' : 'mb-0',
        className,
      )}
    >
      {back ? (
        <button
          type="button"
          onClick={() => (canGoBack ? navigate(-1) : navigate(backTo, { replace: true }))}
          aria-label="Revenir à l'écran précédent"
          className="-ml-2 mt-0.5 hidden size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink md:flex"
        >
          <ChevronLeft className="size-5" aria-hidden />
        </button>
      ) : null}
      <div className="min-w-0 flex-1 md:pt-1">
        {/* Focalisable par script apres une transition d'ecran (AppShell), sans entrer dans l'ordre de tabulation. */}
        <h1
          tabIndex={-1}
          className={cn(
            'headline outline-none md:text-display lg:text-display-lg',
            showTitleOnMobile ? 'text-heading sm:text-display' : 'sr-only md:not-sr-only',
          )}
        >
          {title}
        </h1>
        {subtitle ? (
          <p className={cn('text-body text-muted', showTitleOnMobile ? 'mt-1 md:mt-1.5' : 'md:mt-1.5')}>{subtitle}</p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2 md:pt-1">{actions}</div> : null}
    </div>
  )
}

/** Titre de section a l'interieur d'une page : petit, en capitales, pour ne pas concurrencer le contenu. */
export function SectionTitle({
  children,
  action,
  className,
}: {
  children: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('mb-3 flex items-baseline justify-between gap-3', className)}>
      <h2 className="text-caption font-semibold uppercase tracking-[0.08em] text-muted">{children}</h2>
      {action}
    </div>
  )
}
