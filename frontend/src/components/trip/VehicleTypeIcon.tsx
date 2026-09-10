import { Bike, Car } from 'lucide-react'
import type { SVGProps } from 'react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import { VEHICLE_TYPE_LABEL } from '@/lib/labels'
import type { VehicleType } from '@/api/types'

/**
 * Icone d un type de vehicule (V22) : voiture et moto viennent de lucide, le tricycle
 * n y existe pas et se dessine ici dans le meme style (trait 2 px, coins ronds).
 */
export function TricycleIcon({ className, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
      {...props}
    >
      {/* Cabine avec toit, trois roues */}
      <path d="M4 15V10a2 2 0 0 1 2-2h7l3 4h3a1 1 0 0 1 1 1v2" />
      <path d="M6 8V6h6" />
      <circle cx="6.5" cy="17" r="2" />
      <circle cx="17.5" cy="17" r="2" />
      <path d="M8.5 17h7" />
    </svg>
  )
}

export function VehicleTypeIcon({ type, className }: { type: VehicleType | undefined | null; className?: string }) {
  switch (type) {
    case 'MOTO':
      return <Bike className={className} aria-hidden />
    case 'TRICYCLE':
      return <TricycleIcon className={className} />
    default:
      return <Car className={className} aria-hidden />
  }
}

/** Pastille « Moto » / « Tricycle » ; la voiture, cas par defaut, n en porte pas sauf demande explicite. */
export function VehicleTypeBadge({
  type,
  always = false,
  className,
}: {
  type: VehicleType | undefined | null
  always?: boolean
  className?: string
}) {
  const resolved: VehicleType = type ?? 'CAR'
  if (resolved === 'CAR' && !always) return null
  return (
    <Badge tone="neutral" className={cn(className)}>
      <VehicleTypeIcon type={resolved} />
      {VEHICLE_TYPE_LABEL[resolved]}
    </Badge>
  )
}
