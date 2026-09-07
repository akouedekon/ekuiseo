import { AnimatePresence, m } from 'motion/react'
import {
  AlertTriangle,
  ArrowRight,
  Check,
  CircleDot,
  Flag,
  LifeBuoy,
  MessageSquare,
  Phone,
  RefreshCw,
  Smartphone,
  Ticket,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { RadioGroup, RadioGroupItem, Separator, Skeleton, Stepper } from '@/components/ui/misc'
import { ErrorState, OfflineState } from '@/components/ui/states'
import { PageContainer, PageHeader, SectionTitle } from '@/components/layout/PageContainer'
import { DepositCountdown } from '@/components/booking/Countdown'
import { PaymentSplit } from '@/components/booking/PaymentSplit'
import { PAYMENT_MODES, PROVIDERS, estimatePaymentPlan } from '@/lib/payments'
import { RouteTimeline } from '@/components/trip/RouteTimeline'
import { buildRoutePoints } from '@/lib/route'
import {
  useBooking,
  useBookingQuote,
  useConfirmPayment,
  useCreateBooking,
  useInitiateDeposit,
  useMyBookings,
  usePaymentStatus,
} from '@/hooks/useBookings'
import { isKkiapayClosed, openKkiapay } from '@/lib/kkiapay'
import { useMe } from '@/hooks/useAuth'
import { useTrip, useTripStops } from '@/hooks/useTrips'
import { estimateArrivalIso } from '@/lib/cities'
import { describeError, errorStatus } from '@/lib/errors'
import { formatFcfa, formatPhone, formatRelativeDay, formatTime } from '@/lib/format'
import { CONTACT_EMAIL } from '@/lib/legal'
import { phoneSchema, toE164 } from '@/lib/validation'
import type { PaymentMode, PaymentProvider } from '@/api/extended'
import type { InitiatePaymentResponse } from '@/api/types'

/** Delai laisse au passager pour honorer l'acompte (aligne sur le backend), utilise seulement en attendant l'echeance serveur. */
const DEPOSIT_WINDOW_MS = 20 * 60 * 1000

/**
 * Etapes du tunnel. Les trois premieres sont pilotees par l'utilisateur ; les
 * suivantes sont des ISSUES decidees par le serveur (statut de la reservation ou
 * du paiement) et l'emportent toujours sur l'etape locale.
 */
type Step = 'recap' | 'payment' | 'waiting' | 'confirmed' | 'failed' | 'expired' | 'cancelled' | 'refund'

const STEP_INDEX: Record<Step, number> = {
  recap: 0,
  payment: 1,
  waiting: 2,
  confirmed: 3,
  failed: 1,
  expired: 1,
  cancelled: 1,
  refund: 2,
}

export function BookingPage() {
  const { tripId } = useParams<{ tripId: string }>()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  /*
   * La reservation creee vit dans l'URL (?booking=) : un rafraichissement, une
   * expiration de session ou un retour depuis « Mes trajets » reprennent le tunnel
   * au lieu de recreer une reservation (409 « deja une reservation active »).
   */
  const bookingId = searchParams.get('booking') ?? undefined
  const trip = useTrip(tripId)
  const stops = useTripStops(tripId)
  const me = useMe()

  const [flowStep, setFlowStep] = useState<Step>('recap')
  const [seats, setSeats] = useState(1)
  const [dropoffStopId, setDropoffStopId] = useState<string>('')
  const [paymentMode, setPaymentMode] = useState<PaymentMode>('MOMO_DEPOSIT')
  const [provider, setProvider] = useState<PaymentProvider>('MTN_MOMO')
  // `null` = l'utilisateur n'a rien saisi : on retombe sur le numero du compte.
  const [phoneInput, setPhoneInput] = useState<string | null>(null)
  const [phoneError, setPhoneError] = useState<string>()
  const [paymentId, setPaymentId] = useState<string>()
  // Derniere preparation de paiement : permet de rouvrir le widget Kkiapay ferme sans conclure.
  const [widgetPayment, setWidgetPayment] = useState<InitiatePaymentResponse | null>(null)
  const [widgetOpen, setWidgetOpen] = useState(false)
  // Identifiant Kkiapay remis par le widget mais dont le rattachement serveur a echoue (reseau).
  const [unlinkedTransactionId, setUnlinkedTransactionId] = useState<string>()
  // Compte a rebours local ecoule : on interroge le serveur, seul juge de l'expiration.
  const [localExpired, setLocalExpired] = useState(false)
  // 409 a la creation : une reservation active existe deja sur ce trajet.
  const [conflict, setConflict] = useState(false)

  const data = trip.data
  const stopList = stops.data ?? []
  const selectedStop = stopList.find((s) => s.id === dropoffStopId)
  const unitPrice = selectedStop ? selectedStop.priceFromOrigin : (data?.pricePerSeat ?? 0)

  /*
   * Montants affiches — ordre de priorite strict :
   *  1. le plan renvoye par le serveur pour la reservation creee (fait foi) ;
   *  2. sinon le devis serveur demande avant reservation ;
   *  3. sinon une estimation locale, explicitement etiquetee comme telle.
   * Aucun montant n'est ecrit en dur nulle part.
   */
  const quote = useBookingQuote(
    tripId,
    { seats, dropoffStopId: dropoffStopId || undefined, paymentMode, unitPrice },
    !bookingId,
  )
  const booking = useBooking(bookingId, {
    refetchInterval: localExpired && bookingId ? 5_000 : false,
  })
  const serverPlan = booking.data?.paymentPlan
  const plan = serverPlan ?? quote.data?.plan ?? estimatePaymentPlan(unitPrice * seats, paymentMode)
  const planIsEstimate = !serverPlan && (quote.data?.estimated ?? true)

  const createBooking = useCreateBooking(tripId ?? '')
  const initiateDeposit = useInitiateDeposit(bookingId)
  const paymentStatus = usePaymentStatus(paymentId)
  const confirmPayment = useConfirmPayment(paymentId)
  // Reservation existante a reprendre, cherchee seulement apres un 409.
  const myBookings = useMyBookings(conflict)
  const existing = conflict
    ? myBookings.data?.find(
        (b) => b.tripId === tripId && (b.status === 'PENDING_PAYMENT' || b.status === 'CONFIRMED'),
      )
    : undefined

  const phone = phoneInput ?? me.data?.phone ?? ''

  /*
   * Reprise : la reservation existe deja (URL). On aligne places et mode sur ses
   * vraies valeurs et, si l'acompte est encore attendu, on reprend au paiement.
   * Fait une seule fois par reservation (ref), pour ne pas ramener au paiement un
   * utilisateur qui navigue dans le tunnel.
   */
  const initialisedFor = useRef<string>(undefined)
  const loaded = booking.data
  useEffect(() => {
    if (!loaded || initialisedFor.current === loaded.id) return
    initialisedFor.current = loaded.id
    setSeats(loaded.seats)
    setPaymentMode(loaded.paymentPlan.paymentMethod)
    if (loaded.status === 'PENDING_PAYMENT') setFlowStep('payment')
  }, [loaded])

  /* Quand le serveur a tranche, plus besoin de le sonder. */
  const bookingStatus = booking.data?.status
  useEffect(() => {
    if (localExpired && bookingStatus && bookingStatus !== 'PENDING_PAYMENT') setLocalExpired(false)
  }, [localExpired, bookingStatus])

  /*
   * Issue decidee par le serveur : statut de la reservation d'abord (il integre
   * le webhook et l'expiration), statut du paiement ensuite (plus rapide pour
   * l'echec). Aucune de ces transitions ne repose sur l'horloge du client.
   */
  const payStatus = paymentStatus.data?.status
  const outcome: Step | null = (() => {
    if (bookingStatus === 'EXPIRED' || serverPlan?.paymentStatus === 'EXPIRED') return 'expired'
    if (bookingStatus === 'CANCELLED_BY_PASSENGER' || bookingStatus === 'CANCELLED_BY_DRIVER') return 'cancelled'
    if (bookingStatus === 'CONFIRMED' || bookingStatus === 'COMPLETED') return 'confirmed'
    if (payStatus === 'SUCCEEDED') return 'confirmed'
    if (payStatus === 'REFUNDED' || payStatus === 'REFUND_PENDING') return 'refund'
    if (payStatus === 'EXPIRED') return 'expired'
    if (payStatus === 'FAILED') return 'failed'
    return null
  })()
  const step: Step = outcome ?? flowStep

  /*
   * Echeance de l'acompte : celle du serveur (paymentPlan.depositDueAt), sinon
   * creation + 20 min (serveur aussi). Jamais Date.now() : le compte a rebours
   * doit survivre a un rechargement sans repartir de zero.
   */
  const deadline = booking.data
    ? booking.data.paymentPlan.depositDueAt
      ? new Date(booking.data.paymentPlan.depositDueAt).getTime()
      : new Date(booking.data.createdAt).getTime() + DEPOSIT_WINDOW_MS
    : null

  if (trip.isPending && trip.fetchStatus === 'paused') {
    return (
      <PageContainer width="md">
        <OfflineState onRetry={() => trip.refetch()} />
      </PageContainer>
    )
  }

  if (trip.isPending || (bookingId && booking.isPending && !booking.isError)) {
    return (
      <PageContainer width="md">
        <Skeleton className="mb-4 h-9 w-2/3" />
        <Card className="space-y-3 p-5">
          <Skeleton className="h-5 w-1/2" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-11 w-full" />
        </Card>
      </PageContainer>
    )
  }

  if (trip.isError || !data) {
    return (
      <PageContainer width="md">
        <ErrorState title="Trajet introuvable" description={describeError(trip.error)} onRetry={() => trip.refetch()} />
      </PageContainer>
    )
  }
  if (bookingId && booking.isError && !booking.data) {
    return (
      <PageContainer width="md">
        <ErrorState
          title="Réservation introuvable"
          description={describeError(booking.error)}
          onRetry={() => booking.refetch()}
        />
        <Button variant="ghost" block className="mt-3" onClick={() => restart()}>
          Réserver à nouveau
        </Button>
      </PageContainer>
    )
  }

  const arrival = estimateArrivalIso(data)

  /** « Recommencer » : tout est remis a zero, y compris l'URL (plus de ?booking=). */
  function restart() {
    initialisedFor.current = undefined
    setPaymentId(undefined)
    setWidgetPayment(null)
    setUnlinkedTransactionId(undefined)
    setLocalExpired(false)
    setConflict(false)
    setPhoneError(undefined)
    setFlowStep('recap')
    setSearchParams({}, { replace: true })
  }

  const goToPayment = () => {
    setConflict(false)
    createBooking.mutate(
      {
        seats,
        dropoffStopId: dropoffStopId || undefined,
        paymentMode,
      },
      {
        onSuccess: (created) => {
          initialisedFor.current = created.id
          setFlowStep(paymentMode === 'CASH' ? 'confirmed' : 'payment')
          // La reservation entre dans l'URL : un rafraichissement reprend ici.
          setSearchParams({ booking: created.id }, { replace: true })
        },
        onError: (error) => {
          if (errorStatus(error) === 409) {
            setConflict(true)
            return
          }
          toast.error(describeError(error, "La demande n'a pas pu être envoyée. Réessayez."))
        },
      },
    )
  }

  const submitDeposit = () => {
    const parsed = phoneSchema.safeParse(phone)
    if (!parsed.success) {
      setPhoneError(parsed.error.issues[0]?.message ?? 'Numéro mobile money incomplet')
      return
    }
    setPhoneError(undefined)
    initiateDeposit.mutate(
      { provider, phone: toE164(phone) ?? phone },
      {
        onSuccess: (payment) => {
          setPaymentId(payment.paymentId)
          setUnlinkedTransactionId(undefined)
          setFlowStep('waiting')
          setWidgetPayment(payment)
          void launchWidget(payment)
        },
        onError: (error) => toast.error(describeError(error, "Le paiement n'a pas pu être lancé.")),
      },
    )
  }

  /*
   * Ouverture du widget Kkiapay (cle publique) : c'est lui qui debite le passager.
   * Son evenement "success" ne vaut pas confirmation : on le transmet au serveur,
   * qui reverifie la transaction (statut ET montant) avant de confirmer la
   * reservation. Si cet appel echoue (reseau), le sondage et le webhook tranchent,
   * et la reference Kkiapay reste affichee pour le support.
   */
  const launchWidget = async (payment: InitiatePaymentResponse) => {
    if (widgetOpen) return
    setWidgetOpen(true)
    try {
      const result = await openKkiapay({
        amount: payment.amount,
        publicKey: payment.kkiapayPublicKey,
        sandbox: payment.sandbox,
        phone,
        name: me.data ? `${me.data.firstName} ${me.data.lastName}`.trim() : undefined,
        // Le widget exige un e-mail (recu Kkiapay) : pre-rempli quand le profil en a un.
        email: me.data?.email ?? undefined,
        data: payment.widgetData ?? (bookingId ? { bookingId } : undefined),
      })
      confirmPayment.mutate(
        { transactionId: result.transactionId },
        {
          onError: () => {
            setUnlinkedTransactionId(result.transactionId)
            toast.message('Paiement signalé, rattachement en cours', {
              description: "Nous vérifions la transaction auprès de l'opérateur. Gardez la référence affichée.",
            })
          },
        },
      )
    } catch (error) {
      if (isKkiapayClosed(error)) {
        // Fenetre fermee sans conclure : rien a signaler, elle peut etre rouverte.
        return
      }
      const message = error instanceof Error ? error.message : ''
      if (message.startsWith('Kkiapay :')) {
        toast.error('La fenêtre de paiement ne peut pas s’ouvrir', {
          description: 'Vérifiez votre connexion ou un éventuel bloqueur de contenu, puis réessayez.',
        })
      } else {
        toast.error('Le paiement a été refusé par l’opérateur', {
          description: 'Vérifiez votre solde ou changez de numéro, puis réessayez.',
        })
        setFlowStep('payment')
      }
    } finally {
      setWidgetOpen(false)
    }
  }

  /** Nouvelle tentative de paiement sur la MEME reservation (encore en attente cote serveur). */
  const retryPayment = () => {
    setPaymentId(undefined)
    setWidgetPayment(null)
    setUnlinkedTransactionId(undefined)
    setFlowStep('payment')
  }

  const transactionRef = paymentStatus.data?.transactionRef
  const supportHref = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(
    `Réservation ${bookingId ?? ''}${transactionRef ? ` – référence ${transactionRef}` : ''}`,
  )}`

  return (
    <PageContainer width="md" className="pb-12">
      <PageHeader
        title={step === 'confirmed' ? 'Réservation confirmée' : 'Réserver'}
        subtitle={`${data.originLabel} → ${selectedStop?.label ?? data.destLabel} · ${formatRelativeDay(data.departureAt)}`}
        back={step === 'recap'}
      />

      <StepIndicator current={STEP_INDEX[step]} />

      <AnimatePresence mode="wait">
        {/* ---------------------------------------------------------- Recap */}
        {step === 'recap' ? (
          <m.div
            key="recap"
            initial={{ opacity: 0, x: 14 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -14 }}
            transition={{ duration: 0.22 }}
            className="space-y-4"
          >
            <Card className="p-4">
              <RouteTimeline
                points={buildRoutePoints(
                  data.originLabel,
                  selectedStop?.label ?? data.destLabel,
                  data.departureAt,
                  arrival,
                  unitPrice,
                  selectedStop ? [] : stopList,
                )}
              />
              <p className="mt-2 text-[12px] text-muted">≈ heure d'arrivée estimée, non garantie par le conducteur.</p>
            </Card>

            <Card className="p-4">
              <SectionTitle>Votre réservation</SectionTitle>
              <div className="flex min-h-11 items-center justify-between gap-4">
                <span className="text-[14px] font-medium">Nombre de places</span>
                <Stepper value={seats} onChange={setSeats} min={1} max={data.seatsAvailable || 1} label="places" />
              </div>
              <p className="mt-1 text-[12px] text-muted">
                {data.seatsAvailable} place{data.seatsAvailable > 1 ? 's' : ''} encore disponible
                {data.seatsAvailable > 1 ? 's' : ''}.
              </p>
              {stops.isError ? (
                <p className="mt-2 flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] bg-[var(--ocre-soft)] px-3 py-2 text-[12px] text-[var(--ocre-ink)]">
                  Arrêts intermédiaires indisponibles : réservation jusqu'au terminus uniquement.
                  <button
                    type="button"
                    className="font-semibold underline-offset-4 hover:underline"
                    onClick={() => stops.refetch()}
                  >
                    Réessayer
                  </button>
                </p>
              ) : null}

              {stopList.length > 0 ? (
                <>
                  <Separator className="my-4" />
                  <fieldset>
                    <legend className="mb-2 text-[14px] font-medium">Descendre à</legend>
                    <RadioGroup
                      value={dropoffStopId}
                      onValueChange={setDropoffStopId}
                      className="divide-y divide-rule overflow-hidden rounded-[var(--radius-control)] border border-rule"
                    >
                      {[...stopList, null].map((stop) => {
                        const value = stop?.id ?? ''
                        const label = stop?.label ?? data.destLabel
                        const price = stop?.priceFromOrigin ?? data.pricePerSeat
                        return (
                          <label
                            key={value || 'terminus'}
                            className="flex min-h-[52px] cursor-pointer items-center gap-3 px-3"
                          >
                            <RadioGroupItem value={value} id={`stop-${value || 'terminus'}`} />
                            <span className="flex-1 text-[14px] font-medium">{label}</span>
                            <span className="tnum text-[14px] font-semibold text-ink-2">{formatFcfa(price)}</span>
                          </label>
                        )
                      })}
                    </RadioGroup>
                  </fieldset>
                </>
              ) : null}
            </Card>

            {/* --- Mode de reglement --- */}
            <Card className="p-4">
              <SectionTitle>Comment souhaitez-vous payer ?</SectionTitle>
              <RadioGroup
                value={paymentMode}
                onValueChange={(value) => setPaymentMode(value as PaymentMode)}
                className="divide-y divide-rule overflow-hidden rounded-[var(--radius-control)] border border-rule"
              >
                {PAYMENT_MODES.map((option) => (
                  <label
                    key={option.value}
                    className="flex cursor-pointer items-start gap-3 px-3 py-3 transition-colors has-[:checked]:bg-[var(--indigo-soft)]"
                  >
                    <RadioGroupItem value={option.value} id={`mode-${option.value}`} className="mt-0.5" />
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="text-[14px] font-semibold">{option.label}</span>
                        {option.recommended ? <Badge tone="indigo">Recommandé</Badge> : null}
                      </span>
                      {/* Une phrase, et une seule : ce que le mode implique en cas d'annulation. */}
                      <span className="mt-0.5 block text-[13px] leading-snug text-muted">
                        {option.cancellation(plan.freeCancellationHours)}
                      </span>
                    </span>
                  </label>
                ))}
              </RadioGroup>
            </Card>

            {quote.isError ? (
              <Card className="border-[var(--vermillon)] bg-[var(--vermillon-soft)] p-4 text-[14px] text-[var(--vermillon)]">
                {describeError(quote.error, "Le devis n'a pas pu être calculé.")}
              </Card>
            ) : null}

            {conflict ? (
              <Card className="space-y-2 border-[var(--ocre)] bg-[var(--ocre-soft)] p-4 text-[14px] text-[var(--ocre-ink)]">
                <p className="font-semibold">Vous avez déjà une réservation active sur ce trajet.</p>
                {myBookings.isPending ? (
                  <p>Recherche de votre réservation…</p>
                ) : existing?.status === 'PENDING_PAYMENT' ? (
                  <Button asChild variant="secondary" size="sm">
                    <Link to={`/book/${tripId}?booking=${existing.id}`}>Reprendre ma réservation en attente</Link>
                  </Button>
                ) : (
                  <Button asChild variant="secondary" size="sm">
                    <Link to="/bookings">Voir mes réservations</Link>
                  </Button>
                )}
              </Card>
            ) : null}

            <PaymentSplit plan={plan} estimated={planIsEstimate} />

            <Button size="lg" block loading={createBooking.isPending} disabled={quote.isError} onClick={goToPayment}>
              {paymentMode === 'CASH'
                ? 'Demander la place'
                : `${paymentMode === 'MOMO_FULL' ? 'Payer' : 'Bloquer ma place pour'} ${formatFcfa(plan.depositAmount)}`}
              <ArrowRight className="size-5" aria-hidden />
            </Button>
            <p className="text-center text-[12px] text-muted">
              {planIsEstimate
                ? "Montants estimés : le décompte définitif s'affiche à l'étape suivante, avant tout paiement."
                : paymentMode === 'CASH'
                  ? 'Aucun paiement en ligne. Le conducteur peut réattribuer la place.'
                  : paymentMode === 'MOMO_FULL'
                    ? 'Voyage réglé en une fois. Rien à prévoir à bord.'
                    : "Vous ne payez que l'acompte maintenant. Aucun débit du solde en ligne."}
            </p>
          </m.div>
        ) : null}

        {/* -------------------------------------------------------- Paiement */}
        {step === 'payment' ? (
          <m.div
            key="payment"
            initial={{ opacity: 0, x: 14 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -14 }}
            transition={{ duration: 0.22 }}
            className="space-y-4"
          >
            <DepositCountdown deadline={deadline} onExpire={() => setLocalExpired(true)} />
            {localExpired ? <CheckingExpiry /> : null}

            <PaymentSplit plan={plan} compact estimated={planIsEstimate} />

            <Card className="p-4">
              <SectionTitle>Opérateur mobile money</SectionTitle>
              <RadioGroup
                value={provider}
                onValueChange={(v) => setProvider(v as PaymentProvider)}
                className="divide-y divide-rule overflow-hidden rounded-[var(--radius-control)] border border-rule"
              >
                {PROVIDERS.map((item) => (
                  <label key={item.value} className="flex min-h-[56px] cursor-pointer items-center gap-3 px-3">
                    <RadioGroupItem value={item.value} id={`provider-${item.value}`} />
                    <Smartphone className="size-4 shrink-0 text-muted" aria-hidden />
                    <span className="flex-1 text-[14px] font-medium">{item.label}</span>
                    <span className="tnum text-[12px] text-muted">{item.hint}</span>
                  </label>
                ))}
              </RadioGroup>

              <div className="mt-4">
                <Input
                  label="Numéro à débiter"
                  type="tel"
                  inputMode="tel"
                  autoComplete="tel"
                  value={phone}
                  onChange={(event) => setPhoneInput(event.target.value)}
                  error={phoneError}
                  hint={
                    me.isError
                      ? "Votre profil n'a pas pu être chargé : saisissez le numéro à débiter."
                      : 'Vous recevrez une demande de confirmation sur ce numéro.'
                  }
                  leading={<Phone />}
                  placeholder="+229 01 97 00 00 00"
                />
              </div>
            </Card>

            <Button size="lg" block loading={initiateDeposit.isPending} disabled={localExpired} onClick={submitDeposit}>
              {plan.balanceAmount === 0 ? 'Payer' : "Régler l'acompte"} {formatFcfa(plan.depositAmount)}
            </Button>
            <Button variant="ghost" block onClick={() => navigate(`/trips/${data.id}`)}>
              Revenir au trajet
            </Button>
          </m.div>
        ) : null}

        {/* ------------------------------------------- Attente du webhook */}
        {step === 'waiting' ? (
          <m.div
            key="waiting"
            initial={{ opacity: 0, x: 14 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -14 }}
            transition={{ duration: 0.22 }}
            className="space-y-4"
          >
            <DepositCountdown deadline={deadline} onExpire={() => setLocalExpired(true)} />
            {localExpired ? <CheckingExpiry /> : null}

            <Card className="flex flex-col items-center gap-4 px-5 py-8 text-center">
              {/* Anneau de progression : sobre, et double d'un texte explicite. */}
              <span className="relative flex size-16 items-center justify-center">
                <m.span
                  aria-hidden
                  className="absolute inset-0 rounded-full border-[3px] border-rule border-t-[var(--indigo)]"
                  animate={{ rotate: 360 }}
                  transition={{ duration: 1.1, repeat: Infinity, ease: 'linear' }}
                />
                <Smartphone className="size-6 text-[var(--indigo)]" aria-hidden />
              </span>

              <div>
                <h2 className="font-display text-[19px] font-bold tracking-[-0.02em]">
                  {unlinkedTransactionId
                    ? 'Paiement signalé, rattachement en cours'
                    : widgetPayment
                      ? 'Réglez dans la fenêtre Kkiapay'
                      : 'Validez sur votre téléphone'}
                </h2>
                <p className="mx-auto mt-1.5 max-w-sm text-[14px] leading-relaxed text-ink-2">
                  {unlinkedTransactionId
                    ? `Kkiapay nous a signalé un paiement (référence ${unlinkedTransactionId}) que nous n'avons pas encore pu rattacher à votre réservation. Nous le vérifions auprès de l'opérateur : la confirmation arrive dès que c'est fait.`
                    : widgetPayment
                      ? `Choisissez votre opérateur, confirmez ${formatFcfa(plan.depositAmount)} pour le ${formatPhone(phone)}, puis validez avec votre code secret sur votre téléphone.`
                      : (paymentStatus.data?.instruction ??
                        `Une demande de ${formatFcfa(plan.depositAmount)} a été envoyée au ${formatPhone(phone)}. Saisissez votre code secret pour la confirmer.`)}
                </p>
              </div>

              <Badge tone="warning">
                <RefreshCw aria-hidden className="animate-spin" />
                {confirmPayment.isPending ? 'Vérification du paiement' : "En attente de confirmation de l'opérateur"}
              </Badge>

              {widgetPayment && !unlinkedTransactionId ? (
                <Button
                  variant="secondary"
                  onClick={() => void launchWidget(widgetPayment)}
                  loading={widgetOpen}
                  disabled={confirmPayment.isPending || localExpired}
                >
                  Rouvrir la fenêtre de paiement
                </Button>
              ) : null}

              {transactionRef ? <p className="tnum text-[12px] text-muted">Référence : {transactionRef}</p> : null}
              {unlinkedTransactionId ? (
                <a href={supportHref} className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-primary-ink underline-offset-4 hover:underline">
                  <LifeBuoy className="size-4" aria-hidden />
                  Écrire au support avec cette référence
                </a>
              ) : null}
            </Card>

            <Card className="p-4 text-[13px] leading-relaxed text-muted">
              La confirmation arrive automatiquement dès que l'opérateur nous répond — inutile de rafraîchir la page.
              Vous pouvez fermer l'application : la réservation reste valable jusqu'à la fin du compte à rebours, et
              vous recevrez une notification.
            </Card>

            <Button variant="ghost" block disabled={confirmPayment.isPending || !!unlinkedTransactionId} onClick={retryPayment}>
              Changer d'opérateur ou de numéro
            </Button>
          </m.div>
        ) : null}

        {/* ---------------------------------------------------- Confirmation */}
        {step === 'confirmed' ? (
          <m.div
            key="confirmed"
            initial={{ opacity: 0, scale: 0.98 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.28 }}
            className="space-y-4"
          >
            <Card className="flex flex-col items-center gap-3 px-5 py-8 text-center">
              <m.span
                initial={{ scale: 0.5, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                transition={{ type: 'spring', stiffness: 420, damping: 22 }}
                className="flex size-16 items-center justify-center rounded-full bg-[var(--vert-soft)] text-[var(--vert)]"
              >
                <Check className="size-8" strokeWidth={3} aria-hidden />
              </m.span>
              <div>
                <h2 className="font-display text-[22px] font-extrabold tracking-[-0.03em]">
                  {plan.paymentMethod === 'CASH' ? 'Demande enregistrée' : 'Place confirmée'}
                </h2>
                <p className="mt-1 text-[14px] text-ink-2">
                  {plan.paymentMethod === 'CASH'
                    ? "Aucun paiement en ligne : la place n'est pas garantie tant que le conducteur ne vous a pas pris à bord."
                    : plan.balanceAmount === 0
                      ? `Paiement de ${formatFcfa(plan.depositAmount)} reçu. Votre place est réservée, rien à régler à bord.`
                      : `Acompte de ${formatFcfa(plan.depositAmount)} reçu. Votre place est réservée.`}
                </p>
              </div>
            </Card>

            <Card className="p-4">
              <SectionTitle>À retenir pour le départ</SectionTitle>
              <dl className="space-y-2.5 text-[14px]">
                <Row icon={<CircleDot className="text-[var(--indigo)]" />} label="Départ">
                  {data.originLabel} · {formatTime(data.departureAt)}
                </Row>
                <Row icon={<Flag className="text-[var(--vermillon)]" />} label="Arrivée">
                  {selectedStop?.label ?? data.destLabel}
                </Row>
                <Row icon={<Ticket />} label="Places">
                  {booking.data?.seats ?? seats}
                </Row>
                {plan.balanceAmount > 0 ? (
                  <>
                    <Separator />
                    <div className="flex items-baseline justify-between rounded-[var(--radius-control)] bg-[var(--ocre-soft)] px-3 py-2.5">
                      <dt className="text-[14px] font-semibold text-[var(--ocre-ink)]">À payer en espèces à bord</dt>
                      <dd className="tnum font-display text-[18px] font-extrabold text-[var(--ocre-ink)]">
                        {formatFcfa(plan.balanceAmount)}
                      </dd>
                    </div>
                  </>
                ) : null}
              </dl>
            </Card>

            <div className="flex flex-col gap-2 sm:flex-row">
              <Button asChild size="lg" block>
                <Link to="/bookings">Voir mes réservations</Link>
              </Button>
              {bookingId ? (
                <Button asChild variant="secondary" size="lg" block>
                  <Link to={`/bookings/${bookingId}/messages`}>
                    <MessageSquare className="size-4" aria-hidden />
                    Écrire au conducteur
                  </Link>
                </Button>
              ) : null}
            </div>
          </m.div>
        ) : null}

        {/* ------------------------------------------- Paiement recu trop tard */}
        {step === 'refund' ? (
          <m.div key="refund" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-4">
            <Card className="flex flex-col items-center gap-3 px-5 py-8 text-center">
              <span className="flex size-16 items-center justify-center rounded-full bg-[var(--ocre-soft)] text-[var(--ocre-ink)]">
                <AlertTriangle className="size-7" aria-hidden />
              </span>
              <div>
                <h2 className="font-display text-[20px] font-bold tracking-[-0.02em]">Paiement reçu trop tard</h2>
                <p className="mx-auto mt-1 max-w-sm text-[14px] leading-relaxed text-ink-2">
                  Votre acompte est arrivé après l'expiration de la réservation : la place n'a pas pu être confirmée.
                  {paymentStatus.data?.status === 'REFUNDED'
                    ? ' Il vous a été intégralement remboursé.'
                    : ' Il vous est remboursé automatiquement ; vous serez prévenu.'}
                </p>
                {transactionRef ? <p className="tnum mt-2 text-[12px] text-muted">Référence : {transactionRef}</p> : null}
              </div>
            </Card>
            <Button size="lg" block onClick={restart}>
              Réserver à nouveau
            </Button>
            <SupportLink href={supportHref} />
          </m.div>
        ) : null}

        {/* ------------------------------------------------ Paiement refuse */}
        {step === 'failed' ? (
          <m.div key="failed" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-4">
            <Card className="flex flex-col items-center gap-3 px-5 py-8 text-center">
              <span className="flex size-16 items-center justify-center rounded-full bg-[var(--vermillon-soft)] text-[var(--vermillon)]">
                <AlertTriangle className="size-7" aria-hidden />
              </span>
              <div>
                <h2 className="font-display text-[20px] font-bold tracking-[-0.02em]">Paiement refusé</h2>
                <p className="mx-auto mt-1 max-w-sm text-[14px] leading-relaxed text-ink-2">
                  L'opérateur n'a pas validé le paiement (solde insuffisant, code erroné ou délai dépassé). Votre place
                  reste bloquée jusqu'à la fin du compte à rebours : vous pouvez réessayer avec un autre numéro ou un
                  autre opérateur.
                </p>
                <p className="mx-auto mt-2 max-w-sm text-[13px] leading-relaxed text-muted">
                  Si votre compte mobile money a malgré tout été débité, écrivez au support avec la référence
                  {transactionRef ? ` ${transactionRef}` : ' de la transaction'} : le montant sera vérifié et remboursé.
                </p>
              </div>
            </Card>
            <DepositCountdown deadline={deadline} onExpire={() => setLocalExpired(true)} compact className="justify-center" />
            <Button size="lg" block onClick={retryPayment}>
              Réessayer le paiement
            </Button>
            <SupportLink href={supportHref} />
          </m.div>
        ) : null}

        {/* ---------------------------------------------------- Expiration */}
        {step === 'expired' ? (
          <m.div key="expired" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-4">
            <Card className="flex flex-col items-center gap-3 px-5 py-8 text-center">
              <span className="flex size-16 items-center justify-center rounded-full bg-[var(--vermillon-soft)] text-[var(--vermillon)]">
                <AlertTriangle className="size-7" aria-hidden />
              </span>
              <div>
                <h2 className="font-display text-[20px] font-bold tracking-[-0.02em]">Réservation expirée</h2>
                <p className="mx-auto mt-1 max-w-sm text-[14px] leading-relaxed text-ink-2">
                  L'acompte n'a pas été confirmé dans les 20 minutes : la place a été relibérée par le serveur. Si un
                  paiement a malgré tout été débité, il vous est remboursé automatiquement et vous en serez prévenu.
                </p>
                {transactionRef ? <p className="tnum mt-2 text-[12px] text-muted">Référence : {transactionRef}</p> : null}
              </div>
            </Card>
            <Button size="lg" block onClick={restart}>
              Recommencer la réservation
            </Button>
            <Button variant="ghost" block onClick={() => navigate(`/trips/${data.id}`)}>
              Revenir au trajet
            </Button>
            <SupportLink href={supportHref} />
          </m.div>
        ) : null}

        {/* ---------------------------------------------------- Annulation */}
        {step === 'cancelled' ? (
          <m.div key="cancelled" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-4">
            <Card className="flex flex-col items-center gap-3 px-5 py-8 text-center">
              <span className="flex size-16 items-center justify-center rounded-full bg-[var(--ocre-soft)] text-[var(--ocre-ink)]">
                <AlertTriangle className="size-7" aria-hidden />
              </span>
              <div>
                <h2 className="font-display text-[20px] font-bold tracking-[-0.02em]">Réservation annulée</h2>
                <p className="mx-auto mt-1 max-w-sm text-[14px] leading-relaxed text-ink-2">
                  {bookingStatus === 'CANCELLED_BY_DRIVER'
                    ? "Le conducteur a annulé. Si un acompte avait été versé, il vous est intégralement remboursé."
                    : 'Cette réservation a été annulée. Retrouvez le détail et un éventuel remboursement dans « Mes réservations ».'}
                </p>
              </div>
            </Card>
            <Button asChild size="lg" block>
              <Link to="/bookings">Voir mes réservations</Link>
            </Button>
            <Button variant="ghost" block onClick={restart}>
              Réserver à nouveau
            </Button>
          </m.div>
        ) : null}
      </AnimatePresence>
    </PageContainer>
  )
}

/* ------------------------------------------------------------ Sous-elements */

const STEPS = ['Récapitulatif', 'Paiement', 'Confirmation']

/** Compte a rebours local ecoule : le serveur seul decide de l'expiration, on l'interroge. */
function CheckingExpiry() {
  return (
    <p
      role="status"
      className="flex items-center gap-2 rounded-[var(--radius-control)] bg-[var(--ocre-soft)] px-3 py-2 text-[13px] text-[var(--ocre-ink)]"
    >
      <RefreshCw className="size-4 animate-spin" aria-hidden />
      Délai dépassé sur cet appareil : vérification de l'état de la réservation auprès du serveur…
    </p>
  )
}

function SupportLink({ href }: { href: string }) {
  return (
    <p className="text-center text-[13px] text-muted">
      Une question sur un débit ?{' '}
      <a href={href} className="font-semibold text-primary-ink underline-offset-4 hover:underline">
        Écrire au support ({CONTACT_EMAIL})
      </a>
    </p>
  )
}

function StepIndicator({ current }: { current: number }) {
  return (
    <ol className="mb-5 flex items-center gap-2" aria-label="Étapes de la réservation">
      {STEPS.map((label, index) => {
        const state = index < current ? 'done' : index === current ? 'current' : 'todo'
        return (
          <li key={label} className="flex flex-1 items-center gap-2">
            <span
              aria-current={state === 'current' ? 'step' : undefined}
              className={
                state === 'todo'
                  ? 'flex size-6 shrink-0 items-center justify-center rounded-full border border-rule-strong text-[12px] font-bold text-muted'
                  : 'flex size-6 shrink-0 items-center justify-center rounded-full bg-[var(--indigo)] text-[12px] font-bold text-[var(--indigo-contrast)]'
              }
            >
              {state === 'done' ? <Check className="size-3.5" strokeWidth={3} aria-hidden /> : index + 1}
            </span>
            <span
              className={
                state === 'todo'
                  ? 'hidden text-[13px] font-medium text-muted sm:block'
                  : 'hidden text-[13px] font-semibold text-ink sm:block'
              }
            >
              {label}
            </span>
            {index < STEPS.length - 1 ? (
              <span aria-hidden className="ml-1 h-0.5 flex-1 rounded-full bg-rule-strong">
                <m.span
                  className="block h-full rounded-full bg-[var(--indigo)]"
                  initial={{ scaleX: 0 }}
                  animate={{ scaleX: index < current ? 1 : 0 }}
                  style={{ originX: 0 }}
                  transition={{ duration: 0.3 }}
                />
              </span>
            ) : null}
          </li>
        )
      })}
    </ol>
  )
}

function Row({ icon, label, children }: { icon: React.ReactNode; label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2.5">
      <span aria-hidden className="text-muted [&>svg]:size-4">
        {icon}
      </span>
      <dt className="text-muted">{label}</dt>
      <dd className="ml-auto font-semibold">{children}</dd>
    </div>
  )
}
