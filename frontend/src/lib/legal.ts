/**
 * Textes légaux : version en vigueur et index des trois pages (CGU,
 * confidentialité, mentions légales). La version est celle qu'un
 * utilisateur accepte à l'inscription ; toute modification de fond d'un
 * texte doit l'incrémenter.
 */
export const TERMS_VERSION = '2026-09'

export const CONTACT_EMAIL = 'contact@ekuiseo.com'

export type LegalSlug = 'cgu' | 'confidentialite' | 'mentions-legales'

export interface LegalPageMeta {
  slug: LegalSlug
  path: `/${string}`
  title: string
  /** Date de dernière mise à jour du texte, au format ISO (AAAA-MM-JJ). */
  updatedAt: string
}

export const LEGAL_PAGES: readonly LegalPageMeta[] = [
  { slug: 'cgu', path: '/cgu', title: "Conditions générales d'utilisation", updatedAt: '2026-09-05' },
  { slug: 'confidentialite', path: '/confidentialite', title: 'Politique de confidentialité', updatedAt: '2026-09-05' },
  { slug: 'mentions-legales', path: '/mentions-legales', title: 'Mentions légales', updatedAt: '2026-09-05' },
]
