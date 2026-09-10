import { expect, test, type Page } from '@playwright/test'
import { loginViaUi, randomUser, registerViaUi } from '../helpers/auth'
import { projectContextOptions } from '../helpers/browser'
import { E2E_TRIP, SEED_ADMIN, SEED_DRIVER } from '../helpers/data'
import { E2E_TRIP_ID, resetE2eTrip, sql } from '../helpers/db'

/**
 * Parcours (f) : litige « conducteur absent » de bout en bout (V21 + V25), avec les trois
 * roles sur trois sessions :
 *  1. un passager neuf reserve le trajet E2E en especes (reservation confirmee), puis le
 *     trajet est fait partir il y a deux heures (SQL : l interface ne sait pas voyager
 *     dans le temps) et le passager declare depuis « Mes trajets » que le conducteur n est
 *     pas venu ;
 *  2. le conducteur du seed ouvre la liste des passagers du trajet et conteste ;
 *  3. l administrateur tranche « trajet maintenu » depuis Signalements.
 * A la fin, le trajet E2E est remis dans l etat du seed pour les parcours suivants.
 */
test.describe.configure({ mode: 'serial' })

test.describe('Litige conducteur absent', () => {
  let passengerPage: Page
  const passenger = randomUser('litige')
  const route = `${E2E_TRIP.originLabel} → ${E2E_TRIP.destLabel}`

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(180_000)
    await resetE2eTrip()
    passengerPage = await browser.newPage(projectContextOptions())
    await registerViaUi(passengerPage, passenger)

    // Reservation en especes : confirmee immediatement (conducteur du seed verifie).
    await passengerPage.goto(`/book/${E2E_TRIP_ID}`)
    await passengerPage.getByRole('radio', { name: /Tout en espèces à bord/ }).click()
    await passengerPage.getByRole('button', { name: 'Demander la place' }).click()
    await expect(passengerPage.getByRole('heading', { name: 'Demande enregistrée' })).toBeVisible()

    // Le trajet est parti il y a deux heures : le constat du passager devient possible.
    await sql(`UPDATE trips SET status = 'ONGOING', departure_at = now() - interval '2 hours' WHERE id = '${E2E_TRIP_ID}'`)
  })

  test.afterAll(async () => {
    await passengerPage?.close()
    await resetE2eTrip()
  })

  test('le passager déclare que le conducteur n’est pas venu', async () => {
    await passengerPage.goto('/bookings?tab=past')
    await expect(passengerPage.getByRole('heading', { name: 'Mes trajets' })).toBeVisible()
    await expect(passengerPage.getByText('Ce trajet a-t-il eu lieu ?')).toBeVisible()
    await passengerPage.getByRole('button', { name: "Le conducteur n'est pas venu" }).click()

    await expect(passengerPage.getByText("Le conducteur n'est pas venu ?")).toBeVisible()
    await passengerPage.getByLabel('Précisions pour la modération (facultatif)').fill('Attendu 40 minutes à la gare, aucune réponse.')
    await passengerPage.getByRole('button', { name: "Signaler l'absence" }).click()

    await expect(passengerPage.getByText('Absence du conducteur signalée').first()).toBeVisible()
    // Le dossier explique la suite : contestation possible, remboursement automatique sinon.
    await expect(passengerPage.getByText(/Le conducteur peut contester jusqu'au/)).toBeVisible()
  })

  test('le conducteur conteste depuis la liste des passagers', async ({ browser }) => {
    const driverPage = await browser.newPage(projectContextOptions())
    try {
      await loginViaUi(driverPage, SEED_DRIVER)
      await driverPage.goto('/bookings')
      await driverPage.getByRole('tab', { name: /Je conduis/ }).click()

      // Carte du trajet E2E (en cours, donc dans « À venir » des trajets conduits).
      const card = driverPage
        .locator('div')
        .filter({ hasText: route })
        .filter({ has: driverPage.getByRole('button', { name: 'Passagers' }) })
        .last()
      await card.getByRole('button', { name: 'Passagers' }).click()

      await expect(driverPage.getByRole('dialog', { name: 'Passagers' })).toBeVisible()
      await expect(driverPage.getByText(/Ce passager déclare que vous n'étiez pas au départ/)).toBeVisible()
      await driverPage.getByRole('button', { name: 'Contester' }).click()

      await expect(driverPage.getByText(/Contester l'absence déclarée par/)).toBeVisible()
      await driverPage.getByLabel('Votre version des faits').fill("J'étais à la gare Jonquet à 9 h, le passager n'a pas répondu au téléphone.")
      await driverPage.getByRole('button', { name: 'Envoyer ma version' }).click()

      await expect(driverPage.getByText('Contestation enregistrée').first()).toBeVisible()
      await expect(driverPage.getByText(/Vous avez contesté/)).toBeVisible()
    } finally {
      await driverPage.close()
    }
  })

  test('la modération tranche « trajet maintenu » et le passager en est informé', async ({ browser }) => {
    const adminPage = await browser.newPage(projectContextOptions())
    try {
      await loginViaUi(adminPage, SEED_ADMIN)
      await adminPage.goto('/admin/reports')
      await adminPage.getByRole('tab', { name: 'En cours' }).click()

      const dossier = adminPage.locator('[data-testid="no-show-dispute"]').filter({ hasText: 'Version du conducteur' }).first()
      await expect(dossier).toBeVisible()
      await expect(dossier).toContainText("J'étais à la gare Jonquet")
      await adminPage.getByRole('button', { name: 'Trajet maintenu, payer le conducteur' }).first().click()

      await expect(adminPage.getByText('Maintenir le trajet et payer le conducteur ?')).toBeVisible()
      await adminPage.getByLabel('Note de décision').fill('Messages vérifiés : le passager a renoncé sans prévenir.')
      await adminPage.getByRole('button', { name: 'Maintenir et payer le conducteur' }).click()
      await expect(adminPage.getByText('Trajet maintenu : réservation reversée au conducteur').first()).toBeVisible()

      await adminPage.getByRole('tab', { name: 'Résolus' }).click()
      await expect(adminPage.getByText('Trajet maintenu, reversé au conducteur').first()).toBeVisible()
    } finally {
      await adminPage.close()
    }

    // Le passager voit la decision sur sa reservation.
    await passengerPage.goto('/bookings?tab=past')
    await expect(passengerPage.getByText(/la modération retient que le trajet a eu lieu/)).toBeVisible()
  })
})
