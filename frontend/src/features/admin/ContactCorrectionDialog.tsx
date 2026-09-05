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
import { Input, Textarea } from '@/components/ui/input'
import { useUpdateUserContact } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'
import { formatPhone } from '@/lib/format'
import { optionalEmailSchema, phoneSchema, toE164 } from '@/lib/validation'
import type { AdminUserResponse } from '@/api/extended'

interface ContactCorrectionDialogProps {
  user: AdminUserResponse | null
  onOpenChange: (open: boolean) => void
}

/**
 * Correction du contact d'un utilisateur par la moderation (e-mail et/ou numero),
 * pour un compte dont l'adresse est perdue ou erronee. Reserve aux demandes dont
 * l'identite a ete verifiee hors ligne : le motif est obligatoire et journalise, le
 * contact corrige repart non verifie et les sessions de l'utilisateur sont fermees.
 */
export function ContactCorrectionDialog({ user, onOpenChange }: ContactCorrectionDialogProps) {
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<{ email?: string; phone?: string; reason?: string }>({})
  const update = useUpdateUserContact()

  const reset = () => {
    setEmail('')
    setPhone('')
    setReason('')
    setErrors({})
  }

  const close = (open: boolean) => {
    if (update.isPending) return
    if (!open) reset()
    onOpenChange(open)
  }

  const submit = () => {
    if (!user) return
    const next: typeof errors = {}
    const parsedEmail = optionalEmailSchema.safeParse(email.trim())
    if (!parsedEmail.success) next.email = 'Adresse e-mail invalide'
    if (phone.trim()) {
      const parsedPhone = phoneSchema.safeParse(phone)
      if (!parsedPhone.success) next.phone = parsedPhone.error.issues[0]?.message ?? 'Numéro invalide'
    }
    if (!email.trim() && !phone.trim()) next.email = 'Indiquez un nouvel e-mail ou un nouveau numéro'
    if (reason.trim().length < 10) next.reason = 'Motif obligatoire (10 caractères minimum)'
    setErrors(next)
    if (Object.keys(next).length > 0) return

    update.mutate(
      {
        id: user.id,
        email: email.trim() || undefined,
        phone: phone.trim() ? (toE164(phone) ?? phone.trim()) : undefined,
        reason: reason.trim(),
      },
      {
        onSuccess: () => {
          toast.success(`Contact de ${user.firstName} ${user.lastName} corrigé`, {
            description: "Ses sessions ont été fermées ; le nouveau contact sera vérifié à sa prochaine connexion.",
          })
          reset()
          onOpenChange(false)
        },
        onError: (error) => {
          if (error instanceof ApiError && (error.status === 409 || error.status === 400)) {
            setErrors({ email: error.message })
            return
          }
          toast.error(describeError(error, "La correction n'a pas abouti. Réessayez."))
        },
      },
    )
  }

  return (
    <Dialog open={user !== null} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Corriger le contact</DialogTitle>
          <DialogDescription>
            {user
              ? `${user.firstName} ${user.lastName} · ${formatPhone(user.phone)}${user.email ? ` · ${user.email}` : ''}. À n'utiliser qu'après avoir vérifié l'identité du demandeur (pièce, rappel au numéro connu).`
              : null}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <Input
            label="Nouvelle adresse e-mail"
            type="email"
            inputMode="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            error={errors.email}
            hint="Laissez vide pour ne pas la changer."
            placeholder="nouvelle@exemple.com"
          />
          <Input
            label="Nouveau numéro"
            type="tel"
            inputMode="tel"
            value={phone}
            onChange={(event) => setPhone(event.target.value)}
            error={errors.phone}
            hint="Laissez vide pour ne pas le changer. Format +229 01 XX XX XX XX."
            placeholder="+229 01 97 00 00 00"
          />
          <Textarea
            label="Motif"
            hint="Obligatoire. Conservé dans le journal d'audit avec l'ancien et le nouveau contact."
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            error={errors.reason}
            placeholder="Demande reçue le … ; identité vérifiée par …"
          />
        </div>
        <DialogFooter>
          <Button type="button" variant="ghost" onClick={() => close(false)} disabled={update.isPending}>
            Annuler
          </Button>
          <Button type="button" loading={update.isPending} onClick={submit}>
            Corriger le contact
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
