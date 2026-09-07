/**
 * Titre et description par ecran (audit F317). React 19 hisse nativement les
 * balises <title> et <meta> rendues dans un composant : chaque page declare
 * ainsi ce qu'elle est, pour l'onglet du navigateur, l'historique, le partage et
 * les lecteurs d'ecran (WCAG 2.4.2). Les ecrans prives (compte, reservations,
 * back-office…) portent `noindex`.
 *
 * AppShell lit le meme titre pour l'annoncer dans une zone `aria-live` a chaque
 * changement de route (voir usePageTitle / announceRouteChange).
 */
import { useEffect } from 'react'

export const SITE_NAME = 'Ekuiseo'
export const DEFAULT_DESCRIPTION =
  'Trajets interurbains et navettes quotidiennes partout au Bénin. Acompte de 1 000 FCFA en mobile money, solde en espèces à bord.'

/** Evenement maison consomme par AppShell pour l'annonce vocale du titre. */
export const PAGE_TITLE_EVENT = 'ekuiseo:page-title'

export function fullTitle(title?: string): string {
  return title ? `${title} | ${SITE_NAME}` : `${SITE_NAME} — Covoiturage au Bénin`
}

export function PageMeta({
  title,
  description = DEFAULT_DESCRIPTION,
  noindex = false,
}: {
  /** Titre court de l'ecran, sans le nom du site (ajoute ici). */
  title?: string
  description?: string
  /** Ecran prive : jamais indexe. */
  noindex?: boolean
}) {
  const computed = fullTitle(title)
  useEffect(() => {
    window.dispatchEvent(new CustomEvent(PAGE_TITLE_EVENT, { detail: computed }))
  }, [computed])
  return (
    <>
      <title>{computed}</title>
      <meta name="description" content={description} />
      {noindex ? <meta name="robots" content="noindex, nofollow" /> : null}
    </>
  )
}
