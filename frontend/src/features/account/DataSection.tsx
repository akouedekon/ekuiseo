import { Download, FileJson, Mail, ScrollText } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ApiError, downloadFile } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { SectionTitle } from '@/components/layout/PageContainer'
import { describeError } from '@/lib/errors'
import { CONTACT_EMAIL, LEGAL_PAGES } from '@/lib/legal'

/**
 * « Vos données » (audits F508, F510) : export au format JSON (droit d'acces),
 * renvoi vers la suppression du compte (droit a l'effacement, section suivante)
 * et adresse de contact. L'export est limite a un par 24 h par le serveur (429).
 */
export function DataSection() {
  const [exporting, setExporting] = useState(false)
  const privacy = LEGAL_PAGES.find((p) => p.slug === 'confidentialite')

  const exportData = async () => {
    setExporting(true)
    try {
      const stamp = new Date().toISOString().slice(0, 10)
      await downloadFile('/api/v1/me/export', `ekuiseo-mes-donnees-${stamp}.json`)
      toast.success('Export téléchargé', { description: 'Un fichier JSON contenant toutes vos données.' })
    } catch (error) {
      if (error instanceof ApiError && error.status === 429) {
        toast.error('Un export a déjà été fait récemment', {
          description: 'Un seul export par tranche de 24 heures. Réessayez plus tard.',
        })
      } else {
        toast.error(describeError(error, "L'export n'a pas pu être généré. Réessayez."))
      }
    } finally {
      setExporting(false)
    }
  }

  return (
    <section aria-labelledby="data-title">
      <SectionTitle className="mt-5">
        <span id="data-title">Vos données</span>
      </SectionTitle>
      <Card className="divide-y divide-rule">
        <div className="flex flex-wrap items-center gap-3 px-4 py-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-primary-soft text-primary-ink">
            <FileJson className="size-5" aria-hidden />
          </span>
          <div className="min-w-0 flex-1 basis-48">
            <p className="text-body font-medium text-ink">Télécharger mes données</p>
            <p className="text-caption text-muted">
              Profil, véhicules, trajets, réservations, paiements, avis, messages envoyés et alertes, au format JSON.
              Un export par 24 h.
            </p>
          </div>
          <Button variant="secondary" size="sm" loading={exporting} onClick={() => void exportData()}>
            <Download className="size-4" aria-hidden />
            Exporter
          </Button>
        </div>
        <div className="flex flex-wrap items-center gap-3 px-4 py-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-ink-2">
            <ScrollText className="size-5" aria-hidden />
          </span>
          <div className="min-w-0 flex-1 basis-48">
            <p className="text-body font-medium text-ink">Ce que nous conservons, et pourquoi</p>
            <p className="text-caption text-muted">Durées de conservation, sous-traitants et vos droits, dans la politique de confidentialité.</p>
          </div>
          <Button asChild variant="ghost" size="sm">
            <Link to={privacy?.path ?? '/confidentialite'}>Lire</Link>
          </Button>
        </div>
        <div className="flex flex-wrap items-center gap-3 px-4 py-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-ink-2">
            <Mail className="size-5" aria-hidden />
          </span>
          <div className="min-w-0 flex-1 basis-48">
            <p className="text-body font-medium text-ink">Une question, une contestation, une rectification ?</p>
            <p className="text-caption text-muted">Suppression du compte : voir la section ci-dessous, effet immédiat après confirmation.</p>
          </div>
          <Button asChild variant="ghost" size="sm">
            <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
          </Button>
        </div>
      </Card>
    </section>
  )
}
