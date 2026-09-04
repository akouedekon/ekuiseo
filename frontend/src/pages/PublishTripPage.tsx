import { zodResolver } from '@hookform/resolvers/zod'
import { AnimatePresence, motion } from 'motion/react'
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
import { useMemo, useState } from 'react'
import { Controller, useFieldArray, useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input, Label, Textarea } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { SegmentedToggle } from '@/components/ui/tabs'
import { Separator, SettingRow, Switch, Skeleton, Stepper } from '@/components/ui/misc'
import { CityAutocomplete } from '@/components/trip/CityAutocomplete'
import { PageContainer, PageHeader, SectionTitle } from '@/components/layout/PageContainer'
import { useMyVehicles } from '@/hooks/useAccount'
import { useCreateTrip } from '@/hooks/useTrips'
import {
  estimateDurationMinutes,
  findCityByLabel,
  haversineKm,
  suggestPricePerSeat,
  type CityOption,
} from '@/lib/cities'
import { formatDuration, formatFcfa } from '@/lib/format'
import { estimatePaymentPlan } from '@/lib/payments'
import type { CreateTripRequest, StopRequest, TripType } from '@/api/types'

const WEEKDAYS = [
  { value: 1, letter: 'L', name: 'lundi' },
  { value: 2, letter: 'M', name: 'mardi' },
  { value: 3, letter: 'M', name: 'mercredi' },
  { value: 4, letter: 'J', name: 'jeudi' },
  { value: 5, letter: 'V', name: 'vendredi' },
  { value: 6, letter: 'S', name: 'samedi' },
  { value: 7, letter: 'D', name: 'dimanche' },
]

const RRULE_DAYS = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU']

const schema = z.object({
  tripType: z.enum(['INTERURBAIN', 'QUOTIDIEN']),
  originLabel: z.string().min(1, 'Indiquez le point de départ'),
  destLabel: z.string().min(1, "Indiquez la destination"),
  date: z.string().min(1, 'Choisissez une date'),
  time: z.string().min(1, "Choisissez une heure"),
  weekdays: z.array(z.number()),
  weeksCount: z.number().min(1).max(26),
  vehicleId: z.string().min(1, 'Sélectionnez un véhicule'),
  seatsTotal: z.number().min(1).max(8),
  pricePerSeat: z.number().min(100, 'Prix trop bas').max(100_000),
  instantBooking: z.boolean(),
  luggagePolicy: z.string().max(120).optional(),
  description: z.string().max(400).optional(),
  stops: z.array(
    z.object({
      label: z.string().min(1),
      priceFromOrigin: z.number().min(0),
    }),
  ),
})

type FormValues = z.infer<typeof schema>

function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function PublishTripPage() {
  const navigate = useNavigate()
  const vehicles = useMyVehicles()
  const createTrip = useCreateTrip()
  const [step, setStep] = useState(0)
  const [direction, setDirection] = useState(1)
  const [origin, setOrigin] = useState<CityOption | null>(null)
  const [destination, setDestination] = useState<CityOption | null>(null)

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues: {
      tripType: 'INTERURBAIN',
      originLabel: '',
      destLabel: '',
      date: todayIso(),
      time: '07:00',
      weekdays: [1, 2, 3, 4, 5],
      weeksCount: 4,
      vehicleId: '',
      seatsTotal: 3,
      pricePerSeat: 2000,
      instantBooking: true,
      luggagePolicy: '1 bagage cabine',
      description: '',
      stops: [],
    },
  })

  // React Compiler ne sait pas memoiser react-hook-form (avertissement
  // `incompatible-library`) : c'est attendu, le formulaire gere son propre etat.
  const stopsField = useFieldArray({ control: form.control, name: 'stops' })
  const values = form.watch()

  const distanceKm = origin && destination ? haversineKm(origin.lat, origin.lng, destination.lat, destination.lng) : 0
  const suggestedPrice = distanceKm > 0 ? suggestPricePerSeat(distanceKm) : 0

  /** Nombre de departs generes par la recurrence, affiche au recapitulatif. */
  const departuresCount = useMemo(() => {
    if (values.tripType !== 'QUOTIDIEN') return 1
    return Math.max(1, values.weekdays.length * values.weeksCount)
  }, [values.tripType, values.weekdays, values.weeksCount])

  const goNext = async () => {
    const fields: (keyof FormValues)[][] = [
      ['tripType', 'originLabel', 'destLabel', 'date', 'time'],
      ['vehicleId', 'seatsTotal', 'pricePerSeat'],
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

  const submit = form.handleSubmit((data) => {
    if (!origin || !destination) {
      toast.error('Le départ et la destination sont requis.')
      setStep(0)
      return
    }
    const departureAt = new Date(`${data.date}T${data.time}:00`).toISOString()
    const stops: StopRequest[] = data.stops
      .map((stop) => {
        const city = findCityByLabel(stop.label)
        if (!city) return null
        return { label: city.label, lat: city.lat, lng: city.lng, priceFromOrigin: stop.priceFromOrigin }
      })
      .filter((stop): stop is StopRequest => stop !== null)

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
      instantBooking: data.instantBooking,
      luggagePolicy: data.luggagePolicy || undefined,
      description: data.description || undefined,
      // Recurrence exprimee en RRULE (RFC 5545), lisible par le backend.
      recurrenceRule:
        data.tripType === 'QUOTIDIEN' && data.weekdays.length > 0
          ? `FREQ=WEEKLY;COUNT=${departuresCount};BYDAY=${data.weekdays.map((d) => RRULE_DAYS[d - 1]).join(',')}`
          : undefined,
      stops: stops.length > 0 ? stops : undefined,
    }

    createTrip.mutate(payload, {
      onSuccess: () => {
        toast.success('Trajet publié', {
          description:
            departuresCount > 1 ? `${departuresCount} départs ont été créés.` : 'Votre annonce est en ligne.',
        })
        navigate('/trips/mine')
      },
      onError: () => toast.error("Le trajet n'a pas pu être publié."),
    })
  })

  const vehicleList = vehicles.data?.data ?? []

  return (
    <PageContainer width="md" className="pb-12">
      <PageHeader title="Publier un trajet" subtitle={`Étape ${step + 1} sur 3`} />

      {/* Progression : trois segments pleins, sans decor. */}
      <div className="mb-5 flex gap-1.5" role="progressbar" aria-valuenow={step + 1} aria-valuemin={1} aria-valuemax={3} aria-label="Progression de la publication">
        {[0, 1, 2].map((index) => (
          <span key={index} className="h-1 flex-1 overflow-hidden rounded-full bg-rule-strong">
            <motion.span
              className="block h-full rounded-full bg-[var(--indigo)]"
              initial={false}
              animate={{ scaleX: index <= step ? 1 : 0 }}
              style={{ originX: 0 }}
              transition={{ duration: 0.3 }}
            />
          </span>
        ))}
      </div>

      <form onSubmit={submit} noValidate>
        <AnimatePresence mode="wait" custom={direction}>
          {/* ------------------------------------------------ Étape 1 : trajet */}
          {step === 0 ? (
            <motion.div
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
                  <p className="tnum mt-3 flex items-center gap-1.5 rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2 text-[13px] text-ink-2">
                    <Info className="size-3.5 shrink-0" aria-hidden />≈ {Math.round(distanceKm)} km ·{' '}
                    {formatDuration(estimateDurationMinutes(distanceKm))} de route
                  </p>
                ) : null}

                <div className="mt-3 grid gap-3 sm:grid-cols-2">
                  <Input
                    type="date"
                    label={values.tripType === 'QUOTIDIEN' ? 'Premier départ' : 'Date'}
                    min={todayIso()}
                    leading={<CalendarDays />}
                    error={form.formState.errors.date?.message}
                    {...form.register('date')}
                  />
                  <Input
                    type="time"
                    label="Heure de départ"
                    error={form.formState.errors.time?.message}
                    {...form.register('time')}
                  />
                </div>
              </Card>

              {/* Récurrence : uniquement en mode quotidien */}
              {values.tripType === 'QUOTIDIEN' ? (
                <Card className="p-4">
                  <SectionTitle>Récurrence</SectionTitle>
                  <Controller
                    control={form.control}
                    name="weekdays"
                    render={({ field }) => (
                      <fieldset>
                        <legend className="mb-2 text-[13px] font-medium text-ink-2">Jours de circulation</legend>
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
                                    ? 'flex size-11 flex-1 items-center justify-center rounded-[var(--radius-control)] bg-[var(--indigo)] font-display text-[15px] font-bold text-[var(--indigo-contrast)] transition-transform active:scale-95'
                                    : 'flex size-11 flex-1 items-center justify-center rounded-[var(--radius-control)] border border-rule-strong bg-surface font-display text-[15px] font-bold text-muted transition-transform active:scale-95'
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
                          <span className="text-[14px] font-medium">Répéter pendant</span>
                          <p className="text-[12px] text-muted">Nombre de semaines</p>
                        </div>
                        <Stepper
                          value={field.value}
                          onChange={field.onChange}
                          min={1}
                          max={26}
                          label="semaines"
                          suffix="sem."
                        />
                      </div>
                    )}
                  />

                  <p className="mt-3 flex items-center gap-2 rounded-[var(--radius-control)] bg-[var(--indigo-soft)] px-3 py-2.5 text-[13px] font-medium text-[var(--indigo-deep)]">
                    <Repeat className="size-4 shrink-0" aria-hidden />
                    <span className="tnum">
                      {departuresCount} départ{departuresCount > 1 ? 's' : ''} seront générés.
                    </span>
                  </p>
                </Card>
              ) : null}
            </motion.div>
          ) : null}

          {/* -------------------------------- Étape 2 : véhicule, places, prix */}
          {step === 1 ? (
            <motion.div
              key="step-1"
              custom={direction}
              initial={{ opacity: 0, x: direction * 18 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: direction * -18 }}
              transition={{ duration: 0.22 }}
              className="space-y-4"
            >
              <Card className="p-4">
                <SectionTitle>Véhicule</SectionTitle>
                {vehicles.isPending ? (
                  <Skeleton className="h-11 w-full" />
                ) : vehicleList.length === 0 ? (
                  <div className="rounded-[var(--radius-control)] border border-dashed border-rule-strong p-4 text-center">
                    <Car className="mx-auto size-6 text-muted" aria-hidden />
                    <p className="mt-2 text-[14px] text-ink-2">Aucun véhicule enregistré.</p>
                    <Button variant="secondary" size="sm" className="mt-3" onClick={() => navigate('/me')}>
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
                        <Select value={field.value} onValueChange={field.onChange}>
                          <SelectTrigger id="vehicle-select">
                            <SelectValue placeholder="Choisir un véhicule" />
                          </SelectTrigger>
                          <SelectContent>
                            {vehicleList.map((vehicle) => (
                              <SelectItem key={vehicle.id} value={vehicle.id}>
                                {vehicle.brand} {vehicle.model} — {vehicle.plate}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                        {form.formState.errors.vehicleId ? (
                          <p role="alert" className="text-[12px] font-medium text-[var(--vermillon)]">
                            {form.formState.errors.vehicleId.message}
                          </p>
                        ) : null}
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
                        <span className="text-[14px] font-medium">Places proposées</span>
                        <p className="text-[12px] text-muted">Hors conducteur</p>
                      </div>
                      <Stepper value={field.value} onChange={field.onChange} min={1} max={8} label="places" />
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
                        className="tnum font-display text-[17px] font-bold"
                        aria-label="Prix par place en FCFA"
                      />
                      {suggestedPrice > 0 ? (
                        <div className="mt-3 flex flex-wrap items-center gap-2 rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2.5">
                          <span className="text-[13px] text-ink-2">
                            Prix conseillé pour {Math.round(distanceKm)} km :
                          </span>
                          <span className="tnum font-display text-[15px] font-bold">
                            {formatFcfa(suggestedPrice)}
                          </span>
                          {field.value !== suggestedPrice ? (
                            <button
                              type="button"
                              onClick={() => field.onChange(suggestedPrice)}
                              className="ml-auto text-[13px] font-semibold text-[var(--indigo)] underline-offset-4 hover:underline"
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
                      <p className="mt-2 text-[12px] leading-relaxed text-muted">
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
                      onClick={() => stopsField.append({ label: '', priceFromOrigin: 0 })}
                      className="flex items-center gap-1 text-[13px] font-semibold text-[var(--indigo)] underline-offset-4 hover:underline"
                    >
                      <Plus className="size-3.5" aria-hidden />
                      Ajouter
                    </button>
                  }
                >
                  Arrêts intermédiaires
                </SectionTitle>
                {stopsField.fields.length === 0 ? (
                  <p className="text-[13px] text-muted">
                    Facultatif. Un arrêt permet de prendre des passagers en cours de route, avec leur propre tarif.
                  </p>
                ) : (
                  <ul className="space-y-2">
                    {stopsField.fields.map((field, index) => (
                      <li key={field.id} className="flex items-end gap-2">
                        <Input
                          label={index === 0 ? 'Ville' : undefined}
                          placeholder="Ex. Allada"
                          className="flex-1"
                          {...form.register(`stops.${index}.label` as const)}
                        />
                        <Input
                          label={index === 0 ? 'Prix' : undefined}
                          type="number"
                          inputMode="numeric"
                          step={100}
                          className="tnum w-28"
                          aria-label={`Prix depuis le départ pour l'arrêt ${index + 1}`}
                          {...form.register(`stops.${index}.priceFromOrigin` as const, { valueAsNumber: true })}
                        />
                        <button
                          type="button"
                          onClick={() => stopsField.remove(index)}
                          aria-label={`Supprimer l'arrêt ${index + 1}`}
                          className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] text-muted transition-colors hover:bg-[var(--vermillon-soft)] hover:text-[var(--vermillon)]"
                        >
                          <Trash2 className="size-4" aria-hidden />
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </motion.div>
          ) : null}

          {/* ------------------------------------- Étape 3 : options + résumé */}
          {step === 2 ? (
            <motion.div
              key="step-2"
              custom={direction}
              initial={{ opacity: 0, x: direction * 18 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: direction * -18 }}
              transition={{ duration: 0.22 }}
              className="space-y-4"
            >
              <Card className="divide-y divide-rule">
                <Controller
                  control={form.control}
                  name="instantBooking"
                  render={({ field }) => (
                    <SettingRow
                      title="Réservation immédiate"
                      description="Les passagers réservent sans attendre votre accord"
                    >
                      <Switch
                        checked={field.value}
                        onCheckedChange={field.onChange}
                        aria-label="Réservation immédiate"
                      />
                    </SettingRow>
                  )}
                />
              </Card>

              <Card className="space-y-4 p-4">
                <Input label="Politique bagages" placeholder="1 bagage cabine" {...form.register('luggagePolicy')} />
                <Textarea
                  label="Précisions pour les passagers"
                  placeholder="Point de rendez-vous, arrêts, habitudes…"
                  hint="400 caractères maximum"
                  {...form.register('description')}
                />
              </Card>

              {/* Récapitulatif */}
              <Card className="p-4">
                <SectionTitle>Récapitulatif</SectionTitle>
                <dl className="space-y-2 text-[14px]">
                  <SummaryRow label="Trajet">
                    {origin?.label ?? '—'} → {destination?.label ?? '—'}
                  </SummaryRow>
                  <SummaryRow label="Type">
                    {values.tripType === 'QUOTIDIEN' ? 'Navette quotidienne' : 'Interurbain'}
                  </SummaryRow>
                  <SummaryRow label="Premier départ">
                    {values.date} à {values.time}
                  </SummaryRow>
                  {values.tripType === 'QUOTIDIEN' ? (
                    <SummaryRow label="Jours">
                      {values.weekdays.length === 0
                        ? 'Aucun'
                        : WEEKDAYS.filter((d) => values.weekdays.includes(d.value))
                            .map((d) => d.letter)
                            .join(' ')}
                    </SummaryRow>
                  ) : null}
                  <SummaryRow label="Places">{values.seatsTotal}</SummaryRow>
                  <SummaryRow label="Prix par place">{formatFcfa(values.pricePerSeat)}</SummaryRow>
                  {values.stops.length > 0 ? (
                    <SummaryRow label="Arrêts">{values.stops.map((s) => s.label).join(', ')}</SummaryRow>
                  ) : null}
                </dl>

                <div className="mt-4 flex items-center gap-2 rounded-[var(--radius-control)] bg-[var(--indigo-soft)] px-3 py-3">
                  <Repeat className="size-4 shrink-0 text-[var(--indigo-deep)]" aria-hidden />
                  <span className="tnum text-[14px] font-semibold text-[var(--indigo-deep)]">
                    {departuresCount} départ{departuresCount > 1 ? 's' : ''} publié
                    {departuresCount > 1 ? 's' : ''} · revenu potentiel{' '}
                    {formatFcfa(departuresCount * values.seatsTotal * values.pricePerSeat)}
                  </span>
                </div>
              </Card>
            </motion.div>
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
          {step < 2 ? (
            <Button type="button" size="lg" block onClick={goNext}>
              Continuer
              <ArrowRight className="size-4" aria-hidden />
            </Button>
          ) : (
            <Button type="submit" size="lg" block loading={createTrip.isPending}>
              Publier le trajet
            </Button>
          )}
        </div>
      </form>
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
