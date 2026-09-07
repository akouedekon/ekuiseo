import { useIsMutating } from '@tanstack/react-query'
import { useEffect } from 'react'
import { toast } from 'sonner'
import { useRegisterSW } from 'virtual:pwa-register/react'

const TOAST_ID = 'sw-update'

/**
 * Mise a jour du service worker en mode « prompt » (audit F344) : la nouvelle
 * version attend que l'utilisateur l'accepte, au lieu de remplacer les chunks
 * sous les pieds d'un tunnel de reservation en cours. Le toast reste affiche
 * jusqu'a l'action ; tant qu'une mutation est en vol, on ne recharge pas.
 */
export function ServiceWorkerUpdate() {
  const mutating = useIsMutating()
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    // Une verification a l'ouverture suffit ; le navigateur revalide sw.js de lui-meme ensuite.
    onRegisteredSW(_url, registration) {
      registration?.update().catch(() => undefined)
    },
  })

  useEffect(() => {
    if (!needRefresh) {
      toast.dismiss(TOAST_ID)
      return
    }
    toast.message('Une nouvelle version est disponible', {
      id: TOAST_ID,
      duration: Infinity,
      description: 'Mettez à jour pour profiter des dernières corrections.',
      action: {
        label: 'Mettre à jour',
        onClick: () => {
          if (mutating > 0) {
            toast.info('Une action est en cours : la mise à jour se fera juste après.')
            return
          }
          void updateServiceWorker(true)
        },
      },
      cancel: { label: 'Plus tard', onClick: () => setNeedRefresh(false) },
    })
  }, [needRefresh, mutating, updateServiceWorker, setNeedRefresh])

  return null
}
