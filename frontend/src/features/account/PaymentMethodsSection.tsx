import { motion } from 'motion/react'
import { CreditCard, Plus, Smartphone, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { SectionTitle } from '@/components/layout/PageContainer'
import { useAddPaymentMethod, useDeletePaymentMethod, useMyPaymentMethods } from '@/hooks/useAccount'
import { formatPhone } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import { providerLabel } from '@/lib/payments'
import type { PaymentMethodResponse } from '@/api/extended'
import { MOMO_FORM_ID, MomoForm } from './forms/MomoForm'

export function PaymentMethodsSection({ defaultPhone }: { defaultPhone: string }) {
  const methods = useMyPaymentMethods()
  const addMethod = useAddPaymentMethod()
  const deleteMethod = useDeletePaymentMethod()
  const [addOpen, setAddOpen] = useState(false)
  const [toDelete, setToDelete] = useState<PaymentMethodResponse | null>(null)

  const list = methods.data ?? []

  const confirmDelete = () => {
    if (!toDelete) return
    deleteMethod.mutate(toDelete.id, {
      onSuccess: () => toast.success('Compte mobile money retiré'),
      onError: () => toast.error("Le compte n'a pas pu être retiré. Réessayez."),
      onSettled: () => setToDelete(null),
    })
  }

  return (
    <section aria-labelledby="payment-title">
      <SectionTitle
        action={
          <Button variant="link" size="sm" onClick={() => setAddOpen(true)}>
            <Plus className="size-3.5" aria-hidden />
            Ajouter
          </Button>
        }
      >
        <span id="payment-title">Comptes mobile money</span>
      </SectionTitle>

      {methods.isPending ? (
        <Skeleton className="h-[72px] rounded-[var(--radius-card)]" />
      ) : methods.isError ? (
        <ErrorState onRetry={() => methods.refetch()} />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon={CreditCard}
            title="Aucun compte enregistré"
            description="Enregistrez un numéro MTN, Moov ou Celtiis pour régler vos acomptes plus vite et recevoir vos reversements."
            action={<Button onClick={() => setAddOpen(true)}>Ajouter un compte</Button>}
            className="py-8"
          />
        </Card>
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((method) => (
            <motion.li key={method.id} variants={listItem}>
              <Card className="flex items-center gap-3 p-4">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--indigo-soft)] text-[var(--indigo)]">
                  <Smartphone className="size-5" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="font-display text-base font-bold">{providerLabel(method.provider)}</p>
                  <p className="tnum text-label text-muted">{formatPhone(method.phone)}</p>
                </div>
                {method.isDefault ? <Badge tone="indigo">Par défaut</Badge> : null}
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Retirer le compte ${formatPhone(method.phone)}`}
                  onClick={() => setToDelete(method)}
                  className="text-muted hover:bg-[var(--vermillon-soft)] hover:text-[var(--vermillon)]"
                >
                  <Trash2 className="size-4" aria-hidden />
                </Button>
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}

      <p className="mt-3 text-label leading-relaxed text-muted">
        Ces comptes servent à régler l'acompte de vos réservations et, si vous conduisez, à recevoir vos
        reversements.
      </p>

      <Sheet
        open={addOpen}
        onOpenChange={setAddOpen}
        title="Ajouter un compte mobile money"
        footer={
          <Button type="submit" form={MOMO_FORM_ID} size="lg" block loading={addMethod.isPending}>
            Enregistrer le compte
          </Button>
        }
      >
        <MomoForm
          defaultPhone={defaultPhone}
          onSubmit={(values) =>
            addMethod.mutate(values, {
              onSuccess: () => {
                setAddOpen(false)
                toast.success('Compte mobile money ajouté')
              },
              onError: () => toast.error("Le compte n'a pas pu être ajouté. Réessayez."),
            })
          }
        />
      </Sheet>

      <ConfirmDialog
        open={toDelete !== null}
        onOpenChange={(open) => !open && setToDelete(null)}
        title="Retirer ce compte ?"
        description={
          toDelete
            ? `${providerLabel(toDelete.provider)} · ${formatPhone(toDelete.phone)} ne sera plus proposé pour vos paiements ni vos reversements.`
            : undefined
        }
        tone="danger"
        confirmLabel="Retirer"
        loading={deleteMethod.isPending}
        onConfirm={confirmDelete}
      />
    </section>
  )
}
