import { Banknote, CheckCircle2, Clock } from 'lucide-react'
import { describeRefund } from '@/lib/noShowDispute'
import type { RefundSummary } from '@/api/extended'

/**
 * Sort de l argent encaisse sur une reservation qui n a pas eu lieu (V25) : annulation,
 * expiration ou conducteur absent. Lit `booking.refund` tel quel - le front ne decide
 * jamais d un remboursement.
 */
export function RefundStatusLine({ refund, className = '' }: { refund: RefundSummary; className?: string }) {
  const Icon = refund.status === 'REFUNDED' ? CheckCircle2 : refund.status === 'MANUAL' ? Clock : Banknote
  const tone = refund.status === 'REFUNDED' ? 'text-success-ink' : 'text-accent-ink'
  return (
    <p className={`flex items-start gap-1.5 text-caption leading-relaxed ${tone} ${className}`} data-testid="refund-status">
      <Icon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
      <span>{describeRefund(refund)}</span>
    </p>
  )
}
