import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { SelectField } from '@/components/forms/SelectField'
import { Input } from '@/components/ui/input'
import { COMFORT_OPTIONS, VEHICLE_TYPE_MAX_SEATS, VEHICLE_TYPE_OPTIONS } from '@/lib/labels'
import { vehicleSchema, type VehicleValues } from '@/lib/validation'

export const VEHICLE_FORM_ID = 'vehicle-form'

const DEFAULTS: VehicleValues = {
  vehicleType: 'CAR',
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
  const vehicleType = useWatch({ control: form.control, name: 'vehicleType' })
  const maxSeats = VEHICLE_TYPE_MAX_SEATS[vehicleType] ?? 8
  // Exemples adaptes au type : Haojue et Bajaj sont les marques les plus courantes au Benin.
  const placeholders =
    vehicleType === 'MOTO' ? { brand: 'Haojue', model: 'HJ 125' } : vehicleType === 'TRICYCLE' ? { brand: 'Bajaj', model: 'RE' } : { brand: 'Toyota', model: 'Corolla' }

  return (
    <form
      id={VEHICLE_FORM_ID}
      onSubmit={form.handleSubmit((values) => onSubmit({ ...values, plate: values.plate.toUpperCase() }))}
      noValidate
      className="space-y-3 py-2"
    >
      <Controller
        control={form.control}
        name="vehicleType"
        render={({ field }) => (
          <SelectField
            label="Type de véhicule"
            value={field.value}
            onValueChange={(value) => {
              field.onChange(value)
              // Une moto n a qu une place : le nombre de places suit le type choisi.
              const max = VEHICLE_TYPE_MAX_SEATS[value as VehicleValues['vehicleType']] ?? 8
              if (form.getValues('seats') > max) form.setValue('seats', max, { shouldValidate: true })
            }}
            options={VEHICLE_TYPE_OPTIONS}
            error={errors.vehicleType?.message}
          />
        )}
      />
      <div className="grid grid-cols-2 gap-3">
        <Input label="Marque" placeholder={placeholders.brand} error={errors.brand?.message} {...form.register('brand')} />
        <Input label="Modèle" placeholder={placeholders.model} error={errors.model?.message} {...form.register('model')} />
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
          max={maxSeats}
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
