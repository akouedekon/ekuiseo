import { lazy, type ComponentType, type ReactElement } from 'react'

/*
 * Ecrans charges a la demande, avec un chemin synchrone une fois le module connu.
 *
 * React.lazy suspend a CHAQUE premier rendu d un ecran, meme quand le chunk est deja en
 * cache : le temps d une microtache, le contenu est retire au profit du repli Suspense.
 * Quand cet ecran entre dans un conteneur anime (AppShell : AnimatePresence, opacite 0
 * puis 1), cette suspension peut laisser le conteneur fige a opacite 0 sur Safari iOS :
 * l ecran est monte, ses requetes partent, et l utilisateur voit une page blanche.
 *
 * Ici, un ecran deja resolu (visite une fois, ou precharge) se rend de facon synchrone,
 * sans passer par React.lazy : plus aucune suspension dans le conteneur anime. Les
 * prechargements se font par groupe (« admin » des l entree dans le back-office, le
 * reste apres le premier rendu, quand le navigateur est inactif).
 */

export type PageGroup = 'public' | 'authed' | 'admin'

export interface LazyPage<P extends object = object> {
  (props: P): ReactElement
  /** Charge le module et memorise le composant ; idempotent, ne leve jamais. */
  preload: () => Promise<void>
  /** Vrai des que le composant peut se rendre sans suspendre. */
  isReady: () => boolean
}

const REGISTRY: Record<PageGroup, LazyPage[]> = { public: [], authed: [], admin: [] }

export function createLazyPage<K extends string, P extends object>(
  loader: () => Promise<Record<K, ComponentType<P>>>,
  name: K,
  group: PageGroup,
): LazyPage<P> {
  let Resolved: ComponentType<P> | null = null
  let loading: Promise<void> | null = null

  const load = (): Promise<void> => {
    if (Resolved) return Promise.resolve()
    if (!loading) {
      loading = loader()
        .then((module) => {
          Resolved = module[name]
        })
        .catch(() => {
          // Echec reseau : on retentera au prochain rendu (React.lazy relance le chargeur).
          loading = null
        })
    }
    return loading
  }

  const Lazy = lazy<ComponentType<P>>(() =>
    loader().then((module) => {
      Resolved = module[name]
      return { default: module[name] }
    }),
  )

  const Page = ((props: P) => (Resolved ? <Resolved {...props} /> : <Lazy {...props} />)) as LazyPage<P>
  Page.preload = load
  Page.isReady = () => Resolved !== null
  REGISTRY[group].push(Page as unknown as LazyPage)
  return Page
}

/** Precharge tous les ecrans d un groupe en arriere-plan ; jamais bloquant, jamais en echec. */
export function preloadPages(group: PageGroup): Promise<void> {
  return Promise.all(REGISTRY[group].map((page) => page.preload())).then(() => undefined)
}

/** Precharge quand le navigateur est inactif (ou apres un court delai s il ne sait pas le dire). */
export function preloadPagesWhenIdle(group: PageGroup): void {
  const run = () => void preloadPages(group)
  if (typeof window === 'undefined') return
  if (typeof window.requestIdleCallback === 'function') {
    window.requestIdleCallback(run, { timeout: 4_000 })
  } else {
    window.setTimeout(run, 1_500)
  }
}

/** Reserve aux tests : oublie les ecrans enregistres. */
export function resetPageRegistry(): void {
  REGISTRY.public = []
  REGISTRY.authed = []
  REGISTRY.admin = []
}
