import { expect, test, type Page } from '@playwright/test'
import { pickCity, randomUser, registerViaUi } from '../helpers/auth'
import { projectContextOptions } from '../helpers/browser'
import { beninDatePlusDays, CITY, E2E_TRIP, SEED_DRIVER } from '../helpers/data'

/**
 * Parcours (b) puis (d), enchaines sur la meme session passager :
 *  - recherche Cotonou -> Bohicon depuis l'accueil, ouverture du trajet E2E (conducteur du
 *    seed a identite verifiee), reservation « tout en especes a bord » (mode CASH : aucune
 *    etape de paiement, la demande est enregistree immediatement) ;
 *  - annulation de cette reservation depuis « Mes trajets ».
 * Le passager est cree a chaque execution : aucune reservation preexistante ne parasite
 * la liste, et les deux projets (mobile, desktop) rejouent le parcours independamment.
 */
test.describe.configure({ mode: 'serial' })

test.describe('Réservation en espèces puis annulation', () => {
  let page: Page
  const passenger = randomUser('passager')

  test.beforeAll(async ({ browser }) => {
    // Page partagee par les deux tests (mode serial) : on lui repasse le gabarit du projet.
    page = await browser.newPage(projectContextOptions())
    await registerViaUi(page, passenger)
  })

  test.afterAll(async () => {
    await page?.close()
  })

  test('recherche Cotonou → Bohicon depuis l’accueil et réserve en espèces', async () => {
    await page.goto('/')
    await pickCity(page, 'Départ', CITY.cotonou)
    await pickCity(page, 'Arrivée', CITY.bohicon)
    await page.getByLabel('Date de départ').fill(beninDatePlusDays(E2E_TRIP.daysAhead))
    await page.getByRole('button', { name: 'Rechercher un trajet' }).click()
    await expect(page).toHaveURL(/\/search\?/)

    // Carte de resultat (TripCard) : lien nomme « <origine> vers <destination>, depart ... ».
    const result = page
      .getByRole('link', { name: new RegExp(`${E2E_TRIP.originLabel} vers ${E2E_TRIP.destLabel}`) })
      .filter({ hasText: SEED_DRIVER.cardName })
      .first()
    await expect(result).toBeVisible()
    await result.click()
    await expect(page).toHaveURL(/\/trips\//)

    // Fiche trajet : « Reserver » (colonne laterale sur desktop, barre collante sur mobile).
    await page.getByRole('button', { name: 'Réserver', exact: true }).first().click()
    await expect(page).toHaveURL(/\/book\//)

    // Etape recapitulatif : mode de reglement en especes (propose seulement si le conducteur
    // est verifie), puis demande de place.
    await page.getByRole('radio', { name: /Tout en espèces à bord/ }).click()
    await page.getByRole('button', { name: 'Demander la place' }).click()
    await expect(page.getByRole('heading', { name: 'Demande enregistrée' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Voir mes réservations' })).toBeVisible()
  })

  test('annule la réservation depuis « Mes trajets »', async () => {
    await page.goto('/bookings')
    await expect(page.getByRole('heading', { name: 'Mes trajets' })).toBeVisible()
    await expect(
      page.getByRole('link', { name: `${E2E_TRIP.originLabel} → ${E2E_TRIP.destLabel}` }).first(),
    ).toBeVisible()

    // Le passager n'a que cette reservation : un seul bouton « Annuler » dans l'onglet « A venir ».
    await page.getByRole('button', { name: 'Annuler', exact: true }).first().click()
    // ConfirmDialog : action destructrice confirmee explicitement.
    await expect(page.getByText('Annuler cette réservation ?')).toBeVisible()
    await page.getByRole('button', { name: "Confirmer l'annulation" }).click()

    await expect(page.getByText('Réservation annulée').first()).toBeVisible()
    // Plus rien a annuler : la reservation est close.
    await expect(page.getByRole('button', { name: 'Annuler', exact: true })).toHaveCount(0)
  })
})
