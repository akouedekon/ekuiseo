import { test, type BrowserContextOptions } from '@playwright/test'

/**
 * Options de contexte du projet courant (gabarit Pixel 5 ou bureau, fuseau, langue,
 * baseURL) a repasser explicitement quand un test cree lui-meme un contexte ou une page
 * via `browser` : contrairement a la fixture `page`, `browser.newPage()` n'herite pas de
 * la configuration du projet, et le parcours « mobile » tournerait en fait sur un bureau.
 */
export function projectContextOptions(): BrowserContextOptions {
  const use = test.info().project.use
  return {
    baseURL: use.baseURL,
    locale: use.locale,
    timezoneId: use.timezoneId,
    viewport: use.viewport,
    userAgent: use.userAgent,
    deviceScaleFactor: use.deviceScaleFactor,
    isMobile: use.isMobile,
    hasTouch: use.hasTouch,
    colorScheme: use.colorScheme,
  }
}
