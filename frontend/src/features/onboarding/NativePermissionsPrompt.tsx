import { Bell, MapPin } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useIsAuthenticated } from '@/hooks/useAuth'
import { isNativeApp } from '@/lib/native'
import { registerNativePush, saveNativeToken } from '@/lib/pushNative'

const DONE_KEY = 'ekuiseo.native.permissionsAsked'

function alreadyAsked(): boolean {
  try {
    return localStorage.getItem(DONE_KEY) === '1'
  } catch {
    return false
  }
}

function markAsked(): void {
  try {
    localStorage.setItem(DONE_KEY, '1')
  } catch {
    /* stockage indisponible : on redemandera au prochain lancement */
  }
}

/**
 * Premier lancement de l application Android/iOS : une seule fenetre explique pourquoi
 * la position est utile (departs autour de vous, suivi du vehicule) et demande la
 * permission ; les notifications sont proposees dans le meme geste quand une session
 * est ouverte. Jamais affichee dans un navigateur, ni deux fois.
 */
export function NativePermissionsPrompt() {
  const authed = useIsAuthenticated()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!isNativeApp() || alreadyAsked()) return
    // Un court delai : la fenetre ne doit pas couvrir l ecran de demarrage.
    const timer = window.setTimeout(() => setOpen(true), 1_200)
    return () => window.clearTimeout(timer)
  }, [])

  const close = () => {
    markAsked()
    setOpen(false)
  }

  const allow = async () => {
    setBusy(true)
    try {
      const { Geolocation } = await import('@capacitor/geolocation')
      const status = await Geolocation.requestPermissions({ permissions: ['location'] })
      if (status.location === 'denied') {
        toast.info('Position refusée : vous pourrez l’activer plus tard dans les réglages du téléphone.')
      }
      if (authed) {
        const token = await registerNativePush()
        if (token) await saveNativeToken(token)
      }
    } catch {
      /* greffon absent ou permission indisponible : l application reste utilisable */
    } finally {
      setBusy(false)
      close()
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !next && !busy && close()}>
      <DialogContent hideClose className="max-w-sm">
        <DialogHeader>
          <DialogTitle>Bienvenue sur Ekuiseo</DialogTitle>
          <DialogDescription>Deux autorisations rendent l’application vraiment utile.</DialogDescription>
        </DialogHeader>
        <ul className="space-y-3 text-label text-ink-2">
          <li className="flex items-start gap-3">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-primary-soft text-primary-ink">
              <MapPin className="size-5" aria-hidden />
            </span>
            <span>
              <span className="block font-semibold text-ink">Votre position</span>
              Pour voir les départs autour de vous, remplir votre point de départ et suivre le véhicule en direct. Jamais en
              arrière-plan.
            </span>
          </li>
          <li className="flex items-start gap-3">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-accent-soft text-accent-ink">
              <Bell className="size-5" aria-hidden />
            </span>
            <span>
              <span className="block font-semibold text-ink">Les notifications</span>
              Réservation confirmée, message du conducteur, rappel de départ.
              {authed ? '' : ' Proposées une fois connecté.'}
            </span>
          </li>
        </ul>
        <DialogFooter className="mt-2 flex-col gap-2 sm:flex-col">
          <Button size="lg" block onClick={() => void allow()} loading={busy}>
            Autoriser
          </Button>
          <Button variant="ghost" block onClick={close} disabled={busy}>
            Plus tard
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
