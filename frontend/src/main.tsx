import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClientProvider, onlineManager } from '@tanstack/react-query'
import { PersistQueryClientProvider } from '@tanstack/react-query-persist-client'
import { TooltipProvider } from '@radix-ui/react-tooltip'
import { Toaster } from 'sonner'

// Polices auto-hebergees (pas de CDN) : seul le sous-ensemble latin est charge.
// Archivo pour ce qui se lit de loin (titres, prix), Inter pour le reste.
import '@fontsource/archivo/latin-700.css'
import '@fontsource/archivo/latin-800.css'
import '@fontsource/inter/latin-400.css'
import '@fontsource/inter/latin-500.css'
import '@fontsource/inter/latin-600.css'
import '@fontsource/inter/latin-700.css'

import { queryClient, createPersister, createPersistOptions } from '@/lib/queryClient'
import { applyTheme, readStoredTheme } from '@/lib/theme'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import './index.css'
import App from './App.tsx'

// Le theme est applique avant le premier rendu pour eviter tout clignotement.
applyTheme(readStoredTheme())

const persister = createPersister()

/**
 * Reprise de la file d'attente au retour du reseau : les mutations mises en
 * pause par networkMode 'offlineFirst' sont rejouees dans l'ordre.
 */
onlineManager.subscribe((online) => {
  if (online) void queryClient.resumePausedMutations()
})

const root = createRoot(document.getElementById('root')!)

const tree = (
  <TooltipProvider delayDuration={250}>
    {/* Sous-chemin de publication (vitrine GitHub Pages) : le routeur doit le connaitre. */}
    <BrowserRouter basename={import.meta.env.BASE_URL.replace(/\/$/, '')}>
      {/* La frontiere d'erreur vit dans le routeur : son ecran de secours contient des liens. */}
      <ErrorBoundary>
        <App />
      </ErrorBoundary>
      <Toaster
          position="top-center"
          offset={68}
          closeButton
          toastOptions={{
            // Les toasts empruntent les tokens : aucune couleur en dur.
            style: {
              background: 'var(--surface)',
              color: 'var(--ink)',
              border: '1px solid var(--rule)',
              borderRadius: 'var(--radius-card)',
              fontFamily: 'var(--font-sans)',
              boxShadow: 'var(--shadow-3)',
            },
          }}
        />
    </BrowserRouter>
  </TooltipProvider>
)

// L'ecran de demarrage inline (index.html) disparait des que React a rendu.
document.getElementById('boot')?.remove()

root.render(
  <StrictMode>
    {persister ? (
      // Seules les donnees publiques sont persistees, et le cache est cloisonne par compte (lib/queryClient.ts).
      <PersistQueryClientProvider client={queryClient} persistOptions={createPersistOptions(persister)}>
        {tree}
      </PersistQueryClientProvider>
    ) : (
      <QueryClientProvider client={queryClient}>{tree}</QueryClientProvider>
    )}
  </StrictMode>,
)
