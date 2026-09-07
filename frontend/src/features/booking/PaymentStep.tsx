import { m } from 'motion/react'
import { Phone, RefreshCw, Smartphone } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { RadioGroup, RadioGroupItem } from '@/components/ui/misc'
import { SectionTitle } from '@/components/layout/PageContainer'
import { DepositCountdown } from '@/components/booking/Countdown'
import { PaymentSplit } from '@/components/booking/PaymentSplit'
import type { PaymentProvider } from '@/api/extended'
import { formatFcfa } from '@/lib/format'
import { PROVIDERS } from '@/lib/payments'
import type { BookingFlow } from './useBookingFlow'

/** Compte a rebours local ecoule : le serveur seul decide de l'expiration, on l'interroge. */
export function CheckingExpiry() {
  return (
    <p
      role="status"
      className="flex items-center gap-2 rounded-[var(--radius-control)] bg-accent-soft px-3 py-2 text-label text-accent-ink"
    >
      <RefreshCw className="ek-spin size-4" aria-hidden />
      Délai dépassé sur cet appareil : vérification de l'état de la réservation auprès du serveur…
    </p>
  )
}

/** Etape 2 : operateur, numero a debiter, lancement du widget Kkiapay. */
export function PaymentStep({ flow, onBack }: { flow: BookingFlow; onBack: () => void }) {
  return (
    <m.div
      key="payment"
      initial={{ opacity: 0, x: 14 }}
      animate={{ opacity: 1, x: 0 }}
      exit={{ opacity: 0, x: -14 }}
      transition={{ duration: 0.22 }}
      className="space-y-4"
    >
      {flow.deadline ? (
        <DepositCountdown deadline={flow.deadline} totalSeconds={flow.windowSeconds} onExpire={() => flow.setLocalExpired(true)} />
      ) : null}
      {flow.localExpired ? <CheckingExpiry /> : null}

      <PaymentSplit plan={flow.plan} compact estimated={flow.planIsEstimate} />

      <Card className="p-4">
        <SectionTitle>Opérateur mobile money</SectionTitle>
        <RadioGroup
          value={flow.provider}
          onValueChange={(v) => flow.setProvider(v as PaymentProvider)}
          className="divide-y divide-rule overflow-hidden rounded-[var(--radius-control)] border border-rule"
        >
          {PROVIDERS.map((item) => (
            <label key={item.value} className="flex min-h-[56px] cursor-pointer items-center gap-3 px-3">
              <RadioGroupItem value={item.value} id={`provider-${item.value}`} />
              <Smartphone className="size-4 shrink-0 text-muted" aria-hidden />
              <span className="flex-1 text-body font-medium">{item.label}</span>
              <span className="tnum text-caption text-muted">{item.hint}</span>
            </label>
          ))}
        </RadioGroup>

        <div className="mt-4">
          <Input
            label="Numéro à débiter"
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            value={flow.phone}
            onChange={(event) => flow.setPhoneInput(event.target.value)}
            error={flow.phoneError}
            hint={
              flow.me.isError
                ? "Votre profil n'a pas pu être chargé : saisissez le numéro à débiter."
                : 'Vous recevrez une demande de confirmation sur ce numéro.'
            }
            leading={<Phone />}
            placeholder="+229 01 97 00 00 00"
          />
        </div>
      </Card>

      <Button
        size="lg"
        block
        loading={flow.initiateDeposit.isPending || flow.checkout.busy}
        disabled={flow.localExpired}
        onClick={flow.submitDeposit}
      >
        {flow.plan.balanceAmount === 0 ? 'Payer' : "Régler l'acompte"} {formatFcfa(flow.plan.depositAmount)}
      </Button>
      <Button variant="ghost" block onClick={onBack}>
        Revenir au trajet
      </Button>
    </m.div>
  )
}
