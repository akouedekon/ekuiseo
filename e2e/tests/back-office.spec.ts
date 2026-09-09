import { expect, test, type Page } from '@playwright/test'
import { loginViaUi } from '../helpers/auth'
import { SEED_ADMIN } from '../helpers/data'

/**
 * Parcours (e) : le back-office, page par page, avec le compte de demonstration promu
 * ADMIN par e2e/seed-e2e.sql. Chaque ecran doit afficher son titre et un contenu (liste,
 * indicateurs ou etat vide explicite) : un ecran blanc, une erreur JavaScript non
 * rattrapee ou l ecran de secours « Un probleme est survenu » font echouer le test.
 * Une capture pleine page de chaque ecran est jointe au rapport, quel que soit le resultat,
 * pour comparer les rendus mobile, bureau et iPhone (WebKit).
 */
const ADMIN_PAGES: { path: string; name: string; heading: RegExp }[] = [
  { path: '/admin', name: 'tableau-de-bord', heading: /Vue d.ensemble/ },
  { path: '/admin/liquidity', name: 'liquidite', heading: /Les passagers trouvent-ils/ },
  { path: '/admin/retention', name: 'retention', heading: /Rétention et paiement/ },
  { path: '/admin/reports', name: 'signalements', heading: /^Signalements/ },
  { path: '/admin/verifications', name: 'verifications', heading: /Vérifications d.identité/ },
  { path: '/admin/payouts', name: 'reversements', heading: /^Reversements/ },
  { path: '/admin/payments', name: 'paiements', heading: /^Paiements/ },
  { path: '/admin/users', name: 'utilisateurs', heading: /Utilisateurs/ },
  { path: '/admin/audit', name: 'journal', heading: /Journal d.audit/ },
]

function watchErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(`console: ${message.text()}`)
  })
  return errors
}

test.describe('Back-office', () => {
  test('chaque page du back-office s’affiche sans écran blanc ni erreur JavaScript', async ({ page }, testInfo) => {
    test.setTimeout(180_000)
    const errors = watchErrors(page)
    await loginViaUi(page, SEED_ADMIN)

    for (const entry of ADMIN_PAGES) {
      await test.step(entry.path, async () => {
        await page.goto(entry.path)
        // Le titre de l ecran (h2 de AdminPageHeader ou de la page) prouve que le chunk est charge
        // et que le composant a rendu ; l ecran de secours porte un h1 « Un problème est survenu ».
        await expect(page.getByRole('heading', { name: entry.heading }).first()).toBeVisible({ timeout: 30_000 })
        await expect(page.getByText('Un problème est survenu')).toHaveCount(0)
        // Les donnees ont fini de charger : plus aucun squelette, et un contenu reel sous le titre.
        await expect(page.locator('main .shimmer')).toHaveCount(0, { timeout: 30_000 })
        const main = page.getByRole('main')
        await expect(main).toBeVisible()
        expect((await main.innerText()).trim().length, `${entry.path} : contenu vide`).toBeGreaterThan(80)
        await page.screenshot({ path: testInfo.outputPath(`admin-${entry.name}.png`), fullPage: true })
      })
    }

    // Les erreurs 401 attendues au chargement (restauration de session sans cookie) ne comptent pas.
    const unexpected = errors.filter((line) => !/401|Failed to load resource/.test(line))
    expect(unexpected, unexpected.join('\n')).toEqual([])
  })
})
