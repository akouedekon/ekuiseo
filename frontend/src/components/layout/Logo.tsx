import { cn } from '@/lib/cn'

/**
 * Marque Ekuiseo.
 *
 * Le symbole : un carre vert Benin a coins 28 % portant un « E » a traits
 * reguliers (epaisseur 3,5/32), dont le bras central se prolonge en fleche :
 * l'initiale devient une route qui avance. Decoupe en negatif, il reste
 * lisible a 16 px (favicon) comme a 52 px (ecran de chargement).
 *
 * Le filet tricolore (vert, jaune, rouge du drapeau) souligne le mot-symbole
 * et signe l'en-tete ; il n'est jamais dans le carre.
 *
 * Trois usages :
 *  - `mark`    : symbole seul (barre basse, favicon, avatars de systeme) ;
 *  - `full`    : symbole + mot-symbole (en-tete, ecrans de connexion) ;
 *  - `stacked` : symbole au-dessus du mot, pour les ecrans systeme.
 * Le symbole garde le vert plein dans les deux themes : c'est lui qui porte
 * la reconnaissance.
 */
export const LOGO_MARK_PATH = 'M9 8h14v3.5H12.5v2.75H18v3.5h-5.5v2.75H23V24H9z M18 12.5 24.5 16 18 19.5z'

export function Logo({
  size = 32,
  variant = 'full',
  withWordmark,
  className,
}: {
  size?: number
  variant?: 'mark' | 'full' | 'stacked'
  /** Compatibilite : `withWordmark={false}` equivaut a `variant="mark"`. */
  withWordmark?: boolean
  className?: string
}) {
  const resolved = withWordmark === false ? 'mark' : variant
  const wordSize = Math.round(size * 0.62)

  const mark = (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      role="img"
      aria-label="Ekuiseo"
      className="shrink-0"
    >
      <rect width="32" height="32" rx="9" fill="var(--primary)" />
      <path d={LOGO_MARK_PATH} fill="var(--primary-contrast)" />
    </svg>
  )

  if (resolved === 'mark') return <span className={cn('inline-flex', className)}>{mark}</span>

  const word = (
    <span className="flex flex-col items-start leading-none">
      <span
        className="font-display font-extrabold tracking-[-0.045em] text-ink"
        style={{ fontSize: wordSize, lineHeight: 1 }}
      >
        Ekuiseo
      </span>
      <span aria-hidden className="banner-rule mt-1 h-[2px] w-full max-w-[64px] opacity-90" />
    </span>
  )

  if (resolved === 'stacked') {
    return (
      <span className={cn('inline-flex flex-col items-center gap-3', className)}>
        {mark}
        {word}
      </span>
    )
  }

  return (
    <span className={cn('inline-flex items-center gap-2.5', className)}>
      {mark}
      {word}
    </span>
  )
}
