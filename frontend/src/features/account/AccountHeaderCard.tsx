import { Check, UserCog } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, RatingStars } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { useUpdateProfile } from '@/hooks/useAccount'
import { formatPhone } from '@/lib/format'
import type { IdentityVerificationStatus } from '@/api/extended'
import type { UserResponse } from '@/api/types'
import { PROFILE_FORM_ID, ProfileForm } from './forms/ProfileForm'
import { IDENTITY_PRESENTATION } from './identity'

/** Carte d'identite du compte : qui je suis, ou j'en suis, et la porte vers la modification. */
export function AccountHeaderCard({
  user,
  identityStatus,
}: {
  user: UserResponse
  identityStatus: IdentityVerificationStatus
}) {
  const [open, setOpen] = useState(false)
  const updateProfile = useUpdateProfile()
  const identity = IDENTITY_PRESENTATION[identityStatus]
  const IdentityIcon = identity.icon

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
        {user.phoneVerified ? (
          <Badge tone="success">
            <Check aria-hidden />
            Téléphone confirmé
          </Badge>
        ) : (
          <Badge tone="warning">Téléphone non confirmé</Badge>
        )}
        <Badge tone={identity.tone}>
          <IdentityIcon aria-hidden />
          {identity.label}
        </Badge>
      </div>
      {user.bio ? <p className="mt-3 text-body leading-relaxed text-ink-2">{user.bio}</p> : null}

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
            email: user.email ?? '',
            bio: user.bio ?? '',
          }}
          onSubmit={(values) =>
            updateProfile.mutate(
              {
                firstName: values.firstName,
                lastName: values.lastName,
                email: values.email || null,
                bio: values.bio || null,
              },
              {
                onSuccess: () => {
                  setOpen(false)
                  toast.success('Profil mis à jour')
                },
                onError: () => toast.error("La mise à jour n'a pas abouti. Réessayez."),
              },
            )
          }
        />
      </Sheet>
    </Card>
  )
}
