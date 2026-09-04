import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Input, Textarea } from '@/components/ui/input'
import { profileSchema, type ProfileValues } from '@/lib/validation'

export const PROFILE_FORM_ID = 'profile-form'

/**
 * Formulaire de profil. Le bouton d'envoi vit dans le pied de la feuille
 * (attribut `form={PROFILE_FORM_ID}`), ce qui laisse le contenu defiler.
 */
export function ProfileForm({
  initial,
  onSubmit,
}: {
  initial: ProfileValues
  onSubmit: (values: ProfileValues) => void
}) {
  const form = useForm<ProfileValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: initial,
    mode: 'onTouched',
  })
  const { errors } = form.formState

  return (
    <form id={PROFILE_FORM_ID} onSubmit={form.handleSubmit(onSubmit)} noValidate className="space-y-3 py-2">
      <div className="grid gap-3 sm:grid-cols-2">
        <Input
          label="Prénom"
          autoComplete="given-name"
          error={errors.firstName?.message}
          {...form.register('firstName')}
        />
        <Input label="Nom" autoComplete="family-name" error={errors.lastName?.message} {...form.register('lastName')} />
      </div>
      <Input
        label="E-mail (facultatif)"
        type="email"
        inputMode="email"
        autoComplete="email"
        hint="Pour recevoir vos reçus et récapitulatifs."
        error={errors.email?.message}
        {...form.register('email')}
      />
      <Textarea
        label="Présentation"
        hint="Visible par les passagers sur votre profil public. 300 caractères maximum."
        error={errors.bio?.message}
        {...form.register('bio')}
      />
    </form>
  )
}
