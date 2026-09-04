import { motion } from 'motion/react'
import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { formatFcfa } from '@/lib/format'
import { listItem } from '@/lib/motion'
import type { DeltaUnit } from './adminMetrics'

/*
 * Briques partagees par les ecrans du back-office (tableau de bord, liquidite).
 * Les conventions de format (pourcentages, variations, couleurs) sont dans
 * adminMetrics.ts : ce fichier n'exporte que des composants (Fast Refresh).
 */

/**
 * Tuile de synthese avec sa variation vs la periode precedente : un chiffre seul
 * ne s'interprete pas. `delta` s'exprime dans `deltaUnit` (pourcentage relatif
 * pour un volume, points pour un taux, heures pour un delai) ; null quand il n'y
 * a rien a comparer. `lowerIsBetter` inverse la couleur : une baisse du taux de
 * trajets orphelins est une bonne nouvelle.
 */
export function StatTile({
  label,
  value,
  delta,
  deltaUnit = '%',
  lowerIsBetter = false,
  title,
  hint,
}: {
  label: string
  value: string
  delta: number | null
  deltaUnit?: DeltaUnit
  lowerIsBetter?: boolean
  /** Valeur exacte, exposee au survol quand l'affichage est abrege. */
  title?: string
  /** Precision de lecture, sous la variation (ex. « utilisateurs connectés »). */
  hint?: string
}) {
  return (
    <motion.div variants={listItem}>
      <Card className="h-full p-4">
        <p className="text-[13px] text-muted">{label}</p>
        <p
          title={title}
          className="tnum mt-1.5 font-display text-[24px] font-extrabold leading-none tracking-[-0.03em]"
        >
          {value}
        </p>
        <p className="mt-2 flex flex-wrap items-center gap-x-1.5 text-[13px]">
          <DeltaBadge delta={delta} unit={deltaUnit} lowerIsBetter={lowerIsBetter} />
          <span className="text-muted">vs période précédente</span>
        </p>
        {hint ? <p className="mt-1 text-[12px] text-muted">{hint}</p> : null}
      </Card>
    </motion.div>
  )
}

export function DeltaBadge({
  delta,
  unit,
  lowerIsBetter = false,
}: {
  delta: number | null
  unit: DeltaUnit
  lowerIsBetter?: boolean
}) {
  if (delta === null) {
    return (
      <span className="inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap font-semibold text-muted">
        <Minus className="size-3.5" aria-hidden />
        n/d
      </span>
    )
  }
  const up = delta >= 0
  const good = lowerIsBetter ? delta <= 0 : delta >= 0
  const magnitude = Math.abs(delta).toFixed(1).replace('.', ',')
  const suffix = unit === '%' ? ' %' : unit === 'pts' ? ' pts' : ' h'
  return (
    <span
      className={cn(
        'tnum inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap font-semibold',
        good ? 'text-[var(--vert)]' : 'text-[var(--vermillon)]',
      )}
    >
      {up ? <ArrowUpRight className="size-3.5" aria-hidden /> : <ArrowDownRight className="size-3.5" aria-hidden />}
      {up ? '+' : '−'}
      {magnitude}
      {suffix}
    </span>
  )
}

interface TooltipPayloadEntry {
  name?: string
  value?: number
  color?: string
}

/** Infobulle maison : Recharts par defaut ignore nos tokens de couleur. */
export function ChartTooltip({
  active,
  payload,
  label,
  money,
}: {
  active?: boolean
  payload?: TooltipPayloadEntry[]
  label?: string
  money?: boolean
}) {
  if (!active || !payload?.length) return null
  return (
    <div className="rounded-[var(--radius-control)] border border-rule bg-surface px-3 py-2 shadow-e3">
      {label ? <p className="mb-1 text-[12px] font-semibold text-ink">{label}</p> : null}
      <ul className="space-y-0.5">
        {payload.map((entry, index) => (
          <li key={index} className="flex items-center gap-2 text-[12px]">
            <span aria-hidden className="size-2 rounded-full" style={{ background: entry.color }} />
            <span className="text-muted">{entry.name}</span>
            <span className="tnum ml-auto font-semibold text-ink">
              {money ? formatFcfa(entry.value ?? 0) : (entry.value ?? 0).toLocaleString('fr-FR')}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** En-tete de tableau du back-office (memes classes partout). */
export function TableHead({ children }: { children: React.ReactNode }) {
  return (
    <thead>
      <tr className="border-y border-rule bg-[var(--surface-calm)] text-left text-[12px] uppercase tracking-wide text-muted">
        {children}
      </tr>
    </thead>
  )
}
