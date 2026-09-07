import { Download, Share, SquarePlus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Logo } from '@/components/layout/Logo'
import { usePwaInstall } from '@/hooks/usePwaInstall'

/**
 * Bandeau d'installation (audit, section 5 #21) : discret, au-dessus de la
 * barre basse sur mobile, fermable d'un geste. Sur iOS, ou le navigateur ne
 * propose rien, il explique les deux gestes de Safari.
 */
export function PwaInstallBanner() {
  const { visible, mode, install, dismiss } = usePwaInstall()
  if (!visible) return null

  return (
    <div
      role="region"
      aria-label="Installer l'application"
      className="fixed inset-x-3 z-30 rounded-[var(--radius-card)] border border-rule bg-surface p-3 shadow-e3 md:left-auto md:right-6 md:w-[360px]"
      style={{ bottom: 'calc(72px + env(safe-area-inset-bottom, 0px))' }}
    >
      <div className="flex items-start gap-3">
        <Logo size={36} variant="mark" />
        <div className="min-w-0 flex-1">
          <p className="font-display text-body font-bold text-ink">Ajouter Ekuiseo à l'écran d'accueil</p>
          {mode === 'native' ? (
            <p className="mt-0.5 text-label text-muted">Une icône, un lancement plus rapide, et vos trajets sous la main.</p>
          ) : (
            <ol className="mt-1 space-y-1 text-label text-muted">
              <li className="flex items-center gap-1.5">
                <Share className="size-4 shrink-0 text-ink-2" aria-hidden />
                Touchez « Partager » dans Safari
              </li>
              <li className="flex items-center gap-1.5">
                <SquarePlus className="size-4 shrink-0 text-ink-2" aria-hidden />
                puis « Sur l'écran d'accueil »
              </li>
            </ol>
          )}
          {mode === 'native' ? (
            <Button size="sm" className="mt-2" onClick={() => void install()}>
              <Download className="size-4" aria-hidden />
              Installer
            </Button>
          ) : null}
        </div>
        <Button variant="ghost" size="icon" aria-label="Ne plus proposer l'installation" onClick={dismiss} className="-mr-1 -mt-1">
          <X className="size-4" aria-hidden />
        </Button>
      </div>
    </div>
  )
}
