import { m } from 'motion/react'
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
import { describeError } from '@/lib/errors'
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
      // Le serveur explique le refus (409 : vehicule engage sur un trajet a venir) ; on le repete tel quel (audit F255).
      onError: (error) =>
        toast.error(describeError(error, "Le véhicule n'a pas pu être supprimé. Il est peut-être engagé sur un trajet.")),
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
            description="Ajoutez votre véhicule pour publier des trajets : marque, modèle, immatriculation et nombre de places sont affichés aux passagers."
            action={<Button onClick={() => setAddOpen(true)}>Ajouter un véhicule</Button>}
            className="py-8"
          />
        </Card>
      ) : (
        <m.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((vehicle) => (
            <m.li key={vehicle.id} variants={listItem}>
              <Card className="flex items-center gap-3 p-4">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-ink-2">
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
                {/*
                 * Pas de file de verification des vehicules cote administration (audit F227) :
                 * seul un vehicule reellement atteste par l'equipe porte un badge ; les autres
                 * n'affichent aucune promesse d'« attente ».
                 */}
                {vehicle.verified ? (
                  <Badge tone="success">
                    <BadgeCheck aria-hidden />
                    Attesté par Ekuiseo
                  </Badge>
                ) : null}
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Supprimer ${vehicle.brand} ${vehicle.model}`}
                  onClick={() => setToDelete(vehicle)}
                  className="text-muted hover:bg-danger-soft hover:text-danger-ink"
                >
                  <Trash2 className="size-4" aria-hidden />
                </Button>
              </Card>
            </m.li>
          ))}
        </m.ul>
      )}

      <p className="mt-3 text-label leading-relaxed text-muted">
        Vous pouvez publier dès qu'un véhicule est enregistré. L'équipe Ekuiseo peut l'attester après un contrôle de
        la carte grise et de la plaque, à sa demande : le badge apparaît alors sur vos annonces.
      </p>

      <Sheet
        open={addOpen}
        onOpenChange={setAddOpen}
        title="Ajouter un véhicule"
        description="Ces informations sont affichées aux passagers sur vos annonces."
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
                  toast.success('Véhicule ajouté', { description: 'Vous pouvez le proposer sur vos trajets dès maintenant.' })
                },
                onError: (error) => toast.error(describeError(error, "Le véhicule n'a pas pu être ajouté. Réessayez.")),
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
