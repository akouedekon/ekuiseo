import { ScrollText } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/misc'
import { PageContainer } from '@/components/layout/PageContainer'
import { useAcceptTerms, useLogout } from '@/hooks/useAuth'
import { describeError } from '@/lib/errors'
import { LEGAL_PAGES, TERMS_VERSION } from '@/lib/legal'

/**
 * Ecran bloquant d'acceptation des CGU (audit F509) : affiche a la place de
 * l'application quand GET /me indique que la version acceptee est anterieure a
 * la version courante. Les textes restent consultables (liens) ; refuser, c'est
 * se deconnecter.
 */
export function TermsGate() {
  const [checked, setChecked] = useState(false)
  const accept = useAcceptTerms()
  const logout = useLogout()
  const cgu = LEGAL_PAGES.find((p) => p.slug === 'cgu')
  const privacy = LEGAL_PAGES.find((p) => p.slug === 'confidentialite')

  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-10rem)] flex-col justify-center">
      <Card className="p-5 sm:p-6">
        <span className="flex size-12 items-center justify-center rounded-[var(--radius-card)] bg-primary-soft text-primary-ink">
          <ScrollText className="size-6" aria-hidden />
        </span>
        <h1 className="headline mt-4 text-[24px]">Nos conditions ont changé</h1>
        <p className="mt-2 text-body leading-relaxed text-ink-2">
          Pour continuer à utiliser Ekuiseo, prenez connaissance de la version {TERMS_VERSION} des conditions
          générales d'utilisation et de la politique de confidentialité, puis acceptez-les.
        </p>
        <ul className="mt-4 space-y-1.5 text-body">
          {[cgu, privacy].map((page) =>
            page ? (
              <li key={page.slug}>
                <Link
                  to={page.path}
                  target="_blank"
                  rel="noopener"
                  className="font-medium text-primary-ink underline underline-offset-2"
                >
                  {page.title}
                </Link>
              </li>
            ) : null,
          )}
        </ul>

        <label className="mt-5 flex cursor-pointer items-start gap-3 rounded-[var(--radius-control)] border border-rule px-3 py-3">
          <Checkbox checked={checked} onCheckedChange={(value) => setChecked(value === true)} className="mt-0.5" />
          <span className="text-body leading-snug">
            J'ai lu et j'accepte les conditions générales d'utilisation et la politique de confidentialité.
          </span>
        </label>

        <Button
          size="lg"
          block
          className="mt-4"
          disabled={!checked}
          loading={accept.isPending}
          onClick={() =>
            accept.mutate(undefined, {
              onSuccess: () => toast.success('Merci, vos conditions sont à jour'),
              onError: (error) => toast.error(describeError(error, "L'acceptation n'a pas pu être enregistrée.")),
            })
          }
        >
          Accepter et continuer
        </Button>
        <Button variant="ghost" block className="mt-2 text-muted" onClick={logout}>
          Refuser et me déconnecter
        </Button>
      </Card>
    </PageContainer>
  )
}
