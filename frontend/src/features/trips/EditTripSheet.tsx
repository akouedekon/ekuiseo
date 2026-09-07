import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FieldError, Input, Textarea } from '@/components/ui/input'
import { SettingRow, Stepper, Switch } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { useUpdateTrip } from '@/hooks/useTrips'
import { describeError } from '@/lib/errors'
import { BENIN_TIME_HINT, deviceClockDiffersFromBenin, toInputDate, toInputTime } from '@/lib/format'
import { MIN_DEPARTURE_LEAD_MS, departureFromFields } from '@/lib/validation'
import type { TripResponse } from '@/api/types'

const FORM_ID = 'edit-trip-form'

const schema = z
  .object({
    date: z.string().min(1, 'Choisissez une date'),
    time: z.string().min(1, 'Choisissez une heure'),
    seatsTotal: z.number().min(1).max(8),
    pricePerSeat: z.number().min(100, 'Prix trop bas').max(100_000, 'Prix trop élevé'),
    luggagePolicy: z.string().max(120, '120 caractères maximum'),
    description: z.string().max(400, '400 caractères maximum'),
    instantBooking: z.boolean(),
  })
  .superRefine((values, ctx) => {
    const departure = departureFromFields(values.date, values.time)
    if (departure && departure.getTime() < Date.now() + MIN_DEPARTURE_LEAD_MS) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['time'], message: 'Le départ doit être dans au moins 15 minutes' })
    }
  })

type Values = z.infer<typeof schema>

/**
 * Modification d'un trajet publie (PATCH /api/v1/trips/{id}) : horaire, places,
 * prix, conditions. L'itineraire ne se modifie pas ici - un changement de
 * ville est un autre trajet pour les passagers deja inscrits.
 *
 * Effacement : un champ texte vide est envoye comme chaine vide (""), que le
 * serveur traite comme « retirer » ; `null` serait ignore par le PATCH (audit F224).
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
  const template = trip.status === 'TEMPLATE'
  const clockDiffers = deviceClockDiffersFromBenin()
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues: {
      // Champs saisis et affiches en heure du Benin (audit F424).
      date: toInputDate(trip.departureAt),
      time: toInputTime(trip.departureAt),
      seatsTotal: trip.seatsTotal,
      pricePerSeat: trip.pricePerSeat,
      luggagePolicy: trip.luggagePolicy ?? '',
      description: trip.description ?? '',
      instantBooking: trip.instantBooking,
    },
  })

  const submit = form.handleSubmit((values) => {
    const departureAt = departureFromFields(values.date, values.time)
    if (!departureAt) {
      form.setError('date', { message: 'Date ou heure invalide' })
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
          luggagePolicy: values.luggagePolicy.trim(),
          description: values.description.trim(),
          instantBooking: values.instantBooking,
        },
      },
      {
        onSuccess: () => {
          onOpenChange(false)
          toast.success(template ? 'Navette mise à jour' : 'Trajet mis à jour', {
            description: 'Les passagers voient les nouvelles conditions.',
          })
        },
        onError: (error) => toast.error(describeError(error, "La modification n'a pas abouti.")),
      },
    )
  })

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => !update.isPending && onOpenChange(next)}
      title={template ? 'Modifier la navette' : 'Modifier le trajet'}
      description={`${trip.originLabel} → ${trip.destLabel}`}
      footer={
        <Button type="submit" form={FORM_ID} size="lg" block loading={update.isPending}>
          Enregistrer
        </Button>
      }
    >
      <form id={FORM_ID} onSubmit={submit} noValidate className="space-y-4 py-2">
        <div className="grid grid-cols-2 gap-3">
          <Input
            label={template ? 'Premier départ' : 'Date'}
            type="date"
            error={form.formState.errors.date?.message}
            {...form.register('date')}
          />
          <Input
            label={clockDiffers ? `Heure (${BENIN_TIME_HINT})` : 'Heure'}
            type="time"
            hint={booked > 0 ? 'Changer l’horaire rouvre 24 h d’annulation gratuite aux passagers.' : undefined}
            error={form.formState.errors.time?.message}
            {...form.register('time')}
          />
        </div>

        <Controller
          control={form.control}
          name="seatsTotal"
          render={({ field, fieldState }) => (
            <div>
              <div className="flex min-h-11 items-center justify-between gap-4">
                <span className="text-body font-medium">Places proposées</span>
                <Stepper
                  value={field.value}
                  onChange={field.onChange}
                  min={Math.max(1, booked)}
                  max={8}
                  label="places"
                  decrementLabel="Une place de moins"
                  incrementLabel="Une place de plus"
                />
              </div>
              <p className="mt-1 text-caption text-muted">
                {booked > 0 ? `${booked} place(s) déjà réservée(s) : impossible de descendre en dessous.` : 'Jusqu’à 8 places.'}
              </p>
              {fieldState.error ? <FieldError>{fieldState.error.message}</FieldError> : null}
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

        <Input
          label="Politique bagages"
          placeholder="1 bagage cabine"
          hint="Laissez vide pour retirer la mention."
          error={form.formState.errors.luggagePolicy?.message}
          {...form.register('luggagePolicy')}
        />
        <Textarea
          label="Précisions pour les passagers"
          hint="400 caractères maximum. Laissez vide pour retirer le texte."
          rows={3}
          error={form.formState.errors.description?.message}
          {...form.register('description')}
        />

        <Controller
          control={form.control}
          name="instantBooking"
          render={({ field }) => (
            <SettingRow
              title="Réservation immédiate"
              description={
                field.value
                  ? "Une place est confirmée dès que l'acompte est reçu (ou immédiatement en espèces)."
                  : 'Acceptez chaque passager avant confirmation : 24 h pour répondre, au plus tard 2 h avant le départ ; sans réponse, le passager est remboursé.'
              }
              className="-mx-1 rounded-[var(--radius-control)] border border-rule px-3"
            >
              <Switch checked={field.value} onCheckedChange={field.onChange} aria-label="Réservation immédiate" />
            </SettingRow>
          )}
        />
      </form>
    </Sheet>
  )
}
