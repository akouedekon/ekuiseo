import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

/*
 * Socle commun des tests de composants : matchers jest-dom, demontage apres
 * chaque test, et deux API absentes de jsdom que l'application interroge au
 * rendu (matchMedia pour useMediaQuery, scrollTo dans AppShell).
 */
afterEach(() => {
  cleanup()
})

if (typeof window !== 'undefined') {
  if (typeof window.matchMedia !== 'function') {
    window.matchMedia = (query: string) =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      }) as MediaQueryList
  }
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
    window.HTMLElement.prototype.scrollIntoView = () => undefined
  }
}
