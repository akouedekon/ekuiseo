import { expect, type Page } from '@playwright/test'
import { OtpMailbox } from './otp'

export interface TestUser {
  phone: string
  email: string
  firstName: string
  lastName: string
}

/**
 * Compte neuf a chaque execution : numero beninois au format 2024 (+229 01 + 8 chiffres,
 * cf. lib/validation.ts et PhoneNumbers.java) et adresse e-mail unique. Aucune collision
 * avec le seed (+229 01 97 00 10 xx / +229 01 96 00 20 xx) en pratique.
 */
export function randomUser(prefix = 'e2e'): TestUser {
  const digits = String(Math.floor(Math.random() * 1e8)).padStart(8, '0')
  const stamp = Date.now().toString(36)
  return {
    phone: `+22901${digits}`,
    email: `${prefix}-${stamp}-${digits}@example.com`,
    firstName: 'Test',
    lastName: `Playwright ${stamp}`,
  }
}

/** Saisit le code a 6 chiffres lu dans les journaux (OtpInput : six cases, saisie en cascade). */
async function enterCode(page: Page, mailbox: OtpMailbox): Promise<void> {
  await expect(page.getByText('Saisissez le code à 6 chiffres')).toBeVisible()
  const code = await mailbox.waitForCode()
  await page.getByRole('textbox', { name: 'Chiffre 1 sur 6' }).click()
  // Chaque chiffre fait avancer le focus a la case suivante ; le dernier declenche la verification.
  await page.keyboard.type(code, { delay: 40 })
}

/**
 * Ecran bloquant d'acceptation des CGU (features/account/TermsGate.tsx) : il s'affiche pour
 * les comptes du seed, qui n'ont jamais accepte de version. Un compte cree par le formulaire
 * d'inscription ne le voit pas.
 */
export async function acceptTermsIfAsked(page: Page): Promise<void> {
  const accept = page.getByRole('button', { name: 'Accepter et continuer' })
  const shown = await accept
    .waitFor({ state: 'visible', timeout: 4_000 })
    .then(() => true)
    .catch(() => false)
  if (!shown) return
  await page.getByRole('checkbox').first().click()
  await accept.click()
  await expect(accept).toBeHidden()
}

/** Inscription par le formulaire (/register) puis validation du code : la session est ouverte. */
export async function registerViaUi(page: Page, user: TestUser): Promise<void> {
  const mailbox = new OtpMailbox(user.email)
  await mailbox.snapshot()

  await page.goto('/register')
  await expect(page.getByRole('heading', { name: 'Créer un compte' })).toBeVisible()
  await page.getByLabel('Prénom').fill(user.firstName)
  // `exact` : « Nom » est aussi contenu dans « Prénom ».
  await page.getByLabel('Nom', { exact: true }).fill(user.lastName)
  await page.getByLabel('Numéro de téléphone').fill(user.phone)
  await page.getByLabel('E-mail').fill(user.email)
  // Case CGU (Radix Checkbox, seule case de l'ecran).
  await page.getByRole('checkbox').first().click()
  await page.getByRole('button', { name: 'Créer mon compte' }).click()

  await enterCode(page, mailbox)
  await expect(page).not.toHaveURL(/\/register/)
  await expect(page.getByRole('link', { name: 'Connexion' })).toHaveCount(0)
}

/** Connexion d'un compte existant (/login) par code, puis acceptation des CGU si demandee. */
export async function loginViaUi(page: Page, user: Pick<TestUser, 'phone' | 'email'>): Promise<void> {
  const mailbox = new OtpMailbox(user.email)
  await mailbox.snapshot()

  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'Bienvenue sur Ekuiseo' })).toBeVisible()
  await page.getByLabel('Numéro de téléphone').fill(user.phone)
  await page.getByRole('button', { name: 'Recevoir le code' }).click()

  await enterCode(page, mailbox)
  await expect(page).not.toHaveURL(/\/login/)
  await acceptTermsIfAsked(page)
  await expect(page.getByRole('link', { name: 'Connexion' })).toHaveCount(0)
}

/**
 * Selection d'une ville dans un champ CityAutocomplete (combobox WAI-ARIA) : saisie, puis clic
 * sur l'option du referentiel serveur. `option` est le nom accessible de la ligne
 * (« Cotonou Littoral »), `label` la valeur affichee une fois choisie.
 */
export async function pickCity(
  page: Page,
  field: string,
  city: { query: string; option: RegExp; label: string },
): Promise<void> {
  const input = page.getByRole('combobox', { name: field, exact: true })
  await input.click()
  await input.fill(city.query)
  await page.getByRole('option', { name: city.option }).first().click()
  await expect(input).toHaveValue(city.label)
}
