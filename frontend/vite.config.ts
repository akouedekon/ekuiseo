import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig({
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
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#FFFDF7',
        theme_color: '#2E3FA8',
        categories: ['travel', 'navigation', 'lifestyle'],
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/icons/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
        shortcuts: [
          { name: 'Rechercher un trajet', short_name: 'Rechercher', url: '/' },
          { name: 'Publier un trajet', short_name: 'Publier', url: '/publish' },
          { name: 'Mes réservations', short_name: 'Réservations', url: '/bookings' },
        ],
      },
      workbox: {
        // Coque applicative precachee : l'app s'ouvre meme sans reseau.
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        navigateFallback: '/index.html',
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
    port: 5173,
  },
})
