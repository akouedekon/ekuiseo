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
      /*
       * `prompt` : la nouvelle version attend l'accord de l'utilisateur (toast
       * « Mettre a jour », ServiceWorkerUpdate.tsx) au lieu de remplacer les chunks
       * pendant un tunnel de reservation (audit F344).
       */
      registerType: 'prompt',
      includeAssets: ['favicon.svg', 'icons/apple-touch-icon.png', 'og-image.png', 'og-image.svg'],
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
        /*
         * Hors du precache (audits F141, F337, F417) : la carte (MapLibre, 1 Mo), les
         * graphiques et tout le back-office - un passager ne les telecharge jamais
         * d'office. Ils restent charges a la demande et mis en cache au premier usage
         * par la regle CacheFirst sur /assets/ ci-dessous (noms haches, immuables).
         */
        globIgnores: [
          '**/map-*',
          '**/charts-*',
          '**/Admin*',
          '**/DataTable-*',
          '**/useAdmin-*',
          '**/AdminPageHeader-*',
          '**/AuditTable-*',
          '**/SuspendUserDialog-*',
          '**/UserMotivatedActionDialog-*',
          '**/og-image.*',
        ],
        navigateFallback: `${BASE_PATH}index.html`,
        navigateFallbackDenylist: [/^\/api\//, /^\/share\//],
        cleanupOutdatedCaches: true,
        // Valeur par defaut de Workbox : rien de plus lourd n'a sa place dans le precache.
        maximumFileSizeToCacheInBytes: 2 * 1024 * 1024,
        runtimeCaching: [
          {
            // Chunks charges a la demande (carte, graphiques, back-office) : haches, donc immuables.
            urlPattern: ({ url, request }) => request.destination === 'script' && url.pathname.includes('/assets/'),
            handler: 'CacheFirst',
            options: {
              cacheName: 'ekuiseo-assets',
              expiration: { maxEntries: 60, maxAgeSeconds: 30 * 24 * 60 * 60 },
              cacheableResponse: { statuses: [200] },
            },
          },
          {
            /*
             * Lectures API PUBLIQUES uniquement, reseau d'abord, cache en secours :
             * recherche et detail de trajet, axes populaires, referentiel geo, profil
             * public. Le cache Workbox est cle sur l'URL, sans l'en-tete Authorization :
             * une reponse personnelle (/me, /bookings, /notifications, /admin,
             * /payments, /conversations) servie a un autre compte sur un appareil
             * partage serait une fuite. Elles ne passent donc jamais par ici, et une
             * requete portant un jeton n'est jamais mise en cache (cacheWillUpdate).
             * Pas de `networkTimeoutSeconds` : le cache ne sert qu'en echec reseau, jamais
             * parce que le serveur est lent (audit F339 : places et statuts perimes).
             * Ces fonctions sont serialisees dans sw.js : aucune reference externe.
             */
            urlPattern: ({ url, request }) =>
              request.method === 'GET' &&
              !request.headers.has('Authorization') &&
              /^\/api\/v1\/(trips\/search|trips\/popular|trips\/[^/]+(\/stops)?|geo\/[^/]+|users\/[^/]+(\/reviews)?)\/?$/.test(
                url.pathname,
              ),
            handler: 'NetworkFirst',
            options: {
              // Meme nom que API_CACHE_NAME dans src/lib/queryClient.ts (purge a la deconnexion).
              cacheName: 'ekuiseo-api',
              expiration: { maxEntries: 120, maxAgeSeconds: 24 * 60 * 60 },
              cacheableResponse: { statuses: [200] },
              plugins: [
                {
                  cacheWillUpdate: async ({ request, response }) =>
                    request.headers.has('Authorization') ? null : response,
                },
              ],
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
    /*
     * Sourcemaps produites mais non referencees par les bundles (`hidden`) : elles
     * servent a lire les piles remontees par lib/monitoring.ts et ne doivent pas
     * etre copiees dans l'image nginx (audit F440).
     */
    sourcemap: 'hidden',
    // La carte est isolee dans son propre chunk ; Recharts suit le lazy des routes admin (audit F335).
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/maplibre-gl')) return 'map'
        },
      },
    },
    chunkSizeWarningLimit: 500,
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
