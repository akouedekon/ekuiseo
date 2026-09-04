import { LogOut, Monitor, Moon, Sun } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { SettingRow, Skeleton, Switch } from '@/components/ui/misc'
import { SectionTitle } from '@/components/layout/PageContainer'
import { useMyPreferences, useUpdatePreferences } from '@/hooks/useAccount'
import { useTheme } from '@/hooks/useTheme'
import { cn } from '@/lib/cn'
import type { UserPreferencesResponse } from '@/api/extended'

type ToggleKey = 'notifyByPush' | 'notifyBySms' | 'notifyByEmail' | 'smoking' | 'music' | 'pets'

const NOTIFICATION_ROWS: { key: ToggleKey; title: string; description: string }[] = [
  { key: 'notifyByPush', title: 'Notifications push', description: 'Confirmations, messages, rappels' },
  { key: 'notifyBySms', title: 'SMS', description: 'Uniquement les informations critiques' },
  { key: 'notifyByEmail', title: 'E-mail', description: 'Reçus et récapitulatifs' },
]

const ONBOARD_ROWS: { key: ToggleKey; title: string; description?: string }[] = [
  { key: 'smoking', title: 'Fumeur accepté' },
  { key: 'music', title: 'Musique pendant le trajet' },
  { key: 'pets', title: 'Animaux acceptés' },
]

const THEME_OPTIONS = [
  { value: 'light', label: 'Clair', icon: Sun },
  { value: 'dark', label: 'Sombre', icon: Moon },
  { value: 'system', label: 'Système', icon: Monitor },
] as const

export function PreferencesSection({ onLogout }: { onLogout: () => void }) {
  const preferences = useMyPreferences()
  const update = useUpdatePreferences()
  const { mode, setTheme } = useTheme()
  const prefs = preferences.data?.data

  const toggle = (key: ToggleKey, checked: boolean) =>
    update.mutate({ [key]: checked } as Partial<UserPreferencesResponse>, {
      onError: () => toast.error("Le réglage n'a pas pu être enregistré."),
    })

  const renderRows = (rows: { key: ToggleKey; title: string; description?: string }[]) =>
    rows.map((row) => (
      <SettingRow key={row.key} title={row.title} description={row.description}>
        {preferences.isPending ? (
          <Skeleton className="h-6 w-11 rounded-full" />
        ) : (
          <Switch
            checked={prefs?.[row.key] ?? false}
            onCheckedChange={(checked) => toggle(row.key, checked)}
            aria-label={row.title}
          />
        )}
      </SettingRow>
    ))

  return (
    <div>
      <SectionTitle>Notifications</SectionTitle>
      <Card className="divide-y divide-rule">{renderRows(NOTIFICATION_ROWS)}</Card>

      <SectionTitle className="mt-5">À bord</SectionTitle>
      <Card className="divide-y divide-rule">{renderRows(ONBOARD_ROWS)}</Card>

      <SectionTitle className="mt-5">Apparence</SectionTitle>
      <Card className="p-3">
        <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-label="Thème de l'interface">
          {THEME_OPTIONS.map((option) => {
            const active = mode === option.value
            return (
              <button
                key={option.value}
                type="button"
                role="radio"
                aria-checked={active}
                onClick={() => setTheme(option.value)}
                className={cn(
                  'flex min-h-[56px] flex-col items-center justify-center gap-1 rounded-[var(--radius-control)] border text-label transition-colors',
                  active
                    ? 'border-[var(--indigo)] bg-[var(--indigo-soft)] font-semibold text-[var(--indigo-deep)]'
                    : 'border-rule-strong bg-surface font-medium text-ink-2 hover:bg-[var(--surface-calm)]',
                )}
              >
                <option.icon className="size-[18px]" aria-hidden />
                {option.label}
              </button>
            )
          })}
        </div>
      </Card>

      <Button variant="ghost" block className="mt-5 text-[var(--vermillon)]" onClick={onLogout}>
        <LogOut className="size-4" aria-hidden />
        Se déconnecter
      </Button>
    </div>
  )
}
