import { useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { OtpInput } from '@/components/ui/otp-input'
import { useConfirmEmailChange, useRequestEmailChange } from '@/hooks/useAccount'
import { describeError } from '@/lib/errors'
import { emailSchema } from '@/lib/validation'

interface EmailChangeDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  currentEmail: string | null
}

/**
 * Changement d'adresse e-mail en deux temps : la nouvelle adresse recoit un code, qui
 * la valide. L'adresse est le canal des codes de connexion : tant que le code n'est
 * pas saisi, rien ne change et l'ancienne adresse reste active.
 */
export function EmailChangeDialog({ open, onOpenChange, currentEmail }: EmailChangeDialogProps) {
  const [step, setStep] = useState<'email' | 'code'>('email')
  const [email, setEmail] = useState('')
  const [emailError, setEmailError] = useState<string>()
  const [code, setCode] = useState('')
  const [codeError, setCodeError] = useState<string>()
  const [destination, setDestination] = useState('')
  const request = useRequestEmailChange()
  const confirm = useConfirmEmailChange()
  const busy = request.isPending || confirm.isPending

  const reset = () => {
    setStep('email')
    setEmail('')
    setEmailError(undefined)
    setCode('')
    setCodeError(undefined)
  }

  const close = (next: boolean) => {
    if (busy) return
    if (!next) reset()
    onOpenChange(next)
  }

  const sendCode = (event?: React.FormEvent) => {
    event?.preventDefault()
    const parsed = emailSchema.safeParse(email)
    if (!parsed.success) {
      setEmailError(parsed.error.issues[0]?.message ?? 'Adresse e-mail invalide')
      return
    }
    if (currentEmail && parsed.data.toLowerCase() === currentEmail.toLowerCase()) {
      setEmailError("C'est déjà l'adresse de votre compte")
      return
    }
    setEmailError(undefined)
    request.mutate(parsed.data, {
      onSuccess: (sent) => {
        setDestination(sent.destination)
        setStep('code')
      },
      onError: (error) => {
        if (error instanceof ApiError && (error.status === 409 || error.status === 400)) {
          setEmailError(error.message)
          return
        }
        toast.error(describeError(error, "Le code n'a pas pu être envoyé. Réessayez."))
      },
    })
  }

  const verify = (value: string) => {
    if (value.length < 6) {
      setCodeError('Le code comporte 6 chiffres')
      return
    }
    setCodeError(undefined)
    confirm.mutate(value, {
      onSuccess: () => {
        toast.success('Adresse e-mail mise à jour', {
          description: 'Vos prochains codes de connexion partiront à cette adresse.',
        })
        reset()
        onOpenChange(false)
      },
      onError: (error) => {
        setCodeError(describeError(error, 'Code incorrect ou expiré'))
        setCode('')
      },
    })
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Changer d'adresse e-mail</DialogTitle>
          <DialogDescription>
            {step === 'email'
              ? "Vos codes de connexion partent à cette adresse : nous vérifions d'abord que vous y avez accès."
              : `Code envoyé à ${destination}. Il expire dans 5 minutes ; pensez au dossier Spam.`}
          </DialogDescription>
        </DialogHeader>

        {step === 'email' ? (
          <form onSubmit={sendCode} noValidate className="space-y-3">
            {currentEmail ? (
              <p className="text-[13px] text-muted">
                Adresse actuelle : <span className="font-medium text-ink">{currentEmail}</span>
              </p>
            ) : null}
            <Input
              label="Nouvelle adresse e-mail"
              type="email"
              inputMode="email"
              autoComplete="email"
              autoFocus
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              error={emailError}
              placeholder="vous@exemple.com"
            />
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={() => close(false)} disabled={busy}>
                Annuler
              </Button>
              <Button type="submit" loading={request.isPending}>
                Recevoir le code
              </Button>
            </DialogFooter>
          </form>
        ) : (
          <div className="space-y-3">
            <OtpInput
              value={code}
              onChange={(value) => {
                setCode(value)
                if (codeError) setCodeError(undefined)
              }}
              onComplete={verify}
              error={!!codeError}
              disabled={confirm.isPending}
            />
            {codeError ? (
              <p role="alert" className="text-center text-[13px] font-medium text-[var(--vermillon)]">
                {codeError}
              </p>
            ) : null}
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={() => setStep('email')} disabled={busy}>
                Modifier l'adresse
              </Button>
              <Button type="button" loading={confirm.isPending} disabled={code.length < 6} onClick={() => verify(code)}>
                Confirmer
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
