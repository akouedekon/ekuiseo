import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input, Textarea } from '@/components/ui/input'
import { SettingRow, Stepper, Switch } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { useUpdateTrip } from '@/hooks/useTrips'
import { describeError } from '@/lib/errors'
import type { TripResponse } from '@/api/types'

const FORM_ID = 'edit-trip-form'

const schema = z.object({
  date: z.string().min(1, 'Choisissez une date'),
  time: z.string().min(1, 'Choisissez une heure'),
  seatsTotal: z.number().min(1).max(8),
  pricePerSeat: z.number().min(100, 'Prix trop bas').max(100_000, 'Prix trop élevé'),
  instantBooking: z.boolean(),
  luggagePolicy: z.string().max(120).optional(),
  description: z.string().max(400).optional(),
})

type Values = z.infer<typeof schema>

function toLocalDate(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function toLocalTime(iso: string): string {
  const d = new Date(iso)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

/**
 * Modification d'un trajet publie (PATCH /api/v1/trips/{id}) : horaire, places,
 * prix, conditions. L'itineraire ne se modifie pas ici - un changement de
 * ville est un autre trajet pour les passagers deja inscrits.
 */
export function EditTripSheet({
  trip,
  open,
  onOpenChange,
}: {
  trip: TripResponse
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const update = useUpdateTrip()
  const booked = trip.seatsTotal - trip.seatsAvailable
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues: {
      date: toLocalDate(trip.departureAt),
      time: toLocalTime(trip.departureAt),
      seatsTotal: trip.seatsTotal,
      pricePerSeat: trip.pricePerSeat,
      instantBooking: trip.instantBooking,
      luggagePolicy: trip.luggagePolicy ?? '',
      description: trip.description ?? '',
    },
  })

  const submit = form.handleSubmit((values) => {
    const departureAt = new Date(`${values.date}T${values.time}:00`)
    if (departureAt.getTime() <= Date.now()) {
      form.setError('date', { message: 'Le départ doit être dans le futur' })
      return
    }
    if (values.seatsTotal < booked) {
      form.setError('seatsTotal', { message: `${booked} place(s) déjà réservée(s)` })
      return
    }
    update.mutate(
      {
        id: trip.id,
        input: {
          departureAt: departureAt.toISOString(),
          seatsTotal: values.seatsTotal,
          pricePerSeat: values.pricePerSeat,
          instantBooking: values.instantBooking,
          luggagePolicy: values.luggagePolicy?.trim() || null,
          description: values.description?.trim() || null,
        },
      },
      {
        onSuccess: () => {
          onOpenChange(false)
          toast.success('Trajet mis à jour', { description: 'Les passagers voient les nouvelles conditions.' })
        },
        onError: (error) => toast.error(describeError(error, "La modification n'a pas abouti.")),
      },
    )
  })

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => !update.isPending && onOpenChange(next)}
      title="Modifier le trajet"
      description={`${trip.originLabel} → ${trip.destLabel}`}
      footer={
        <Button type="submit" form={FORM_ID} size="lg" block loading={update.isPending}>
          Enregistrer
        </Button>
      }
    >
      <form id={FORM_ID} onSubmit={submit} noValidate className="space-y-4 py-2">
        <div className="grid grid-cols-2 gap-3">
          <Input label="Date" type="date" error={form.formState.errors.date?.message} {...form.register('date')} />
          <Input label="Heure" type="time" error={form.formState.errors.time?.message} {...form.register('time')} />
        </div>

        <Controller
          control={form.control}
          name="seatsTotal"
          render={({ field, fieldState }) => (
            <div>
              <div className="flex min-h-11 items-center justify-between gap-4">
                <span className="text-[14px] font-medium">Places proposées</span>
                <Stepper value={field.value} onChange={field.onChange} min={Math.max(1, booked)} max={8} label="places" />
              </div>
              <p className="mt-1 text-[12px] text-muted">
                {booked > 0 ? `${booked} place(s) déjà réservée(s) : impossible de descendre en dessous.` : 'Jusqu’à 8 places.'}
              </p>
              {fieldState.error ? (
                <p className="mt-1 text-[12px] font-medium text-[var(--vermillon)]">{fieldState.error.message}</p>
              ) : null}
            </div>
          )}
        />

        <Input
          label="Prix par place (FCFA)"
          type="number"
          inputMode="numeric"
          step={100}
          error={form.formState.errors.pricePerSeat?.message}
          hint={booked > 0 ? 'Les réservations déjà confirmées gardent leur prix.' : undefined}
          {...form.register('pricePerSeat', { valueAsNumber: true })}
        />

        <Controller
          control={form.control}
          name="instantBooking"
          render={({ field }) => (
            <SettingRow title="Réservation immédiate" description="Les passagers réservent sans attendre votre accord">
              <Switch checked={field.value} onCheckedChange={field.onChange} aria-label="Réservation immédiate" />
            </SettingRow>
          )}
        />

        <Input label="Politique bagages" placeholder="1 bagage cabine" {...form.register('luggagePolicy')} />
        <Textarea
          label="Précisions pour les passagers"
          hint="400 caractères maximum"
          rows={3}
          {...form.register('description')}
        />
      </form>
    </Sheet>
  )
}
