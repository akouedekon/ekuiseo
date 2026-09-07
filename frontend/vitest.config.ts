import path from 'node:path'
import { defineConfig } from 'vitest/config'

/**
 * Tests unitaires et de composants. Environnement jsdom (audit F435) : les
 * hooks, gardes de route et ecrans du tunnel de reservation se testent avec
 * Testing Library ; `fetch` est remplace par un double dans chaque test.
 * `localStorage` peut etre absent ou vide : le code doit le tolerer.
 */
export default defineConfig({
  resolve: { alias: { '@': path.resolve(import.meta.dirname, 'src') } },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
    clearMocks: true,
    restoreMocks: true,
  },
})
