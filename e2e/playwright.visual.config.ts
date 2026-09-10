import { defineConfig, devices } from '@playwright/test'

/*
 * Captures d ecran hors ligne (visual/mobile.spec.ts) : Playwright lance le serveur Vite du
 * front sur un port dedie et simule l API (visual/fixtures.ts). Aucun backend, aucun Docker.
 *
 *   cd e2e && npm run visual
 *
 * Projets : « mobile » (Pixel 5, navigateur) et « app » (meme gabarit, pont Capacitor simule
 * pour voir l application telle qu elle se presente dans l APK).
 */
const PORT = 5177

export default defineConfig({
  testDir: './visual',
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  retries: 0,
  reporter: [['list']],
  outputDir: 'visual/test-results',
  webServer: {
    command: `npm --prefix ../frontend run dev -- --port ${PORT} --strictPort`,
    url: `http://localhost:${PORT}/`,
    reuseExistingServer: true,
    timeout: 120_000,
    env: { VITE_API_URL: '', VITE_ERROR_REPORT_URL: 'off', VITE_MAP_STYLE_URL: '' },
  },
  use: {
    // Navigateur du poste (Chrome ou Edge) quand le telechargement de Chromium par Playwright
    // est impossible ; VISUAL_CHANNEL=chromium pour revenir au navigateur de Playwright.
    channel: process.env.VISUAL_CHANNEL ?? 'chrome',
    baseURL: `http://localhost:${PORT}`,
    locale: 'fr-FR',
    timezoneId: 'Africa/Porto-Novo',
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
  },
  projects: [
    { name: 'mobile', use: { ...devices['Pixel 5'] } },
    { name: 'app', use: { ...devices['Pixel 5'] } },
  ],
})
