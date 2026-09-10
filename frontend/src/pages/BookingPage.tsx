import { AnimatePresence } from 'motion/react'
import { useNavigate, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { ErrorState, OfflineState } from '@/components/ui/states'
import { StepIndicator } from '@/components/feedback/StepIndicator'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import { stickyClearanceAlwaysClass } from '@/components/layout/StickyActionBar'
import { useIsCompactShell } from '@/hooks/useMediaQuery'
import { cn } from '@/lib/cn'
import { AwaitingDriverStep } from '@/features/booking/AwaitingDriverStep'
import { ConfirmedStep } from '@/features/booking/ConfirmedStep'
import { CancelledStep, ExpiredStep, FailedStep, RefundStep } from '@/features/booking/ExpiredStep'
import { PaymentStep } from '@/features/booking/PaymentStep'
import { RecapStep } from '@/features/booking/RecapStep'
import { WaitingStep } from '@/features/booking/WaitingStep'
import { BOOKING_STEPS, STEP_INDEX, useBookingFlow } from '@/features/booking/useBookingFlow'
import { describeError, isDefinitiveError } from '@/lib/errors'
import { formatRelativeDay } from '@/lib/format'
import { CONTACT_EMAIL } from '@/lib/legal'

/**
 * Tunnel de reservation : orchestration seulement (audit F142). L'etat et les
 * regles vivent dans `useBookingFlow`, chaque etape dans `features/booking`.
 */
export function BookingPage() {
  const { tripId } = useParams<{ tripId: string }>()
  const navigate = useNavigate()
  const flow = useBookingFlow(tripId)
  const { trip, booking, bookingId, data, step } = flow
  const compact = useIsCompactShell()

  if (trip.isPending && trip.fetchStatus === 'paused') {
    return (
      <PageContainer width="md">
        <PageMeta title="Réserver" noindex />
        <OfflineState headingLevel="h1" onRetry={() => trip.refetch()} />
      </PageContainer>
    )
  }

  if (trip.isPending || (bookingId && booking.isPending && !booking.isError)) {
    return (
      <PageContainer width="md">
        <PageMeta title="Réserver" noindex />
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
    const final = isDefinitiveError(trip.error)
    return (
      <PageContainer width="md">
        <PageMeta title="Trajet introuvable" noindex />
        <ErrorState
          headingLevel="h1"
          title="Trajet introuvable"
          description={describeError(trip.error)}
          onRetry={final ? undefined : () => trip.refetch()}
        />
      </PageContainer>
    )
  }
  if (bookingId && booking.isError && !booking.data) {
    const final = isDefinitiveError(booking.error)
    return (
      <PageContainer width="md">
        <PageMeta title="Réservation introuvable" noindex />
        <ErrorState
          headingLevel="h1"
          title="Réservation introuvable"
          description={describeError(booking.error)}
          onRetry={final ? undefined : () => booking.refetch()}
        />
        <Button variant="ghost" block className="mt-3" onClick={flow.restart}>
          Réserver à nouveau
        </Button>
      </PageContainer>
    )
  }

  const transactionRef = flow.paymentStatus.data?.transactionRef
  const supportHref = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(
    `Réservation ${bookingId ?? ''}${transactionRef ? ` – référence ${transactionRef}` : ''}`,
  )}`
  const backToTrip = () => navigate(`/trips/${data.id}`)
  const title = step === 'confirmed' ? 'Réservation confirmée' : step === 'awaiting' ? 'Demande transmise' : 'Réserver'

  return (
    // Etape 1 sur mobile : le bouton principal est dans une barre collante (RecapStep), on lui reserve sa hauteur.
    <PageContainer width="md" className={cn('pb-12', compact && step === 'recap' && stickyClearanceAlwaysClass)}>
      <PageMeta title={`${title} · ${data.originLabel} → ${data.destLabel}`} noindex />
      <PageHeader
        title={title}
        subtitle={`${data.originLabel} → ${flow.selectedStop?.label ?? data.destLabel} · ${formatRelativeDay(data.departureAt)}`}
        back={step === 'recap'}
      />

      <StepIndicator steps={BOOKING_STEPS} current={STEP_INDEX[step]} label="Étapes de la réservation" />

      <AnimatePresence mode="wait">
        {step === 'recap' ? <RecapStep flow={flow} trip={data} /> : null}
        {step === 'payment' ? <PaymentStep flow={flow} onBack={backToTrip} /> : null}
        {step === 'waiting' ? <WaitingStep flow={flow} supportHref={supportHref} /> : null}
        {step === 'awaiting' ? <AwaitingDriverStep flow={flow} /> : null}
        {step === 'confirmed' ? <ConfirmedStep flow={flow} trip={data} /> : null}
        {step === 'refund' ? <RefundStep flow={flow} supportHref={supportHref} /> : null}
        {step === 'failed' ? <FailedStep flow={flow} supportHref={supportHref} /> : null}
        {step === 'expired' ? <ExpiredStep flow={flow} supportHref={supportHref} onBackToTrip={backToTrip} /> : null}
        {step === 'cancelled' ? <CancelledStep flow={flow} /> : null}
      </AnimatePresence>
    </PageContainer>
  )
}
