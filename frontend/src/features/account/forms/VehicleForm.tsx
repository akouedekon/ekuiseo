import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm } from 'react-hook-form'
import { SelectField } from '@/components/forms/SelectField'
import { Input } from '@/components/ui/input'
import { vehicleSchema, type VehicleValues } from '@/lib/validation'

export const VEHICLE_FORM_ID = 'vehicle-form'

const COMFORT_OPTIONS = [
  { value: 'BASIC', label: 'Confort simple' },
  { value: 'COMFORT', label: 'Confortable (climatisé)' },
  { value: 'PREMIUM', label: 'Haut de gamme' },
] as const

const DEFAULTS: VehicleValues = {
  brand: '',
  model: '',
  color: '',
  plate: '',
  seats: 4,
  comfortLevel: 'COMFORT',
}

export function VehicleForm({ onSubmit }: { onSubmit: (values: VehicleValues) => void }) {
  const form = useForm<VehicleValues>({
    resolver: zodResolver(vehicleSchema),
    defaultValues: DEFAULTS,
    mode: 'onTouched',
  })
  const { errors } = form.formState

  return (
    <form
      id={VEHICLE_FORM_ID}
      onSubmit={form.handleSubmit((values) => onSubmit({ ...values, plate: values.plate.toUpperCase() }))}
      noValidate
      className="space-y-3 py-2"
    >
      <div className="grid grid-cols-2 gap-3">
        <Input label="Marque" placeholder="Toyota" error={errors.brand?.message} {...form.register('brand')} />
        <Input label="Modèle" placeholder="Corolla" error={errors.model?.message} {...form.register('model')} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Input label="Couleur" placeholder="Gris" error={errors.color?.message} {...form.register('color')} />
        <Input
          label="Immatriculation"
          placeholder="AB 1234 RB"
          className="tnum uppercase"
          autoCapitalize="characters"
          error={errors.plate?.message}
          {...form.register('plate')}
        />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Input
          label="Places (hors conducteur)"
          type="number"
          inputMode="numeric"
          min={1}
          max={8}
          className="tnum"
          error={errors.seats?.message}
          {...form.register('seats', { valueAsNumber: true })}
        />
        <Controller
          control={form.control}
          name="comfortLevel"
          render={({ field }) => (
            <SelectField
              label="Confort"
              value={field.value}
              onValueChange={field.onChange}
              options={COMFORT_OPTIONS}
              error={errors.comfortLevel?.message}
            />
          )}
        />
      </div>
    </form>
  )
}
