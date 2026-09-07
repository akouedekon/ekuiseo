import { format, parseISO } from 'date-fns'
import { fr } from 'date-fns/locale'
import { ScrollText } from 'lucide-react'
import { useEffect, type JSX } from 'react'
import { Link } from 'react-router'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { CguContent } from '@/content/legal/cgu'
import { ConfidentialiteContent } from '@/content/legal/confidentialite'
import { MentionsLegalesContent } from '@/content/legal/mentions-legales'
import { cn } from '@/lib/cn'
import { CONTACT_EMAIL, LEGAL_PAGES, TERMS_VERSION, type LegalSlug } from '@/lib/legal'

const CONTENT: Record<LegalSlug, () => JSX.Element> = {
  cgu: CguContent,
  confidentialite: ConfidentialiteContent,
  'mentions-legales': MentionsLegalesContent,
}

/**
 * Styles typographiques du corps de texte légal. Pas de plugin typography :
 * les balises du contenu (h2, p, ul, table…) sont stylées depuis ce wrapper.
 */
const PROSE =
  'text-base text-ink-2 ' +
  '[&_p]:mt-3 [&_p]:leading-relaxed ' +
  '[&_h2]:mt-8 [&_h2]:font-display [&_h2]:text-title [&_h2]:font-bold [&_h2]:tracking-[-0.02em] [&_h2]:text-ink ' +
  '[&_ul]:mt-3 [&_ul]:list-disc [&_ul]:space-y-1.5 [&_ul]:pl-5 [&_li]:leading-relaxed ' +
  '[&_strong]:font-semibold [&_strong]:text-ink ' +
  '[&_a]:font-medium [&_a]:text-primary-ink [&_a]:underline [&_a]:underline-offset-2 ' +
  '[&_.table-wrap]:mt-3 [&_.table-wrap]:overflow-x-auto [&_.table-wrap]:rounded-[var(--radius-card)] [&_.table-wrap]:border [&_.table-wrap]:border-rule ' +
  '[&_table]:w-full [&_table]:min-w-[480px] [&_table]:border-collapse [&_table]:text-body ' +
  '[&_th]:bg-surface-2 [&_th]:px-3 [&_th]:py-2 [&_th]:text-left [&_th]:text-label [&_th]:font-semibold [&_th]:text-ink ' +
  '[&_td]:border-t [&_td]:border-rule [&_td]:px-3 [&_td]:py-2 [&_td]:align-top'

/** Page de texte légal : CGU, politique de confidentialité ou mentions légales. */
export function LegalPage({ slug }: { slug: LegalSlug }) {
  const page = LEGAL_PAGES.find((p) => p.slug === slug) ?? LEGAL_PAGES[0]
  const Content = CONTENT[slug]

  useEffect(() => {
    window.scrollTo({ top: 0 })
  }, [slug])

  return (
    <PageContainer width="md">
      <PageHeader title={page.title} backTo="/" />

      <div
        role="note"
        className="flex gap-3 rounded-[var(--radius-card)] border border-[var(--ocre)] bg-[var(--ocre-soft)] px-4 py-3 text-[var(--ocre-ink)]"
      >
        <ScrollText className="mt-0.5 size-5 shrink-0" aria-hidden />
        <div className="text-body leading-relaxed">
          <p className="font-semibold">
            Projet de texte : à valider par un juriste béninois avant ouverture au public.
          </p>
          <p>
            Version {TERMS_VERSION}, mise à jour le {format(parseISO(page.updatedAt), 'd MMMM yyyy', { locale: fr })}.
          </p>
        </div>
      </div>

      <nav aria-label="Textes légaux" className="mt-5 flex flex-wrap gap-2">
        {LEGAL_PAGES.map((item) => {
          const active = item.slug === slug
          return (
            <Link
              key={item.slug}
              to={item.path}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'inline-flex min-h-9 items-center rounded-[var(--radius-pill)] border px-3.5 text-label font-semibold transition-colors',
                active
                  ? 'border-transparent bg-primary text-on-primary'
                  : 'border-rule-strong bg-surface text-ink-2 hover:bg-surface-2 hover:text-ink',
              )}
            >
              {item.title}
            </Link>
          )
        })}
      </nav>

      <article className={cn('mt-6', PROSE)}>
        <Content />
      </article>

      <footer className="mt-10 border-t border-rule pt-5 text-body text-muted">
        Contact :{' '}
        <a href={`mailto:${CONTACT_EMAIL}`} className="font-medium text-primary-ink underline underline-offset-2">
          {CONTACT_EMAIL}
        </a>
      </footer>
    </PageContainer>
  )
}
