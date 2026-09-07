import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm } from 'react-hook-form'
import { SelectField } from '@/components/forms/SelectField'
import { Input } from '@/components/ui/input'
import { DOCUMENT_OPTIONS } from '@/lib/labels'
import { identitySchema, type IdentityValues } from '@/lib/validation'

export const IDENTITY_FORM_ID = 'identity-form'

export function IdentityForm({ onSubmit }: { onSubmit: (values: IdentityValues) => void }) {
  const form = useForm<IdentityValues>({
    resolver: zodResolver(identitySchema),
    defaultValues: { documentType: 'CNI', documentNumber: '' },
    mode: 'onTouched',
  })
  const { errors } = form.formState

  return (
    <form
      id={IDENTITY_FORM_ID}
      onSubmit={form.handleSubmit((values) => onSubmit({ ...values, documentNumber: values.documentNumber.toUpperCase() }))}
      noValidate
      className="space-y-3 py-2"
    >
      <Controller
        control={form.control}
        name="documentType"
        render={({ field }) => (
          <SelectField
            label="Type de document"
            value={field.value}
            onValueChange={field.onChange}
            options={DOCUMENT_OPTIONS}
            error={errors.documentType?.message}
          />
        )}
      />
      <Input
        label="Numéro du document"
        className="tnum uppercase"
        autoCapitalize="characters"
        autoComplete="off"
        error={errors.documentNumber?.message}
        {...form.register('documentNumber')}
      />
      {/* Les photos de la piece se deposent a l'etape suivante (IdentityDocumentsForm), une fois le dossier cree. */}
      {/* Aucun delai chiffre : la promesse serait intenable (audits F254, F323). */}
      <p className="rounded-[var(--radius-control)] bg-surface-2 px-3 py-2.5 text-label leading-relaxed text-ink-2">
        Après cette étape, vous ajouterez les photos de votre pièce (recto, verso, selfie). Un agent Ekuiseo contrôle
        ensuite votre dossier ; vous serez prévenu par notification à la fin du contrôle.
      </p>
    </form>
  )
}
