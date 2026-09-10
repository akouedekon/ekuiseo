import { expect, test, type Page } from '@playwright/test'
import { fakeNativeBridge, mockApi } from './fixtures'

/*
 * Captures d ecran des ecrans principaux, au format telephone, contre le serveur Vite local
 * et une API simulee (fixtures.ts) : aucun backend, aucun Docker. Trois projets (voir
 * playwright.visual.config.ts) : « mobile » (navigateur Android), « app » (pont Capacitor
 * simule : classe app-native, pied de page et bandeau d installation masques) et
 * « desktop » (1366 px, pour verifier que le web n a pas bouge).
 *
 *   cd e2e && npm run visual            -> e2e/visual/output/<projet>/<ecran>.png
 *                                          (pleine page, plus <ecran>.viewport.png : ce que
 *                                          l on voit a l ouverture, barres fixes en place)
 *
 * Le test n affirme presque rien : il sert a REGARDER. Il echoue seulement si un ecran ne
 * rend pas son titre (chunk casse, erreur de rendu) ou si une erreur JavaScript survient.
 */
interface Screen {
  name: string
  path: string
  heading: RegExp | string
  authed?: boolean
  settle?: number
  /** Projets concernes (tous par defaut). */
  projects?: string[]
  /** Interaction avant la capture (ouvrir une feuille, changer d onglet…). */
  prepare?: (page: Page) => Promise<void>
}

const SCREENS: Screen[] = [
  { name: '01-accueil', path: '/', heading: /Partagez la route|Où allez-vous/ },
  { name: '02-resultats', path: '/search?from=Cotonou&to=Bohicon&fromLat=6.3703&fromLng=2.3912&toLat=7.1786&toLng=2.0667&date=2026-09-11&seats=1', heading: /Cotonou/ },
  {
    name: '02b-resultats-filtres',
    path: '/search?from=Cotonou&to=Bohicon&fromLat=6.3703&fromLng=2.3912&toLat=7.1786&toLng=2.0667&date=2026-09-11&seats=1',
    heading: /Cotonou/,
    projects: ['mobile', 'app'],
    prepare: async (page) => {
      await page.getByRole('button', { name: 'Filtres' }).click()
      await expect(page.getByRole('dialog', { name: 'Filtrer les trajets' })).toBeVisible()
    },
  },
  { name: '03-trajet', path: '/trips/t-0', heading: /Cotonou/ },
  { name: '04-reservation', path: '/book/t-0', heading: /R.serv|Votre place|Confirmer/ },
  { name: '05-mes-trajets', path: '/bookings', heading: /Mes trajets/ },
  { name: '05b-mes-trajets-passes', path: '/bookings?tab=past', heading: /Mes trajets/, projects: ['mobile', 'app'] },
  { name: '06-messages', path: '/messages', heading: /Messages/ },
  { name: '07-conversation', path: '/bookings/b-1/messages', heading: /Marcellin/ },
  { name: '08-compte', path: '/me', heading: /Awa|Mon compte|Compte/ },
  { name: '08b-compte-reglages', path: '/me?tab=preferences', heading: /Awa|Mon compte|Compte/, projects: ['mobile', 'app'] },
  { name: '09-publier', path: '/publish', heading: /Publier un trajet/ },
  { name: '10-notifications', path: '/notifications', heading: /Notifications/ },
  { name: '11-profil-conducteur', path: '/drivers/d-1', heading: /Marcellin/ },
  { name: '12-connexion', path: '/login', heading: /Bienvenue/, authed: false },
  { name: '13-autour', path: '/autour', heading: /Autour|autour/ },
]

for (const screen of SCREENS) {
  test(`capture ${screen.name}`, async ({ page }, testInfo) => {
    test.skip(screen.projects !== undefined && !screen.projects.includes(testInfo.project.name), 'ecran hors de ce projet')
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    if (testInfo.project.name === 'app') await fakeNativeBridge(page)
    await mockApi(page, { authed: screen.authed ?? true })
    await page.goto(screen.path)
    await expect(page.getByRole('heading', { name: screen.heading }).first()).toBeVisible({ timeout: 30_000 })
    if (screen.prepare) await screen.prepare(page)
    await page.waitForTimeout(screen.settle ?? 700)
    const base = `visual/output/${testInfo.project.name}/${screen.name}`
    // La vue a l ouverture d abord (barres fixes en place), puis la page entiere.
    await page.screenshot({ path: `${base}.viewport.png`, fullPage: false })
    await page.screenshot({ path: `${base}.png`, fullPage: true })
    expect(errors, errors.join('\n')).toEqual([])
  })
}
