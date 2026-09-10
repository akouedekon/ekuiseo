import { expect, test } from '@playwright/test'
import { loginViaUi } from '../helpers/auth'
import { SEED_ADMIN, SEED_DRIVER } from '../helpers/data'
import { sql } from '../helpers/db'

/**
 * Parcours (h) : reversement conducteur. Une reservation payee integralement en ligne il y
 * a deux jours (SQL : un vrai paiement Kkiapay n est pas possible dans la pile de test) est
 * constituee en lot par l administrateur, qui le marque ensuite regle avec la reference du
 * virement ; le conducteur voit le versement dans ses revenus.
 * Montant : 4 000 FCFA en MOMO_FULL, frais 320 → net 3 680 (au-dessus du seuil de 2 000).
 */
const DRIVER_ID = 'a0000000-0000-0000-0000-000000000006'
const PASSENGER_ID = 'a0000000-0000-0000-0000-000000000026'
const VEHICLE_ID = 'b0000000-0000-0000-0000-000000000006'

test.describe('Reversement conducteur', () => {
  // Identifiants uniques par execution, en hexadecimal (contrainte du type uuid).
  const stamp = Date.now().toString(16).padStart(12, '0').slice(-12)
  const tripId = `e2e00000-0000-4000-8000-${stamp}`
  const bookingId = `e2e00000-0000-4000-8001-${stamp}`
  const reference = `E2E-${stamp.toUpperCase()}`

  test.beforeAll(async () => {
    await sql(`
      INSERT INTO payment_methods (user_id, provider, phone, label, is_default, verified_at)
      SELECT '${DRIVER_ID}', 'MOOV_MONEY', '${SEED_DRIVER.phone}', 'Compte E2E', TRUE, now()
       WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE user_id = '${DRIVER_ID}' AND is_default = TRUE);
      UPDATE payment_methods SET verified_at = COALESCE(verified_at, now()) WHERE user_id = '${DRIVER_ID}' AND is_default = TRUE;
      INSERT INTO trips (id, driver_id, vehicle_id, trip_type, origin_label, origin_lat, origin_lng, dest_label, dest_lat, dest_lng,
                         departure_at, seats_total, seats_available, price_per_seat, instant_booking, description, status)
      VALUES ('${tripId}', '${DRIVER_ID}', '${VEHICLE_ID}', 'INTERURBAIN', 'Cotonou, gare Jonquet', 6.3703, 2.3912,
              'Bohicon, gare routiere', 7.1786, 2.0667, now() - interval '2 days', 4, 3, 4000, TRUE,
              'Trajet E2E reversement', 'COMPLETED');
      INSERT INTO bookings (id, trip_id, passenger_id, seats, amount, service_fee, status, payment_method, deposit_amount, balance_due_on_board, created_at)
      VALUES ('${bookingId}', '${tripId}', '${PASSENGER_ID}', 1, 4000, 320, 'COMPLETED', 'MOMO_FULL', 4000, 0, now() - interval '3 days');
      INSERT INTO payments (booking_id, provider, provider_tx_id, amount, fee, status, verified_amount, created_at)
      VALUES ('${bookingId}', 'KKIAPAY', 'e2e-${stamp}', 4000, 0, 'SUCCEEDED', 4000, now() - interval '3 days');
    `)
  })

  test('l’administrateur constitue le lot, le règle, et le conducteur voit son versement', async ({ page }) => {
    test.setTimeout(180_000)
    await loginViaUi(page, SEED_ADMIN)
    await page.goto('/admin/payouts')
    await expect(page.getByRole('heading', { name: 'Reversements' })).toBeVisible()

    await page.getByRole('button', { name: 'Constituer les lots' }).click()
    await expect(page.getByText('Constituer les lots de la semaine ?')).toBeVisible()
    await page.getByRole('button', { name: 'Constituer' }).click()

    // Le lot du conducteur apparait « À verser » avec le net de la reservation (tableau sur
    // bureau, cartes sur mobile) : on part du montant et on remonte au plus proche conteneur
    // qui porte le bouton de reglement.
    const amount = page.getByText(/3.680 FCFA/).first()
    await expect(amount).toBeVisible({ timeout: 30_000 })
    const row = amount.locator('xpath=ancestor::*[.//button[normalize-space()="Marquer réglé"]][1]')
    await expect(row).toContainText(SEED_DRIVER.lastName)
    await row.getByRole('button', { name: 'Marquer réglé' }).click()

    await expect(page.getByText('Confirmer le versement ?')).toBeVisible()
    await page.getByLabel('Référence du virement').fill(reference)
    await page.getByRole('button', { name: 'Oui, réglé' }).click()
    await expect(page.getByText(reference).first()).toBeVisible({ timeout: 30_000 })
  })

  test('le conducteur retrouve le versement dans ses revenus', async ({ page }) => {
    await loginViaUi(page, SEED_DRIVER)
    await page.goto('/me?tab=earnings')
    // Le lot regle apparait dans l historique avec son montant net et sa date de versement.
    await expect(page.getByText(/3.680 FCFA/).first()).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(/Versé le/).first()).toBeVisible()
  })
})
