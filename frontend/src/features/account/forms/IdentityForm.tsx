import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm } from 'react-hook-form'
import { SelectField } from '@/components/forms/SelectField'
import { Input } from '@/components/ui/input'
import { identitySchema, type IdentityValues } from '@/lib/validation'
import { DOCUMENT_OPTIONS } from '../identity'

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
      {/* TODO(backend) : ajouter le televersement de la photo du document
          (POST /api/v1/me/identity, multipart) quand le stockage sera en place. */}
      <p className="rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2.5 text-label leading-relaxed text-ink-2">
        Un agent Ekuiseo vérifie votre document sous 24 à 48 h. Vous recevrez une notification à la fin du contrôle.
      </p>
    </form>
  )
}
