import { Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { OtpInput } from '@/components/ui/otp-input'
import { SectionTitle } from '@/components/layout/PageContainer'
import { useConfirmAccountDeletion, useRequestAccountDeletion } from '@/hooks/useAccount'
import { resetSession } from '@/hooks/useAuth'
import { describeError } from '@/lib/errors'

const CODE_LENGTH = 6

/**
 * Suppression du compte (droit a l'effacement) en deux temps : une confirmation
 * explicite de ce qui est conserve et de ce qui disparait, puis un code recu par
 * e-mail. Rien n'est modifie tant que le code n'est pas accepte par le serveur.
 * Un trajet publie ou une reservation en cours bloque la demande (409, message
 * du serveur affiche tel quel).
 */
export function DeleteAccountSection({ email }: { email: string | null }) {
  const navigate = useNavigate()
  const request = useRequestAccountDeletion()
  const confirm = useConfirmAccountDeletion()
  const [step, setStep] = useState<'closed' | 'explain' | 'code'>('closed')
  const [code, setCode] = useState('')
  const [codeError, setCodeError] = useState<string>()
  const busy = request.isPending || confirm.isPending

  const close = () => {
    if (busy) return
    setStep('closed')
    setCode('')
    setCodeError(undefined)
  }

  const sendCode = () => {
    request.mutate(undefined, {
      onSuccess: () => setStep('code'),
      onError: (error) =>
        toast.error(describeError(error, "La demande n'a pas pu être enregistrée. Réessayez.")),
    })
  }

  const confirmDeletion = (value: string) => {
    if (value.length < CODE_LENGTH) {
      setCodeError(`Le code comporte ${CODE_LENGTH} chiffres`)
      return
    }
    setCodeError(undefined)
    confirm.mutate(value, {
      onSuccess: () => {
        // La session locale est videe comme a une deconnexion : rien de ce compte ne reste sur l'appareil.
        resetSession('logout')
        navigate('/', { replace: true })
        toast.success('Votre compte a été supprimé', {
          description: 'Votre profil est anonymisé. Merci d’avoir voyagé avec Ekuiseo.',
        })
      },
      onError: (error) => {
        setCodeError(describeError(error, 'Code incorrect ou expiré'))
        setCode('')
      },
    })
  }

  return (
    <section aria-label="Supprimer mon compte">
      <SectionTitle className="mt-5">Supprimer mon compte</SectionTitle>
      <Card className="p-4">
        <p className="text-body text-ink-2">
          Votre profil est anonymisé : nom, numéro, e-mail, photo et comptes mobile money sont effacés et vous ne
          pourrez plus vous connecter. Vos réservations, paiements et avis sont conservés, sans lien avec votre
          identité, pour la comptabilité et les autres voyageurs.
        </p>
        <p className="mt-2 text-label text-muted">
          Impossible tant qu'un trajet publié ou une réservation est en cours. Un code de confirmation vous sera
          envoyé{email ? ` à ${email}` : ' par e-mail'}.
        </p>
        <Button variant="ghost" className="mt-3 text-[var(--vermillon)]" onClick={() => setStep('explain')}>
          <Trash2 className="size-4" aria-hidden />
          Supprimer mon compte
        </Button>
      </Card>

      <ConfirmDialog
        open={step !== 'closed'}
        onOpenChange={(open) => !open && close()}
        title={step === 'code' ? 'Confirmez avec le code reçu' : 'Supprimer définitivement votre compte ?'}
        description={
          step === 'code'
            ? `Code envoyé${email ? ` à ${email}` : ''}. Il expire dans quelques minutes ; pensez au dossier Spam. Une fois le code accepté, la suppression est immédiate et irréversible.`
            : 'Vous serez déconnecté de tous vos appareils. Vos réservations, paiements et avis restent enregistrés de façon anonyme ; votre profil, vos contacts, vos comptes mobile money, vos alertes et vos notifications sont effacés. Cette action est irréversible.'
        }
        tone="danger"
        confirmLabel={step === 'code' ? 'Supprimer définitivement' : 'Recevoir le code'}
        cancelLabel="Garder mon compte"
        confirmDisabled={step === 'code' && code.length < CODE_LENGTH}
        loading={busy}
        onConfirm={step === 'code' ? () => confirmDeletion(code) : sendCode}
      >
        {step === 'code' ? (
          <div className="space-y-2">
            <OtpInput
              value={code}
              length={CODE_LENGTH}
              label="Code de confirmation"
              onChange={(value) => {
                setCode(value)
                if (codeError) setCodeError(undefined)
              }}
              error={!!codeError}
              disabled={confirm.isPending}
            />
            {codeError ? (
              <p role="alert" className="text-center text-[13px] font-medium text-[var(--vermillon)]">
                {codeError}
              </p>
            ) : null}
          </div>
        ) : null}
      </ConfirmDialog>
    </section>
  )
}
