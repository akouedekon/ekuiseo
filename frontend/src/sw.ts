/// <reference lib="webworker" />
import { CacheableResponsePlugin } from 'workbox-cacheable-response'
import { ExpirationPlugin } from 'workbox-expiration'
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching'
import { NavigationRoute, registerRoute } from 'workbox-routing'
import { CacheFirst, NetworkFirst } from 'workbox-strategies'

/*
 * Service worker de l'application (vite-plugin-pwa, strategie `injectManifest`).
 *
 * Il reproduit a l'identique ce que `generateSW` produisait depuis vite.config.ts
 * (precache de la coque, chunks a la demande, lectures API publiques, tuiles de
 * carte, mise a jour en mode « prompt ») et y ajoute la reception des notifications
 * Web Push (V20) : `push` affiche la notification, `notificationclick` ouvre l'ecran
 * vise. Ce fichier est compile par Vite : `import.meta.env.BASE_URL` y est remplace.
 */
declare let self: ServiceWorkerGlobalScope

/** Sous-chemin de publication, toujours termine par « / » (voir BASE_PATH dans vite.config.ts). */
const BASE_PATH = import.meta.env.BASE_URL.replace(/\/?$/, '/')

/*
 * Mode « prompt » (audit F344) : la nouvelle version reste en attente jusqu'a ce que
 * l'utilisateur accepte la mise a jour (toast de ServiceWorkerUpdate.tsx), moment ou
 * workbox-window envoie SKIP_WAITING. Aucun `clientsClaim` : la page en cours garde
 * son ancien worker jusqu'au rechargement.
 */
self.addEventListener('message', (event) => {
  const data: unknown = event.data
  if (typeof data === 'object' && data !== null && (data as { type?: unknown }).type === 'SKIP_WAITING') {
    void self.skipWaiting()
  }
})

/* ------------------------------------------------------------- Precache */

cleanupOutdatedCaches()
// Liste des fichiers de la coque, injectee au build (globPatterns / globIgnores de vite.config.ts).
precacheAndRoute(self.__WB_MANIFEST)

// Navigation : index.html precache pour toute route de l'application, jamais pour l'API ni l'apercu de partage.
registerRoute(new NavigationRoute(createHandlerBoundToURL(`${BASE_PATH}index.html`), { denylist: [/^\/api\//, /^\/share\//] }))

/* ------------------------------------------------------- Cache runtime */

// Chunks charges a la demande (carte, graphiques, back-office) : haches, donc immuables.
registerRoute(
  ({ url, request }) => request.destination === 'script' && url.pathname.includes('/assets/'),
  new CacheFirst({
    cacheName: 'ekuiseo-assets',
    plugins: [
      new ExpirationPlugin({ maxEntries: 60, maxAgeSeconds: 30 * 24 * 60 * 60 }),
      new CacheableResponsePlugin({ statuses: [200] }),
    ],
  }),
)

/*
 * Lectures API PUBLIQUES uniquement, reseau d'abord, cache en secours : recherche et
 * detail de trajet, axes populaires, referentiel geo, profil public. Le cache Workbox
 * est cle sur l'URL, sans l'en-tete Authorization : une reponse personnelle (/me,
 * /bookings, /notifications, /admin, /payments, /conversations) servie a un autre
 * compte sur un appareil partage serait une fuite. Elles ne passent donc jamais par
 * ici, et une requete portant un jeton n'est jamais mise en cache (cacheWillUpdate).
 * Pas de `networkTimeoutSeconds` : le cache ne sert qu'en echec reseau, jamais parce
 * que le serveur est lent (audit F339 : places et statuts perimes).
 */
const PUBLIC_API = /^\/api\/v1\/(trips\/search|trips\/popular|trips\/[^/]+(\/stops)?|geo\/[^/]+|users\/[^/]+(\/reviews)?)\/?$/

registerRoute(
  ({ url, request }) => request.method === 'GET' && !request.headers.has('Authorization') && PUBLIC_API.test(url.pathname),
  new NetworkFirst({
    // Meme nom que API_CACHE_NAME dans src/lib/queryClient.ts (purge a la deconnexion).
    cacheName: 'ekuiseo-api',
    plugins: [
      new ExpirationPlugin({ maxEntries: 120, maxAgeSeconds: 24 * 60 * 60 }),
      new CacheableResponsePlugin({ statuses: [200] }),
      { cacheWillUpdate: async ({ request, response }) => (request.headers.has('Authorization') ? null : response) },
    ],
  }),
)

// Tuiles de carte : cache d'abord, elles changent rarement.
registerRoute(
  // Hote exact (aligne sur la CSP) : un domaine tiers contenant « tiles » ne doit pas entrer dans ce cache.
  ({ url }) => url.hostname === 'api.maptiler.com',
  new CacheFirst({
    cacheName: 'ekuiseo-tiles',
    plugins: [
      new ExpirationPlugin({ maxEntries: 400, maxAgeSeconds: 30 * 24 * 60 * 60 }),
      new CacheableResponsePlugin({ statuses: [0, 200] }),
    ],
  }),
)

/* ------------------------------------------------------------ Web Push */

/** Contenu envoye par le backend (WebPushSender) : titre, corps court, chemin de l'application, etiquette. */
interface PushPayload {
  title?: string
  body?: string
  url?: string
  tag?: string
}

/** Un chemin absolu de l'application (« /bookings ») devient une URL sous le sous-chemin de publication. */
function resolveAppUrl(path: string | undefined): string {
  const target = path && path.startsWith('/') ? `${BASE_PATH.replace(/\/$/, '')}${path}` : BASE_PATH
  return new URL(target, self.location.origin).href
}

function readPayload(event: PushEvent): PushPayload {
  if (!event.data) return {}
  try {
    const parsed: unknown = event.data.json()
    return typeof parsed === 'object' && parsed !== null ? (parsed as PushPayload) : {}
  } catch {
    return { body: event.data.text() }
  }
}

self.addEventListener('push', (event) => {
  const payload = readPayload(event)
  const options: NotificationOptions = {
    body: payload.body,
    icon: `${BASE_PATH}icons/icon-192.png`,
    badge: `${BASE_PATH}icons/icon-192.png`,
    tag: payload.tag,
    lang: 'fr',
    data: { url: resolveAppUrl(payload.url) },
  }
  event.waitUntil(self.registration.showNotification(payload.title || 'Ekuiseo', options))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const data = event.notification.data as { url?: string } | undefined
  const url = data?.url ?? resolveAppUrl(undefined)
  event.waitUntil(
    (async () => {
      // Un onglet de l'application deja ouvert est reutilise et amene sur l'ecran vise.
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      for (const client of windows) {
        if (new URL(client.url).origin !== self.location.origin) continue
        const focused = await client.focus()
        try {
          await focused.navigate(url)
        } catch {
          /* navigation refusee (onglet non controle) : le focus suffit */
        }
        return
      }
      await self.clients.openWindow(url)
    })(),
  )
})
