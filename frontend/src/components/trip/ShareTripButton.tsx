import { Check, Share2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button, type ButtonProps } from '@/components/ui/button'

const COPIED_FEEDBACK_MS = 2000

/**
 * Partage du lien d'un trajet : la feuille de partage native quand elle
 * existe (WhatsApp est le canal reel de distribution au Benin), sinon copie
 * dans le presse-papiers avec retour visuel. Un lien partage ouvre l'ecran
 * du trajet directement, sans installation.
 */
export function ShareTripButton({
  title,
  text,
  path,
  variant = 'secondary',
  size = 'sm',
  className,
  iconOnly = false,
}: {
  title: string
  text: string
  /** Chemin relatif du trajet (ex. /trips/xxx) ; l'origine est celle de la page. */
  path: string
  variant?: ButtonProps['variant']
  size?: ButtonProps['size']
  className?: string
  /** Icone seule (barre d'action mobile) ; le libelle reste accessible. */
  iconOnly?: boolean
}) {
  const [copied, setCopied] = useState(false)

  const share = async () => {
    const url = `${window.location.origin}${path}`
    try {
      if (typeof navigator.share === 'function') {
        await navigator.share({ title, text, url })
        return
      }
      await navigator.clipboard.writeText(url)
      setCopied(true)
      toast.success('Lien copié', { description: 'Collez-le dans votre groupe WhatsApp.' })
      window.setTimeout(() => setCopied(false), COPIED_FEEDBACK_MS)
    } catch (error) {
      // L'utilisateur a ferme la feuille de partage : ce n'est pas une erreur.
      if (error instanceof DOMException && error.name === 'AbortError') return
      toast.error("Le lien n'a pas pu être partagé.")
    }
  }

  const Icon = copied ? Check : Share2
  const label = copied ? 'Lien copié' : 'Partager'

  return (
    <Button
      type="button"
      variant={variant}
      size={iconOnly ? (size === 'sm' ? 'iconSm' : 'icon') : size}
      className={className}
      onClick={share}
      aria-label={iconOnly ? `${label} ce trajet` : undefined}
    >
      <Icon className="size-4" aria-hidden />
      {iconOnly ? null : label}
    </Button>
  )
}
