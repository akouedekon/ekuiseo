import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig, loadEnv } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'node:path'

/*
 * Sous-chemin de publication : "/" en production (le site est a la racine du
 * domaine), "/ekuiseo/" pour la vitrine GitHub Pages. Toujours termine par "/".
 */
const BASE_PATH = (process.env.VITE_BASE_PATH ?? '/').replace(/\/?$/, '/')

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // Les variables des fichiers .env* ne sont pas dans process.env : on les charge pour le proxy.
  const env = loadEnv(mode, process.cwd(), '')
  const devApi = env.VITE_DEV_API_URL || 'http://localhost:8080'
  return {
  base: BASE_PATH,
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'icons/apple-touch-icon.png'],
      manifest: {
        name: 'Ekuiseo — Covoiturage au Bénin',
        short_name: 'Ekuiseo',
        description:
          "Trajets interurbains et navettes quotidiennes au Bénin. Acompte de 1 000 FCFA en mobile money, solde en espèces à bord.",
        lang: 'fr',
        dir: 'ltr',
        start_url: BASE_PATH,
        scope: BASE_PATH,
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#F6F6F3',
        theme_color: '#0E7C4A',
        categories: ['travel', 'navigation', 'lifestyle'],
        // Chemins relatifs au manifeste : valables quel que soit le sous-chemin de publication.
        icons: [
          { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: 'icons/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
        shortcuts: [
          { name: 'Rechercher un trajet', short_name: 'Rechercher', url: BASE_PATH },
          { name: 'Publier un trajet', short_name: 'Publier', url: `${BASE_PATH}publish` },
          { name: 'Mes réservations', short_name: 'Réservations', url: `${BASE_PATH}bookings` },
        ],
      },
      workbox: {
        // Coque applicative precachee : l'app s'ouvre meme sans reseau.
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        navigateFallback: `${BASE_PATH}index.html`,
        navigateFallbackDenylist: [/^\/api\//],
        cleanupOutdatedCaches: true,
        // maplibre-gl est volumineux et charge a la demande.
        maximumFileSizeToCacheInBytes: 6 * 1024 * 1024,
        runtimeCaching: [
          {
            // Lectures API : on sert le reseau d'abord, le cache en secours.
            urlPattern: ({ url }) => url.pathname.startsWith('/api/v1/'),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'ekuiseo-api',
              networkTimeoutSeconds: 6,
              expiration: { maxEntries: 120, maxAgeSeconds: 24 * 60 * 60 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
          {
            // Tuiles de carte : cache d'abord, elles changent rarement.
            urlPattern: ({ url }) => /tiles?|maptiler|basemaps/.test(url.hostname),
            handler: 'CacheFirst',
            options: {
              cacheName: 'ekuiseo-tiles',
              expiration: { maxEntries: 400, maxAgeSeconds: 30 * 24 * 60 * 60 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
      devOptions: { enabled: false },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  build: {
    // Le back-office et la carte sont isoles pour alleger le paquet principal.
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/recharts') || id.includes('node_modules/d3-')) return 'charts'
          if (id.includes('node_modules/maplibre-gl')) return 'map'
        },
      },
    },
    chunkSizeWarningLimit: 900,
  },
  server: {
    host: true,
    // PORT permet a un lanceur externe d'imposer un port (ex. quand 5173 est deja pris).
    port: Number(process.env.PORT) || 5173,
    // En developpement, /api est relaye vers le backend local : memes URL relatives
    // qu'en production, pas de CORS a configurer. VITE_DEV_API_URL pour un autre backend.
    proxy: {
      '/api': { target: devApi, changeOrigin: true },
      '/actuator': { target: devApi, changeOrigin: true },
    },
  },
  }
})
