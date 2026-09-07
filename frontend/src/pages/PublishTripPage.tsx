import { zodResolver } from '@hookform/resolvers/zod'
import { AnimatePresence, m } from 'motion/react'
import {
  ArrowLeft,
  ArrowRight,
  CalendarDays,
  Car,
  Check,
  CircleDot,
  Flag,
  Info,
  Plus,
  Repeat,
  Trash2,
  Wallet,
} from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiError } from '@/api/client'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { FieldError, Input, Label, Textarea } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { SegmentedToggle } from '@/components/ui/tabs'
import { Separator, SettingRow, Skeleton, Stepper, Switch } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { ErrorState } from '@/components/ui/states'
import { StepIndicator } from '@/components/feedback/StepIndicator'
import { CityAutocomplete } from '@/components/trip/CityAutocomplete'
import { PageContainer, PageHeader, SectionTitle } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import { VEHICLE_FORM_ID, VehicleForm } from '@/features/account/forms/VehicleForm'
import { useAddVehicle, useMyVehicles } from '@/hooks/useAccount'
import { useCreateTrip } from '@/hooks/useTrips'
import { estimateDurationMinutes, haversineKm, suggestPricePerSeat, type CityOption } from '@/lib/cities'
import { BENIN_TIME_HINT, deviceClockDiffersFromBenin, formatDuration, formatFcfa, toInputDate } from '@/lib/format'
import { describeError } from '@/lib/errors'
import { WEEKDAYS } from '@/lib/labels'
import { estimatePaymentPlan } from '@/lib/payments'
import { MIN_DEPARTURE_LEAD_MS, departureFromFields, nextHalfHour } from '@/lib/validation'
import type { CreateTripRequest, StopRequest, TripType } from '@/api/types'

const PUBLISH_STEPS = ['Trajet', 'Véhicule et prix', 'Options'] as const
const MAX_SEATS = 8

const schema = z
  .object({
    tripType: z.enum(['INTERURBAIN', 'QUOTIDIEN']),
    originLabel: z.string().min(1, 'Indiquez le point de départ'),
    destLabel: z.string().min(1, 'Indiquez la destination'),
    date: z.string().min(1, 'Choisissez une date'),
    time: z.string().min(1, 'Choisissez une heure'),
    weekdays: z.array(z.number()),
    weeksCount: z.number().min(1).max(26),
    vehicleId: z.string().min(1, 'Sélectionnez un véhicule'),
    seatsTotal: z.number().min(1).max(MAX_SEATS),
    pricePerSeat: z.number().min(100, 'Prix trop bas').max(100_000),
    luggagePolicy: z.string().max(120).optional(),
    description: z.string().max(400).optional(),
    /** `false` : le conducteur accepte chaque passager avant confirmation (V19). */
    instantBooking: z.boolean(),
    stops: z.array(
      z.object({
        label: z.string().min(1, 'Choisissez une ville'),
        // Coordonnees posees par l'autocompletion : un arret sans position n'est pas envoye en aveugle.
        lat: z.number({ message: 'Choisissez une ville dans la liste' }),
        lng: z.number({ message: 'Choisissez une ville dans la liste' }),
        priceFromOrigin: z.number({ message: 'Indiquez le prix jusqu’à cet arrêt' }).min(100, 'Prix trop bas (100 FCFA minimum)'),
        /** Heure de passage « HH:MM », facultative ; posterieure au depart et croissante d'un arret a l'autre. */
        time: z.string().regex(/^(\d{2}:\d{2})?$/, 'Heure invalide'),
      }),
    ),
  })
  .superRefine((values, ctx) => {
    // Regle F225 : un depart se publie au moins 15 minutes a l'avance, verifie des l'etape 1.
    const departure = departureFromFields(values.date, values.time)
    if (departure && departure.getTime() < Date.now() + MIN_DEPARTURE_LEAD_MS) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['time'], message: 'Le départ doit être dans au moins 15 minutes' })
    }
    // Memes bornes que le serveur (TripService#validatePrices) : un arret ne coute jamais
    // plus que le trajet complet et les prix croissent avec la position.
    let previousTime = values.time
    values.stops.forEach((stop, index) => {
      if (stop.priceFromOrigin > values.pricePerSeat) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['stops', index, 'priceFromOrigin'], message: 'Ne peut pas dépasser le prix par place' })
      }
      const previous = index > 0 ? values.stops[index - 1]?.priceFromOrigin ?? 0 : 0
      if (stop.priceFromOrigin < previous) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['stops', index, 'priceFromOrigin'], message: 'Au moins le prix de l’arrêt précédent' })
      }
      if (stop.time) {
        if (stop.time <= previousTime) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            path: ['stops', index, 'time'],
            message: index === 0 ? 'Après l’heure de départ' : 'Après l’arrêt précédent',
          })
        }
        previousTime = stop.time
      }
    })
  })

type FormValues = z.infer<typeof schema>

/** Jour civil au Benin : c'est dans ce fuseau que les champs date/heure sont saisis (audit F424). */
function todayIso(): string {
  return toInputDate(new Date())
}

export function PublishTripPage() {
  const navigate = useNavigate()
  const vehicles = useMyVehicles()
  const addVehicle = useAddVehicle()
  const createTrip = useCreateTrip()
  const [step, setStep] = useState(0)
  const [direction, setDirection] = useState(1)
  const [origin, setOrigin] = useState<CityOption | null>(null)
  const [destination, setDestination] = useState<CityOption | null>(null)
  const [vehicleSheetOpen, setVehicleSheetOpen] = useState(false)

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues: {
      tripType: 'INTERURBAIN',
      originLabel: '',
      destLabel: '',
      // Heure initiale honnete : la prochaine demi-heure ronde, jamais « aujourd'hui 07:00 » deja passe (audit F225).
      ...nextHalfHour(),
      weekdays: [1, 2, 3, 4, 5],
      weeksCount: 4,
      vehicleId: '',
      seatsTotal: 3,
      pricePerSeat: 2000,
      luggagePolicy: '1 bagage cabine',
      description: '',
      instantBooking: true,
      stops: [],
    },
  })

  const stopsField = useFieldArray({ control: form.control, name: 'stops' })
  /*
   * Abonnements cibles (audit F242) : seuls les champs qui pilotent l'affichage
   * re-rendent la page, pas chaque frappe dans un champ texte. Le recapitulatif de
   * l'etape 3 lit ses propres champs plus bas.
   */
  const tripType = useWatch({ control: form.control, name: 'tripType' })
  const weekdays = useWatch({ control: form.control, name: 'weekdays' })
  const weeksCount = useWatch({ control: form.control, name: 'weeksCount' })
  const vehicleId = useWatch({ control: form.control, name: 'vehicleId' })
  const instantBooking = useWatch({ control: form.control, name: 'instantBooking' })
  const [date, time, seatsTotal, pricePerSeat, stopsValue] = useWatch({
    control: form.control,
    name: ['date', 'time', 'seatsTotal', 'pricePerSeat', 'stops'],
  })
  const clockDiffers = deviceClockDiffersFromBenin()

  const distanceKm = origin && destination ? haversineKm(origin.lat, origin.lng, destination.lat, destination.lng) : 0
  const suggestedPrice = distanceKm > 0 ? suggestPricePerSeat(distanceKm) : 0

  const vehicleList = vehicles.data ?? []
  const selectedVehicle = vehicleList.find((v) => v.id === vehicleId)
  // Le nombre de places est borne par le vehicule choisi (audit F226).
  const maxSeats = selectedVehicle ? Math.min(MAX_SEATS, Math.max(1, selectedVehicle.seats)) : MAX_SEATS

  /** Nombre de departs generes par la recurrence, affiche au recapitulatif. */
  const departuresCount = useMemo(() => {
    if (tripType !== 'QUOTIDIEN') return 1
    return Math.max(1, weekdays.length * weeksCount)
  }, [tripType, weekdays, weeksCount])

  const selectVehicle = (vehicleId: string) => {
    form.setValue('vehicleId', vehicleId, { shouldValidate: true })
    const vehicle = vehicleList.find((v) => v.id === vehicleId)
    if (vehicle && form.getValues('seatsTotal') > vehicle.seats) {
      form.setValue('seatsTotal', Math.max(1, Math.min(MAX_SEATS, vehicle.seats)))
    }
  }

  const goNext = async () => {
    const fields: (keyof FormValues)[][] = [
      ['tripType', 'originLabel', 'destLabel', 'date', 'time'],
      ['vehicleId', 'seatsTotal', 'pricePerSeat', 'stops'],
      [],
    ]
    const valid = await form.trigger(fields[step] as never)
    if (!valid) return
    setDirection(1)
    setStep((s) => Math.min(2, s + 1))
  }

  const goBack = () => {
    setDirection(-1)
    setStep((s) => Math.max(0, s - 1))
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    // Entree dans un champ (ou tout envoi implicite) avant le recapitulatif : on avance
    // d une etape, on ne publie jamais sans que le conducteur ait vu le resume.
    if (step < 2) {
      event.preventDefault()
      void goNext()
      return
    }
    return publish(event)
  }

  const publish = form.handleSubmit((data) => {
    if (!origin || !destination) {
      toast.error('Le départ et la destination sont requis.')
      setStep(0)
      return
    }
    const departure = departureFromFields(data.date, data.time)
    if (!departure) {
      form.setError('date', { message: 'Date ou heure invalide' })
      setStep(0)
      return
    }
    const departureAt = departure.toISOString()
    // Chaque arret porte les coordonnees choisies dans l'autocompletion (valide par le schema)
    // et, si le conducteur l'a renseignee, son heure de passage le jour du depart.
    const stops: StopRequest[] = data.stops.map((stop) => ({
      label: stop.label,
      lat: stop.lat,
      lng: stop.lng,
      priceFromOrigin: stop.priceFromOrigin,
      plannedAt: stop.time ? departureFromFields(data.date, stop.time)?.toISOString() : undefined,
    }))

    const payload: CreateTripRequest = {
      vehicleId: data.vehicleId,
      tripType: data.tripType,
      originLabel: origin.label,
      originLat: origin.lat,
      originLng: origin.lng,
      destLabel: destination.label,
      destLat: destination.lat,
      destLng: destination.lng,
      departureAt,
      seatsTotal: data.seatsTotal,
      pricePerSeat: data.pricePerSeat,
      // V19 (audit F048) : `false` = le conducteur accepte chaque passager avant confirmation.
      instantBooking: data.instantBooking,
      luggagePolicy: data.luggagePolicy || undefined,
      description: data.description || undefined,
      // Recurrence exprimee en RRULE (RFC 5545), lisible par le backend.
      recurrenceRule:
        data.tripType === 'QUOTIDIEN' && data.weekdays.length > 0
          ? `FREQ=WEEKLY;COUNT=${departuresCount};BYDAY=${data.weekdays.map((d) => WEEKDAYS[d - 1].rrule).join(',')}`
          : undefined,
      stops: stops.length > 0 ? stops : undefined,
    }

    createTrip.mutate(payload, {
      onSuccess: (trip) => {
        // Le nombre reel d occurrences vient du serveur (navette) : l estimation locale ne fait pas foi.
        const generated = trip.generatedOccurrences ?? null
        toast.success(trip.status === 'TEMPLATE' ? 'Navette publiée' : 'Trajet publié', {
          description:
            generated !== null
              ? `${generated} départ${generated > 1 ? 's' : ''} sur les 14 prochains jours. Les suivants seront ajoutés automatiquement.`
              : 'Votre annonce est en ligne.',
        })
        navigate('/trips/mine')
      },
      onError: (error) => {
        // Horaire refuse par le serveur (400 mentionnant departureAt) : retour a l'etape 1, erreur sur le champ.
        if (error instanceof ApiError && error.status === 400 && /departureAt|départ|depart/i.test(error.message)) {
          setDirection(-1)
          setStep(0)
          form.setError('time', { message: error.message })
          return
        }
        toast.error(describeError(error, "Le trajet n'a pas pu être publié."))
      },
    })
  })

  return (
    <PageContainer width="md" className="pb-12">
      <PageMeta title="Publier un trajet" noindex />
      <PageHeader title="Publier un trajet" subtitle="Trois étapes : trajet, véhicule et prix, options." />

      {/* Meme indicateur d'etapes que la reservation (audit F331). */}
      <StepIndicator steps={PUBLISH_STEPS} current={step} label="Étapes de la publication" />

      <form onSubmit={submit} noValidate>
        <AnimatePresence mode="wait" custom={direction}>
          {/* ------------------------------------------------ Étape 1 : trajet */}
          {step === 0 ? (
            <m.div
              key="step-0"
              custom={direction}
              initial={{ opacity: 0, x: direction * 18 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: direction * -18 }}
              transition={{ duration: 0.22 }}
              className="space-y-4"
            >
              <Card className="overflow-visible p-4">
                <Controller
                  control={form.control}
                  name="tripType"
                  render={({ field }) => (
                    <SegmentedToggle
                      label="Type de trajet"
                      value={field.value}
                      onValueChange={(value: TripType) => field.onChange(value)}
                      className="mb-4"
                      options={[
                        { value: 'INTERURBAIN', label: 'Interurbain', hint: 'Un départ ponctuel' },
                        { value: 'QUOTIDIEN', label: 'Quotidien', hint: 'Navette récurrente' },
                      ]}
                    />
                  )}
                />

                <div className="grid gap-3">
                  <CityAutocomplete
                    label="Départ"
                    value={origin}
                    exclude={destination}
                    icon={<CircleDot />}
                    error={form.formState.errors.originLabel?.message}
                    onChange={(city) => {
                      setOrigin(city)
                      form.setValue('originLabel', city?.label ?? '', { shouldValidate: true })
                      if (city && destination) {
                        form.setValue('pricePerSeat', suggestPricePerSeat(haversineKm(city.lat, city.lng, destination.lat, destination.lng)))
                      }
                    }}
                  />
                  <CityAutocomplete
                    label="Destination"
                    value={destination}
                    exclude={origin}
                    icon={<Flag />}
                    error={form.formState.errors.destLabel?.message}
                    onChange={(city) => {
                      setDestination(city)
                      form.setValue('destLabel', city?.label ?? '', { shouldValidate: true })
                      if (city && origin) {
                        form.setValue('pricePerSeat', suggestPricePerSeat(haversineKm(origin.lat, origin.lng, city.lat, city.lng)))
                      }
                    }}
                  />
                </div>

                {distanceKm > 0 ? (
                  <p className="tnum mt-3 flex items-center gap-1.5 rounded-[var(--radius-control)] bg-surface-2 px-3 py-2 text-label text-ink-2">
                    <Info className="size-3.5 shrink-0" aria-hidden />≈ {Math.round(distanceKm)} km ·{' '}
                    {formatDuration(estimateDurationMinutes(distanceKm))} de route (estimation)
                  </p>
                ) : null}

                <div className="mt-3 grid gap-3 sm:grid-cols-2">
                  <Input
                    type="date"
                    label={tripType === 'QUOTIDIEN' ? 'Premier départ' : 'Date'}
                    min={todayIso()}
                    leading={<CalendarDays />}
                    error={form.formState.errors.date?.message}
                    {...form.register('date')}
                  />
                  <Input
                    type="time"
                    label={clockDiffers ? `Heure de départ (${BENIN_TIME_HINT})` : 'Heure de départ'}
                    hint={clockDiffers ? "Au moins 15 minutes à l'avance, en heure du Bénin." : "Au moins 15 minutes à l'avance."}
                    error={form.formState.errors.time?.message}
                    {...form.register('time')}
                  />
                </div>
              </Card>

              {/* Récurrence : uniquement en mode quotidien */}
              {tripType === 'QUOTIDIEN' ? (
                <Card className="p-4">
                  <SectionTitle>Récurrence</SectionTitle>
                  <Controller
                    control={form.control}
                    name="weekdays"
                    render={({ field }) => (
                      <fieldset>
                        <legend className="mb-2 text-label font-medium text-ink-2">Jours de circulation</legend>
                        <div className="flex gap-1.5">
                          {WEEKDAYS.map((day) => {
                            const active = field.value.includes(day.value)
                            return (
                              <button
                                key={day.value}
                                type="button"
                                aria-pressed={active}
                                aria-label={day.name}
                                onClick={() =>
                                  field.onChange(
                                    active
                                      ? field.value.filter((d) => d !== day.value)
                                      : [...field.value, day.value].sort((a, b) => a - b),
                                  )
                                }
                                className={
                                  active
                                    ? 'flex size-11 flex-1 items-center justify-center rounded-[var(--radius-control)] bg-primary font-display text-base font-bold text-on-primary transition-transform active:scale-95'
                                    : 'flex size-11 flex-1 items-center justify-center rounded-[var(--radius-control)] border border-field-border bg-surface font-display text-base font-bold text-muted transition-transform active:scale-95'
                                }
                              >
                                {day.letter}
                              </button>
                            )
                          })}
                        </div>
                      </fieldset>
                    )}
                  />

                  <Separator className="my-4" />

                  <Controller
                    control={form.control}
                    name="weeksCount"
                    render={({ field }) => (
                      <div className="flex items-center justify-between gap-4">
                        <div>
                          <span className="text-body font-medium">Répéter pendant</span>
                          <p className="text-caption text-muted">Nombre de semaines</p>
                        </div>
                        <Stepper
                          value={field.value}
                          onChange={field.onChange}
                          min={1}
                          max={26}
                          label="semaines"
                          suffix="sem."
                          decrementLabel="Une semaine de moins"
                          incrementLabel="Une semaine de plus"
                        />
                      </div>
                    )}
                  />

                  <p className="mt-3 flex items-center gap-2 rounded-[var(--radius-control)] bg-primary-soft px-3 py-2.5 text-label font-medium text-primary-ink">
                    <Repeat className="size-4 shrink-0" aria-hidden />
                    <span className="tnum">
                      {departuresCount} départ{departuresCount > 1 ? 's' : ''} au total, publiés 14 jours à l'avance.
                    </span>
                  </p>
                </Card>
              ) : null}
            </m.div>
          ) : null}

          {/* -------------------------------- Étape 2 : véhicule, places, prix */}
          {step === 1 ? (
            <m.div
              key="step-1"
              custom={direction}
              initial={{ opacity: 0, x: direction * 18 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: direction * -18 }}
              transition={{ duration: 0.22 }}
              className="space-y-4"
            >
              <Card className="p-4">
                <SectionTitle
                  action={
                    vehicleList.length > 0 ? (
                      <Button variant="link" size="sm" type="button" onClick={() => setVehicleSheetOpen(true)}>
                        <Plus className="size-3.5" aria-hidden />
                        Ajouter
                      </Button>
                    ) : null
                  }
                >
                  Véhicule
                </SectionTitle>
                {vehicles.isPending ? (
                  <Skeleton className="h-11 w-full" />
                ) : vehicles.isError ? (
                  <ErrorState
                    title="Véhicules indisponibles"
                    description="Impossible de charger vos véhicules pour l'instant."
                    onRetry={() => vehicles.refetch()}
                  />
                ) : vehicleList.length === 0 ? (
                  <div className="rounded-[var(--radius-control)] border border-dashed border-field-border p-4 text-center">
                    <Car className="mx-auto size-6 text-muted" aria-hidden />
                    <p className="mt-2 text-body text-ink-2">Aucun véhicule enregistré.</p>
                    {/* La feuille s'ouvre ici : le formulaire de publication n'est pas perdu (audit F226). */}
                    <Button variant="secondary" size="sm" type="button" className="mt-3" onClick={() => setVehicleSheetOpen(true)}>
                      Ajouter un véhicule
                    </Button>
                  </div>
                ) : (
                  <Controller
                    control={form.control}
                    name="vehicleId"
                    render={({ field }) => (
                      <div className="flex flex-col gap-1.5">
                        <Label htmlFor="vehicle-select">Sélectionnez votre véhicule</Label>
                        <Select value={field.value} onValueChange={selectVehicle}>
                          <SelectTrigger id="vehicle-select">
                            <SelectValue placeholder="Choisir un véhicule" />
                          </SelectTrigger>
                          <SelectContent>
                            {vehicleList.map((vehicle) => (
                              <SelectItem key={vehicle.id} value={vehicle.id}>
                                {vehicle.brand} {vehicle.model} — {vehicle.plate} · {vehicle.seats} place{vehicle.seats > 1 ? 's' : ''}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                        {form.formState.errors.vehicleId ? <FieldError>{form.formState.errors.vehicleId.message}</FieldError> : null}
                      </div>
                    )}
                  />
                )}

                <Separator className="my-4" />

                <Controller
                  control={form.control}
                  name="seatsTotal"
                  render={({ field }) => (
                    <div className="flex items-center justify-between gap-4">
                      <div>
                        <span className="text-body font-medium">Places proposées</span>
                        <p className="text-caption text-muted">
                          {selectedVehicle
                            ? `Hors conducteur · ${maxSeats} au plus dans ce véhicule`
                            : 'Hors conducteur'}
                        </p>
                      </div>
                      <Stepper
                        value={Math.min(field.value, maxSeats)}
                        onChange={field.onChange}
                        min={1}
                        max={maxSeats}
                        label="places"
                        decrementLabel="Une place de moins"
                        incrementLabel="Une place de plus"
                      />
                    </div>
                  )}
                />
              </Card>

              <Card className="p-4">
                <SectionTitle>Prix par place</SectionTitle>
                <Controller
                  control={form.control}
                  name="pricePerSeat"
                  render={({ field }) => (
                    <>
                      <Input
                        type="number"
                        inputMode="numeric"
                        step={100}
                        min={100}
                        value={String(field.value)}
                        onChange={(event) => field.onChange(Number(event.target.value) || 0)}
                        leading={<Wallet />}
                        error={form.formState.errors.pricePerSeat?.message}
                        className="tnum font-display text-title font-bold"
                        aria-label="Prix par place en FCFA"
                      />
                      {suggestedPrice > 0 ? (
                        <div className="mt-3 flex flex-wrap items-center gap-2 rounded-[var(--radius-control)] bg-surface-2 px-3 py-2.5">
                          <span className="text-label text-ink-2">
                            Prix conseillé pour {Math.round(distanceKm)} km :
                          </span>
                          <span className="tnum font-display text-base font-bold">
                            {formatFcfa(suggestedPrice)}
                          </span>
                          {field.value !== suggestedPrice ? (
                            <button
                              type="button"
                              onClick={() => field.onChange(suggestedPrice)}
                              className="ml-auto min-h-8 text-label font-semibold text-primary-ink underline-offset-4 hover:underline"
                            >
                              Appliquer
                            </button>
                          ) : (
                            <Badge tone="success" className="ml-auto">
                              <Check aria-hidden />
                              Appliqué
                            </Badge>
                          )}
                        </div>
                      ) : null}
                      <p className="mt-2 text-caption leading-relaxed text-muted">
                        Un prix proche du conseil augmente nettement les réservations. Le passager règle un acompte
                        en ligne ; vous encaissez environ{' '}
                        {formatFcfa(estimatePaymentPlan(field.value, 'MOMO_DEPOSIT').balanceAmount)} en espèces à
                        bord, par place. Montant indicatif, confirmé à chaque réservation.
                      </p>
                    </>
                  )}
                />
              </Card>

              {/* Arrêts intermédiaires */}
              <Card className="p-4">
                <SectionTitle
                  action={
                    <button
                      type="button"
                      onClick={() => {
                        // Prix suggere : celui de l'arret precedent, sinon la moitie du prix par place (arrondie aux 100 F).
                        const stops = form.getValues('stops')
                        const previous = stops.length > 0 ? stops[stops.length - 1]?.priceFromOrigin ?? 0 : 0
                        const suggested = previous > 0 ? previous : Math.max(100, Math.round(form.getValues('pricePerSeat') / 200) * 100)
                        stopsField.append({ label: '', lat: undefined as unknown as number, lng: undefined as unknown as number, priceFromOrigin: suggested, time: '' })
                      }}
                      className="flex min-h-8 items-center gap-1 text-label font-semibold text-primary-ink underline-offset-4 hover:underline"
                    >
                      <Plus className="size-3.5" aria-hidden />
                      Ajouter
                    </button>
                  }
                >
                  Arrêts intermédiaires
                </SectionTitle>
                {stopsField.fields.length === 0 ? (
                  <p className="text-label text-muted">
                    Facultatif. Un arrêt permet de prendre des passagers en cours de route, avec leur propre tarif et,
                    si vous le souhaitez, une heure de passage.
                  </p>
                ) : (
                  <ul className="space-y-3">
                    {stopsField.fields.map((field, index) => (
                      <li key={field.id} className="rounded-[var(--radius-control)] border border-rule p-3">
                        <div className="flex items-end gap-2">
                          <Controller
                            control={form.control}
                            name={`stops.${index}.label` as const}
                            render={({ field: labelField, fieldState }) => {
                              const lat = form.getValues(`stops.${index}.lat`)
                              const lng = form.getValues(`stops.${index}.lng`)
                              const current: CityOption | null =
                                labelField.value && typeof lat === 'number' && typeof lng === 'number'
                                  ? { label: labelField.value, lat, lng, region: '' }
                                  : null
                              return (
                                <div className="flex-1">
                                  <CityAutocomplete
                                    label={`Arrêt ${index + 1}`}
                                    placeholder="Ex. Allada"
                                    value={current}
                                    exclude={destination}
                                    error={fieldState.error?.message ?? form.formState.errors.stops?.[index]?.lat?.message}
                                    onChange={(city) => {
                                      labelField.onChange(city?.label ?? '')
                                      form.setValue(`stops.${index}.lat`, city?.lat as number, { shouldValidate: !!city })
                                      form.setValue(`stops.${index}.lng`, city?.lng as number, { shouldValidate: !!city })
                                    }}
                                  />
                                </div>
                              )
                            }}
                          />
                          <button
                            type="button"
                            onClick={() => stopsField.remove(index)}
                            aria-label={`Supprimer l'arrêt ${index + 1}`}
                            className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] text-muted transition-colors hover:bg-danger-soft hover:text-danger-ink"
                          >
                            <Trash2 className="size-4" aria-hidden />
                          </button>
                        </div>
                        <div className="mt-2 grid grid-cols-2 gap-2">
                          <Input
                            label="Prix depuis le départ"
                            type="number"
                            inputMode="numeric"
                            step={100}
                            className="tnum"
                            error={form.formState.errors.stops?.[index]?.priceFromOrigin?.message}
                            {...form.register(`stops.${index}.priceFromOrigin` as const, { valueAsNumber: true })}
                          />
                          <Input
                            label="Heure de passage"
                            type="time"
                            hint="Facultatif"
                            error={form.formState.errors.stops?.[index]?.time?.message}
                            {...form.register(`stops.${index}.time` as const)}
                          />
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </m.div>
          ) : null}

          {/* ------------------------------------- Étape 3 : options + résumé */}
          {step === 2 ? (
            <m.div
              key="step-2"
              custom={direction}
              initial={{ opacity: 0, x: direction * 18 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: direction * -18 }}
              transition={{ duration: 0.22 }}
              className="space-y-4"
            >
              <Card className="space-y-4 p-4">
                <Input label="Politique bagages" placeholder="1 bagage cabine" {...form.register('luggagePolicy')} />
                <Textarea
                  label="Précisions pour les passagers"
                  placeholder="Point de rendez-vous, arrêts, habitudes…"
                  hint="400 caractères maximum"
                  {...form.register('description')}
                />
              </Card>

              <Card className="overflow-hidden">
                <Controller
                  control={form.control}
                  name="instantBooking"
                  render={({ field }) => (
                    <SettingRow
                      title="Réservation immédiate"
                      description={
                        field.value
                          ? "Une place est confirmée dès que l'acompte est reçu (ou immédiatement en espèces)."
                          : "Acceptez chaque passager avant confirmation. L'acompte est encaissé, puis vous avez 24 h (au plus tard 2 h avant le départ) pour répondre ; sans réponse, le passager est remboursé et la place libérée."
                      }
                    >
                      <Switch checked={field.value} onCheckedChange={field.onChange} aria-label="Réservation immédiate" />
                    </SettingRow>
                  )}
                />
              </Card>

              {/* Récapitulatif */}
              <Card className="p-4">
                <SectionTitle>Récapitulatif</SectionTitle>
                <dl className="space-y-2 text-body">
                  <SummaryRow label="Trajet">
                    {origin?.label ?? '—'} → {destination?.label ?? '—'}
                  </SummaryRow>
                  <SummaryRow label="Type">
                    {tripType === 'QUOTIDIEN' ? 'Navette quotidienne' : 'Interurbain'}
                  </SummaryRow>
                  <SummaryRow label="Premier départ">
                    {date} à {time}
                    {clockDiffers ? ` (${BENIN_TIME_HINT})` : ''}
                  </SummaryRow>
                  {tripType === 'QUOTIDIEN' ? (
                    <SummaryRow label="Jours">
                      {weekdays.length === 0
                        ? 'Aucun'
                        : WEEKDAYS.filter((d) => weekdays.includes(d.value))
                            .map((d) => d.short)
                            .join(', ')}
                    </SummaryRow>
                  ) : null}
                  {selectedVehicle ? (
                    <SummaryRow label="Véhicule">
                      {selectedVehicle.brand} {selectedVehicle.model}
                    </SummaryRow>
                  ) : null}
                  <SummaryRow label="Places">{seatsTotal}</SummaryRow>
                  <SummaryRow label="Prix par place">{formatFcfa(pricePerSeat)}</SummaryRow>
                  {stopsValue.length > 0 ? (
                    <SummaryRow label="Arrêts">
                      {stopsValue.map((s) => (s.time ? `${s.label} (${s.time})` : s.label)).join(', ')}
                    </SummaryRow>
                  ) : null}
                </dl>

                <p className="mt-3 text-caption leading-relaxed text-muted">
                  {instantBooking
                    ? "Les passagers réservent directement : une place est confirmée dès que l'acompte est reçu (ou immédiatement en espèces)."
                    : 'Sur accord du conducteur : chaque demande vous est transmise, vous acceptez ou refusez depuis la liste des passagers.'}{' '}
                  Vous retrouvez la liste d'appel dans « Mes trajets ».
                </p>

                <div className="mt-4 flex items-center gap-2 rounded-[var(--radius-control)] bg-primary-soft px-3 py-3">
                  <Repeat className="size-4 shrink-0 text-primary-ink" aria-hidden />
                  <span className="tnum text-body font-semibold text-primary-ink">
                    {departuresCount} départ{departuresCount > 1 ? 's' : ''} publié
                    {departuresCount > 1 ? 's' : ''} · revenu potentiel{' '}
                    {formatFcfa(departuresCount * seatsTotal * pricePerSeat)}
                  </span>
                </div>
              </Card>
            </m.div>
          ) : null}
        </AnimatePresence>

        {/* --- Navigation de l'assistant --- */}
        <div className="mt-5 flex gap-2">
          {step > 0 ? (
            <Button type="button" variant="secondary" size="lg" onClick={goBack} className="shrink-0">
              <ArrowLeft className="size-4" aria-hidden />
              <span className="sr-only sm:not-sr-only">Retour</span>
            </Button>
          ) : null}
          {/*
           * Deux elements DOM distincts (`key`) : sans cela React reutilise le meme <button>
           * et, le passage a l'etape 3 se faisant dans les microtaches du clic sur
           * « Continuer », le navigateur executait l'action par defaut du clic sur un bouton
           * devenu `type="submit"` : le trajet etait publie sans passer par le recapitulatif.
           */}
          {step < 2 ? (
            <Button key="next" type="button" size="lg" block onClick={goNext}>
              Continuer
              <ArrowRight className="size-4" aria-hidden />
            </Button>
          ) : (
            <Button key="submit" type="submit" size="lg" block loading={createTrip.isPending}>
              Publier le trajet
            </Button>
          )}
        </div>
      </form>

      {/* Ajout d'un vehicule sans quitter la publication : selectionne automatiquement une fois cree. */}
      <Sheet
        open={vehicleSheetOpen}
        onOpenChange={setVehicleSheetOpen}
        title="Ajouter un véhicule"
        description="Ces informations sont affichées aux passagers sur vos annonces."
        footer={
          <Button type="submit" form={VEHICLE_FORM_ID} size="lg" block loading={addVehicle.isPending}>
            Ajouter et sélectionner
          </Button>
        }
      >
        <VehicleForm
          onSubmit={(vehicleValues) =>
            addVehicle.mutate(
              { ...vehicleValues, color: vehicleValues.color || undefined },
              {
                onSuccess: (vehicle) => {
                  setVehicleSheetOpen(false)
                  form.setValue('vehicleId', vehicle.id, { shouldValidate: true })
                  if (form.getValues('seatsTotal') > vehicle.seats) {
                    form.setValue('seatsTotal', Math.max(1, Math.min(MAX_SEATS, vehicle.seats)))
                  }
                  toast.success('Véhicule ajouté', { description: `${vehicle.brand} ${vehicle.model} est sélectionné pour ce trajet.` })
                },
                onError: (error) => toast.error(describeError(error, "Le véhicule n'a pas pu être ajouté. Réessayez.")),
              },
            )
          }
        />
      </Sheet>
    </PageContainer>
  )
}

function SummaryRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <dt className="shrink-0 text-muted">{label}</dt>
      <dd className="min-w-0 truncate text-right font-semibold">{children}</dd>
    </div>
  )
}
