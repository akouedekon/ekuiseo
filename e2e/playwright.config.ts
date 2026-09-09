import { defineConfig, devices } from '@playwright/test'

/*
 * Tests de bout en bout Ekuiseo (constats F435/F143).
 *
 * Cible : la pile Docker de developpement (docker-compose.yml + docker-compose.e2e.yml),
 * front Vite sur http://localhost:5173, API sur http://localhost:8080, jeu de demonstration
 * charge par e2e/scripts/seed.sh. Les codes de connexion sont lus dans les journaux du
 * backend (helpers/otp.ts) : Docker doit etre accessible depuis le poste qui lance les tests.
 *
 * Deux projets : mobile (Pixel 5, la cible reelle du produit) puis desktop. Un seul worker :
 * les parcours partagent le meme jeu de donnees (trajet E2E, comptes du seed) et le meme
 * journal de codes.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  // Un parcours complet (inscription, code, navigation) reste sous la minute ; la marge couvre
  // un premier chargement lent de Vite en CI.
  timeout: 120_000,
  expect: { timeout: 15_000 },
  retries: process.env.CI ? 1 : 0,
  forbidOnly: !!process.env.CI,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
  outputDir: 'test-results',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    locale: 'fr-FR',
    // Meme fuseau que le produit (heures et jours civils du Benin) : les dates saisies dans
    // les formulaires ne dependent pas de l'horloge du poste.
    timezoneId: 'Africa/Porto-Novo',
    // Traces, captures et videos conservees uniquement sur echec (artefacts de la CI).
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
  },
  projects: [
    {
      name: 'mobile',
      use: { ...devices['Pixel 5'] },
    },
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 900 } },
    },
    // iPhone (moteur WebKit, celui de Safari iOS) : reserve au parcours back-office, dont les
    // pages blanches ont ete signalees depuis un iPhone. Les autres parcours restent sur
    // Chromium, ou le selecteur Radix et le champ fichier sont stables.
    {
      name: 'iphone-webkit',
      testMatch: /back-office.spec.ts/,
      use: { ...devices['iPhone 13'] },
    },
  ],
})
