import { cn } from '@/lib/cn'

/**
 * Marque : un carre indigo portant un « E » decoupe, souligne d'un filet
 * tricolore — reprise des bandes appliquees des bannieres d'Abomey.
 */
export function Logo({ size = 32, withWordmark = true, className }: { size?: number; withWordmark?: boolean; className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-2', className)}>
      <svg
        width={size}
        height={size}
        viewBox="0 0 32 32"
        role="img"
        aria-label="Ekuiseo"
        className="shrink-0"
      >
        <rect width="32" height="32" rx="8" fill="var(--indigo)" />
        <path d="M10 8h13v3.6h-9v3.1h8.1v3.5H14v3.2h9.2V25H10z" fill="var(--indigo-contrast)" />
        <rect x="10" y="26.4" width="4.4" height="1.8" fill="var(--ocre)" />
        <rect x="15.2" y="26.4" width="4.4" height="1.8" fill="var(--vermillon)" />
        <rect x="20.4" y="26.4" width="2.8" height="1.8" fill="var(--vert)" />
      </svg>
      {withWordmark ? (
        <span className="font-display text-[19px] font-extrabold tracking-[-0.04em] text-ink">Ekuiseo</span>
      ) : null}
    </span>
  )
}
