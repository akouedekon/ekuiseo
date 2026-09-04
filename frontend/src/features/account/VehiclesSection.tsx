import { motion } from 'motion/react'
import { BadgeCheck, Car, Plus, Trash2 } from 'lucide-react'
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
import { useAddVehicle, useDeleteVehicle, useMyVehicles } from '@/hooks/useAccount'
import { listContainer, listItem } from '@/lib/motion'
import type { VehicleResponse } from '@/api/types'
import { VEHICLE_FORM_ID, VehicleForm } from './forms/VehicleForm'

export function VehiclesSection() {
  const vehicles = useMyVehicles()
  const addVehicle = useAddVehicle()
  const deleteVehicle = useDeleteVehicle()
  const [addOpen, setAddOpen] = useState(false)
  const [toDelete, setToDelete] = useState<VehicleResponse | null>(null)

  const list = vehicles.data ?? []

  const confirmDelete = () => {
    if (!toDelete) return
    const vehicle = toDelete
    deleteVehicle.mutate(vehicle.id, {
      onSuccess: () => toast.success(`${vehicle.brand} ${vehicle.model} retiré de vos véhicules`),
      onError: () => toast.error("Le véhicule n'a pas pu être supprimé. Il est peut-être engagé sur un trajet."),
      onSettled: () => setToDelete(null),
    })
  }

  return (
    <section aria-labelledby="vehicles-title">
      <SectionTitle
        action={
          <Button variant="link" size="sm" onClick={() => setAddOpen(true)}>
            <Plus className="size-3.5" aria-hidden />
            Ajouter
          </Button>
        }
      >
        <span id="vehicles-title">Mes véhicules</span>
      </SectionTitle>

      {vehicles.isPending ? (
        <div className="space-y-2">
          <Skeleton className="h-[72px] rounded-[var(--radius-card)]" />
          <Skeleton className="h-[72px] rounded-[var(--radius-card)]" />
        </div>
      ) : vehicles.isError ? (
        <ErrorState onRetry={() => vehicles.refetch()} />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon={Car}
            title="Aucun véhicule"
            description="Ajoutez votre véhicule pour publier des trajets. Il sera vérifié avant votre première annonce."
            action={<Button onClick={() => setAddOpen(true)}>Ajouter un véhicule</Button>}
            className="py-8"
          />
        </Card>
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((vehicle) => (
            <motion.li key={vehicle.id} variants={listItem}>
              <Card className="flex items-center gap-3 p-4">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--surface-calm)] text-ink-2">
                  <Car className="size-5" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-display text-base font-bold">
                    {vehicle.brand} {vehicle.model}
                  </p>
                  <p className="tnum text-label text-muted">
                    {vehicle.plate} · {vehicle.seats} places
                    {vehicle.color ? ` · ${vehicle.color}` : ''}
                  </p>
                </div>
                {vehicle.verified ? (
                  <Badge tone="success">
                    <BadgeCheck aria-hidden />
                    Vérifié
                  </Badge>
                ) : (
                  <Badge tone="warning">En attente</Badge>
                )}
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Supprimer ${vehicle.brand} ${vehicle.model}`}
                  onClick={() => setToDelete(vehicle)}
                  className="text-muted hover:bg-[var(--vermillon-soft)] hover:text-[var(--vermillon)]"
                >
                  <Trash2 className="size-4" aria-hidden />
                </Button>
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}

      <Sheet
        open={addOpen}
        onOpenChange={setAddOpen}
        title="Ajouter un véhicule"
        description="Il sera vérifié avant votre première publication."
        footer={
          <Button type="submit" form={VEHICLE_FORM_ID} size="lg" block loading={addVehicle.isPending}>
            Ajouter le véhicule
          </Button>
        }
      >
        <VehicleForm
          onSubmit={(values) =>
            addVehicle.mutate(
              { ...values, color: values.color || undefined },
              {
                onSuccess: () => {
                  setAddOpen(false)
                  toast.success('Véhicule ajouté', { description: "Il sera vérifié par l'équipe Ekuiseo avant votre première publication." })
                },
                onError: () => toast.error("Le véhicule n'a pas pu être ajouté. Réessayez."),
              },
            )
          }
        />
      </Sheet>

      <ConfirmDialog
        open={toDelete !== null}
        onOpenChange={(open) => !open && setToDelete(null)}
        title="Supprimer ce véhicule ?"
        description={
          toDelete
            ? `${toDelete.brand} ${toDelete.model} (${toDelete.plate}) ne pourra plus être proposé sur vos trajets. Un véhicule engagé sur un trajet à venir ne peut pas être supprimé.`
            : undefined
        }
        tone="danger"
        confirmLabel="Supprimer"
        loading={deleteVehicle.isPending}
        onConfirm={confirmDelete}
      />
    </section>
  )
}
