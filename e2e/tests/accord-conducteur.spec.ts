import { expect, test, type Page } from '@playwright/test'
import { loginViaUi, randomUser, registerViaUi } from '../helpers/auth'
import { projectContextOptions } from '../helpers/browser'
import { E2E_TRIP, SEED_DRIVER } from '../helpers/data'
import { E2E_TRIP_ID, resetE2eTrip, sql } from '../helpers/db'

/**
 * Parcours (g) : trajet « sur accord du conducteur » (V19). Le trajet E2E est passe en
 * `instant_booking = false` ; un passager neuf demande une place en especes (la demande
 * attend le conducteur), le conducteur l accepte depuis sa liste de passagers, et la
 * reservation du passager devient confirmee. Le trajet est remis dans l etat du seed a la fin.
 */
test.describe.configure({ mode: 'serial' })

test.describe('Accord du conducteur', () => {
  let passengerPage: Page
  const passenger = randomUser('accord')
  const route = `${E2E_TRIP.originLabel} → ${E2E_TRIP.destLabel}`

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(180_000)
    await resetE2eTrip()
    await sql(`UPDATE trips SET instant_booking = FALSE WHERE id = '${E2E_TRIP_ID}'`)
    passengerPage = await browser.newPage(projectContextOptions())
    await registerViaUi(passengerPage, passenger)
  })

  test.afterAll(async () => {
    await passengerPage?.close()
    await resetE2eTrip()
  })

  test('le passager demande une place et attend le conducteur', async () => {
    await passengerPage.goto(`/book/${E2E_TRIP_ID}`)
    await passengerPage.getByRole('radio', { name: /Tout en espèces à bord/ }).click()
    await passengerPage.getByRole('button', { name: 'Demander la place' }).click()

    // Rien n est confirme sans le conducteur : l ecran le dit et donne l echeance.
    await expect(passengerPage.getByRole('heading', { name: 'Demande transmise' })).toBeVisible()
    await passengerPage.goto('/bookings')
    await expect(passengerPage.getByText('En attente du conducteur').first()).toBeVisible()
  })

  test('le conducteur accepte et la place est confirmée', async ({ browser }) => {
    const driverPage = await browser.newPage(projectContextOptions())
    try {
      await loginViaUi(driverPage, SEED_DRIVER)
      await driverPage.goto('/bookings')
      await driverPage.getByRole('tab', { name: /Je conduis/ }).click()
      const card = driverPage
        .locator('div')
        .filter({ hasText: route })
        .filter({ has: driverPage.getByRole('button', { name: 'Passagers' }) })
        .last()
      await card.getByRole('button', { name: 'Passagers' }).click()

      await expect(driverPage.getByRole('dialog', { name: 'Passagers' })).toBeVisible()
      await expect(driverPage.getByText(/demande à traiter/)).toBeVisible()
      await driverPage.getByRole('button', { name: 'Accepter', exact: true }).first().click()
      // Deux dialogues ouverts (le volet Passagers et la confirmation) : on vise celui de la confirmation.
      const confirmation = driverPage.getByRole('dialog').filter({ hasText: 'Le passager est prévenu' })
      await expect(confirmation).toBeVisible()
      await confirmation.getByRole('button', { name: 'Accepter', exact: true }).click()
      await expect(driverPage.getByText(/est confirmé/).first()).toBeVisible()
    } finally {
      await driverPage.close()
    }

    await passengerPage.goto('/bookings')
    await expect(passengerPage.getByText('Confirmée').first()).toBeVisible()
    await expect(passengerPage.getByText('En attente du conducteur')).toHaveCount(0)
  })
})
