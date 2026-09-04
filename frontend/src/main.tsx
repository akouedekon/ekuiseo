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

import { queryClient, createPersister } from '@/lib/queryClient'
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
  <ErrorBoundary>
    <TooltipProvider delayDuration={250}>
      <BrowserRouter>
        <App />
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
  </ErrorBoundary>
)

// L'ecran de demarrage inline (index.html) disparait des que React a rendu.
document.getElementById('boot')?.remove()

root.render(
  <StrictMode>
    {persister ? (
      <PersistQueryClientProvider
        client={queryClient}
        persistOptions={{ persister, maxAge: 24 * 60 * 60 * 1000, buster: 'v2' }}
      >
        {tree}
      </PersistQueryClientProvider>
    ) : (
      <QueryClientProvider client={queryClient}>{tree}</QueryClientProvider>
    )}
  </StrictMode>,
)
