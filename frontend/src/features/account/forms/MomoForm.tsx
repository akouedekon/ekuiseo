import { zodResolver } from '@hookform/resolvers/zod'
import { Phone } from 'lucide-react'
import { Controller, useForm } from 'react-hook-form'
import { SelectField } from '@/components/forms/SelectField'
import { Input } from '@/components/ui/input'
import { PROVIDERS } from '@/lib/payments'
import { momoSchema, type MomoValues } from '@/lib/validation'

export const MOMO_FORM_ID = 'momo-form'

const PROVIDER_OPTIONS = PROVIDERS.map((item) => ({ value: item.value, label: `${item.label} (${item.hint})` }))

export function MomoForm({
  defaultPhone,
  onSubmit,
}: {
  defaultPhone: string
  onSubmit: (values: MomoValues) => void
}) {
  const form = useForm<MomoValues>({
    resolver: zodResolver(momoSchema),
    defaultValues: { provider: 'MTN_MOMO', phone: defaultPhone },
    mode: 'onTouched',
  })
  const { errors } = form.formState

  return (
    <form id={MOMO_FORM_ID} onSubmit={form.handleSubmit(onSubmit)} noValidate className="space-y-3 py-2">
      <Controller
        control={form.control}
        name="provider"
        render={({ field }) => (
          <SelectField
            label="Opérateur"
            value={field.value}
            onValueChange={field.onChange}
            options={PROVIDER_OPTIONS}
            error={errors.provider?.message}
          />
        )}
      />
      <Input
        label="Numéro mobile money"
        type="tel"
        inputMode="tel"
        autoComplete="tel"
        className="tnum"
        leading={<Phone />}
        placeholder="+229 01 97 00 00 00"
        hint="Le numéro doit être enregistré chez cet opérateur."
        error={errors.phone?.message}
        {...form.register('phone')}
      />
    </form>
  )
}
