import { Check, Mail, UserCog } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, RatingStars } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { useUpdateProfile } from '@/hooks/useAccount'
import { describeError } from '@/lib/errors'
import { formatPhone } from '@/lib/format'
import type { IdentityVerificationStatus } from '@/api/extended'
import type { UserResponse } from '@/api/types'
import { EmailChangeDialog } from './EmailChangeDialog'
import { PROFILE_FORM_ID, ProfileForm } from './forms/ProfileForm'
import { IDENTITY_PRESENTATION } from './identity'

/** Carte d'identite du compte : qui je suis, ou j'en suis, et la porte vers la modification. */
export function AccountHeaderCard({
  user,
  identityStatus,
}: {
  user: UserResponse
  /** Absent tant que l'etat n'est pas connu (chargement ou erreur) : aucun badge n'est alors affiche. */
  identityStatus?: IdentityVerificationStatus
}) {
  const [open, setOpen] = useState(false)
  const [emailOpen, setEmailOpen] = useState(false)
  const updateProfile = useUpdateProfile()
  const identity = identityStatus ? IDENTITY_PRESENTATION[identityStatus] : null
  const IdentityIcon = identity?.icon

  return (
    <Card className="p-5">
      <div className="flex items-start gap-4">
        <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={64} />
        <div className="min-w-0 flex-1">
          <h2 className="font-display text-heading font-extrabold tracking-[-0.03em]">
            {user.firstName} {user.lastName}
          </h2>
          <p className="tnum text-body text-muted">{formatPhone(user.phone)}</p>
          {user.ratingCount > 0 ? (
            <RatingStars value={user.ratingAvg} count={user.ratingCount} className="mt-1" />
          ) : null}
        </div>
        <Button variant="secondary" size="sm" onClick={() => setOpen(true)} aria-label="Modifier mon profil">
          <UserCog className="size-4" aria-hidden />
          <span className="hidden sm:inline">Modifier</span>
        </Button>
      </div>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {user.emailVerified ? (
          <Badge tone="success">
            <Check aria-hidden />
            E-mail confirmé
          </Badge>
        ) : null}
        {user.phoneVerified ? (
          <Badge tone="success">
            <Check aria-hidden />
            Téléphone confirmé
          </Badge>
        ) : (
          <Badge tone="warning">Téléphone non confirmé</Badge>
        )}
        {identity && IdentityIcon ? (
          <Badge tone={identity.tone}>
            <IdentityIcon aria-hidden />
            {identity.label}
          </Badge>
        ) : null}
      </div>
      {user.bio ? <p className="mt-3 text-body leading-relaxed text-ink-2">{user.bio}</p> : null}

      <div className="mt-4 flex flex-wrap items-center justify-between gap-2 rounded-lg bg-[var(--surface-2)] px-3 py-2">
        <span className="flex min-w-0 items-center gap-2 text-[13px] text-ink-2">
          <Mail className="size-4 shrink-0 text-muted" aria-hidden />
          <span className="truncate">{user.email ?? 'Aucune adresse e-mail'}</span>
        </span>
        <Button variant="ghost" size="sm" onClick={() => setEmailOpen(true)}>
          {user.email ? "Changer l'adresse" : 'Ajouter une adresse'}
        </Button>
      </div>
      <p className="mt-1.5 text-[12px] text-muted">
        Vos codes de connexion sont envoyés à cette adresse. Tout changement est confirmé par un code reçu sur la
        nouvelle adresse.
      </p>

      <EmailChangeDialog open={emailOpen} onOpenChange={setEmailOpen} currentEmail={user.email} />

      <Sheet
        open={open}
        onOpenChange={setOpen}
        title="Modifier mon profil"
        description="Ces informations sont visibles par vos passagers et conducteurs."
        footer={
          <Button type="submit" form={PROFILE_FORM_ID} size="lg" block loading={updateProfile.isPending}>
            Enregistrer
          </Button>
        }
      >
        <ProfileForm
          initial={{
            firstName: user.firstName,
            lastName: user.lastName,
            bio: user.bio ?? '',
          }}
          onSubmit={(values) =>
            updateProfile.mutate(
              {
                firstName: values.firstName,
                lastName: values.lastName,
                bio: values.bio || null,
              },
              {
                onSuccess: () => {
                  setOpen(false)
                  toast.success('Profil mis à jour')
                },
                onError: (error) => toast.error(describeError(error, "La mise à jour n'a pas abouti. Réessayez.")),
              },
            )
          }
        />
      </Sheet>
    </Card>
  )
}
