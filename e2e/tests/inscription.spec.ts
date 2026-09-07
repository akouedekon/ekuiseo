import { expect, test } from '@playwright/test'
import { loginViaUi, randomUser, registerViaUi } from '../helpers/auth'
import { projectContextOptions } from '../helpers/browser'

/**
 * Parcours (a) : inscription d'un compte neuf par le formulaire, code de connexion recu
 * par e-mail (lu dans les journaux du backend), puis reconnexion du meme compte depuis
 * un navigateur vierge.
 */
test.describe('Inscription et connexion par code', () => {
  test('crée un compte, ouvre la session, puis se reconnecte avec le même numéro', async ({ page, browser }) => {
    const user = randomUser('inscription')

    await registerViaUi(page, user)
    // Session ouverte : une page reservee aux comptes connectes s'affiche sans redirection.
    await page.goto('/bookings')
    await expect(page.getByRole('heading', { name: 'Mes trajets' })).toBeVisible()
    await expect(page).not.toHaveURL(/\/login/)

    // Reconnexion depuis un contexte neuf (aucun jeton conserve) : le compte existe, le
    // formulaire de connexion envoie un nouveau code.
    const fresh = await browser.newContext(projectContextOptions())
    const otherPage = await fresh.newPage()
    try {
      await loginViaUi(otherPage, user)
      await otherPage.goto('/bookings')
      await expect(otherPage.getByRole('heading', { name: 'Mes trajets' })).toBeVisible()
    } finally {
      await fresh.close()
    }
  })
})
