import path from 'node:path'
import { defineConfig } from 'vitest/config'

/**
 * Tests unitaires de la logique sans DOM (client HTTP, erreurs, regles de
 * paiement, validation). Environnement Node : localStorage absent, ce que le
 * code doit tolerer (navigation privee), et `fetch` remplace par un double.
 */
export default defineConfig({
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    clearMocks: true,
  },
})
