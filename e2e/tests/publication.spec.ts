import { expect, test } from '@playwright/test'
import { loginViaUi, pickCity } from '../helpers/auth'
import { beninDatePlusDays, CITY, SEED_DRIVER } from '../helpers/data'

/**
 * Parcours (c) : un conducteur du seed publie un trajet interurbain en trois etapes
 * (trajet, vehicule et prix, options). Le vehicule vient du jeu de demonstration.
 */
test.describe('Publication d’un trajet', () => {
  test('le conducteur publie un trajet interurbain Bohicon → Cotonou', async ({ page }) => {
    await loginViaUi(page, SEED_DRIVER)

    await page.goto('/publish')
    await expect(page.getByRole('heading', { name: 'Publier un trajet' })).toBeVisible()

    // Etape 1 : trajet (type interurbain par defaut), date et heure en heure du Benin.
    await pickCity(page, 'Départ', CITY.bohicon)
    await pickCity(page, 'Destination', CITY.cotonou)
    await page.getByLabel('Date', { exact: true }).fill(beninDatePlusDays(2))
    await page.getByLabel(/^Heure de départ/).fill('07:30')
    await page.getByRole('button', { name: 'Continuer' }).click()

    // Etape 2 : vehicule (Select Radix), places par defaut, prix par place.
    await page.getByRole('combobox', { name: 'Sélectionnez votre véhicule' }).click()
    await page.getByRole('option', { name: SEED_DRIVER.vehicle }).click()
    await page.getByLabel('Prix par place en FCFA').fill('3500')
    await page.getByRole('button', { name: 'Continuer' }).click()

    // Etape 3 : options et recapitulatif, puis publication.
    await expect(page.getByText('Récapitulatif').first()).toBeVisible()
    await expect(page.getByText(`${CITY.bohicon.label} → ${CITY.cotonou.label}`).first()).toBeVisible()
    await page.getByRole('button', { name: 'Publier le trajet' }).click()

    await expect(page).toHaveURL(/\/trips\/mine/)
    await expect(page.getByText('Trajet publié').first()).toBeVisible()
  })
})
