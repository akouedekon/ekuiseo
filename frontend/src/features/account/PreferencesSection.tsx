import * as RadioGroupPrimitive from '@radix-ui/react-radio-group'
import { ExternalLink, LifeBuoy, LogOut, Monitor, Moon, Sun } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { SelectField } from '@/components/forms/SelectField'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { SettingRow, Skeleton, Switch } from '@/components/ui/misc'
import { ErrorState } from '@/components/ui/states'
import { SectionTitle } from '@/components/layout/PageContainer'
import { useMyPreferences, useUpdatePreferences } from '@/hooks/useAccount'
import { useTheme } from '@/hooks/useTheme'
import { describeError } from '@/lib/errors'
import { CHATTY_LABEL } from '@/lib/labels'
import { CONTACT_EMAIL, LEGAL_PAGES } from '@/lib/legal'
import { cn } from '@/lib/cn'
import type { UserPreferencesResponse } from '@/api/extended'

type ToggleKey = 'notifyByEmail' | 'smoking' | 'music' | 'pets'

const NOTIFICATION_ROWS: { key: ToggleKey; title: string; description: string }[] = [
  {
    key: 'notifyByEmail',
    title: 'E-mail',
    description:
      'Reçus, rappels et récapitulatifs. Les informations indispensables (confirmation, annulation, changement d’horaire) sont toujours envoyées.',
  },
]

const ONBOARD_ROWS: { key: ToggleKey; title: string; description?: string }[] = [
  { key: 'smoking', title: 'Fumeur accepté' },
  { key: 'music', title: 'Musique pendant le trajet' },
  { key: 'pets', title: 'Animaux acceptés' },
]

const CHATTY_OPTIONS = [
  { value: 'QUIET', label: CHATTY_LABEL.QUIET },
  { value: 'DEPENDS', label: CHATTY_LABEL.DEPENDS },
  { value: 'TALKATIVE', label: CHATTY_LABEL.TALKATIVE },
] as const

const THEME_OPTIONS = [
  { value: 'light', label: 'Clair', icon: Sun },
  { value: 'dark', label: 'Sombre', icon: Moon },
  { value: 'system', label: 'Système', icon: Monitor },
] as const

/** Numero WhatsApp de l'equipe (VITE_SUPPORT_WHATSAPP, chiffres seuls) ; sans lui, le contact passe par e-mail. */
const SUPPORT_WHATSAPP = (import.meta.env.VITE_SUPPORT_WHATSAPP as string | undefined)?.replace(/\D/g, '') || null

export function PreferencesSection({ onLogout }: { onLogout: () => void }) {
  const preferences = useMyPreferences()
  const update = useUpdatePreferences()
  const { mode, setTheme } = useTheme()
  const prefs = preferences.data

  const save = (input: Partial<UserPreferencesResponse>) =>
    update.mutate(input, {
      onError: (error) => toast.error(describeError(error, "Le réglage n'a pas pu être enregistré.")),
    })

  // Sans valeurs serveur, aucun interrupteur n'est affiche : un OFF par defaut
  // serait faux, et le premier clic ecraserait les vrais reglages.
  const renderRows = (rows: { key: ToggleKey; title: string; description?: string }[]) =>
    rows.map((row) => (
      <SettingRow key={row.key} title={row.title} description={row.description}>
        {preferences.isPending ? (
          <Skeleton className="h-6 w-11 rounded-full" />
        ) : (
          <Switch
            checked={prefs?.[row.key] ?? false}
            disabled={!prefs}
            onCheckedChange={(checked) => save({ [row.key]: checked })}
            aria-label={row.title}
          />
        )}
      </SettingRow>
    ))

  const errorBlock = (
    <ErrorState
      title="Réglages indisponibles"
      description="Impossible de charger vos préférences pour l'instant."
      onRetry={() => preferences.refetch()}
    />
  )

  return (
    <div>
      <SectionTitle>Notifications</SectionTitle>
      {preferences.isError ? (
        errorBlock
      ) : (
        <Card className="divide-y divide-rule">
          {/* Web Push n'existe pas encore : pas d'interrupteur qui ne ferait rien (audit F256). */}
          <SettingRow
            title="Notifications push"
            description="Confirmations, messages, rappels — bientôt disponibles"
            interactive={false}
            className="opacity-70"
          >
            <Badge tone="neutral">Bientôt</Badge>
          </SettingRow>
          {renderRows(NOTIFICATION_ROWS)}
        </Card>
      )}

      <SectionTitle className="mt-5">À bord</SectionTitle>
      {preferences.isError ? (
        errorBlock
      ) : (
        <Card className="divide-y divide-rule">
          {renderRows(ONBOARD_ROWS)}
          <div className="px-4 py-3">
            {preferences.isPending ? (
              <Skeleton className="h-11 w-full rounded-[var(--radius-control)]" />
            ) : (
              <SelectField
                label="Pendant le trajet"
                value={prefs?.chatty ?? ''}
                onValueChange={(value) => save({ chatty: value })}
                options={CHATTY_OPTIONS}
                disabled={!prefs}
                hint="Affiché sur votre profil public pour que les passagers sachent à quoi s'attendre."
              />
            )}
          </div>
        </Card>
      )}

      <SectionTitle className="mt-5">Apparence</SectionTitle>
      <Card className="p-3">
        {/* Groupe radio Radix : fleches du clavier et tabindex tournant (audit F321). */}
        <RadioGroupPrimitive.Root
          value={mode}
          onValueChange={(value) => setTheme(value as typeof mode)}
          orientation="horizontal"
          aria-label="Thème de l'interface"
          className="grid grid-cols-3 gap-2"
        >
          {THEME_OPTIONS.map((option) => {
            const active = mode === option.value
            return (
              <RadioGroupPrimitive.Item
                key={option.value}
                value={option.value}
                className={cn(
                  'flex min-h-[56px] flex-col items-center justify-center gap-1 rounded-[var(--radius-control)] border text-label transition-colors',
                  active
                    ? 'border-primary bg-primary-soft font-semibold text-primary-ink'
                    : 'border-field-border bg-surface font-medium text-ink-2 hover:bg-surface-2',
                )}
              >
                <option.icon className="size-[18px]" aria-hidden />
                {option.label}
              </RadioGroupPrimitive.Item>
            )
          })}
        </RadioGroupPrimitive.Root>
      </Card>

      {/* Aide, contact et textes legaux (audit F329) : joignables depuis les reglages, pas seulement le pied de page. */}
      <SectionTitle className="mt-5">Aide et informations</SectionTitle>
      <Card className="divide-y divide-rule">
        <a
          href={SUPPORT_WHATSAPP ? `https://wa.me/${SUPPORT_WHATSAPP}` : `mailto:${CONTACT_EMAIL}`}
          target={SUPPORT_WHATSAPP ? '_blank' : undefined}
          rel={SUPPORT_WHATSAPP ? 'noopener noreferrer' : undefined}
          className="flex min-h-[56px] items-center gap-3 px-4 py-3 text-body font-medium text-ink transition-colors hover:bg-surface-2"
        >
          <LifeBuoy className="size-[18px] shrink-0 text-primary-ink" aria-hidden />
          <span className="min-w-0 flex-1">
            <span className="block">Aide et contact</span>
            <span className="block text-caption font-normal text-muted">
              {SUPPORT_WHATSAPP ? 'WhatsApp de l’équipe Ekuiseo' : CONTACT_EMAIL}
            </span>
          </span>
          <ExternalLink className="size-4 shrink-0 text-muted" aria-hidden />
        </a>
        {LEGAL_PAGES.map((page) => (
          <Link
            key={page.slug}
            to={page.path}
            className="flex min-h-[52px] items-center px-4 py-3 text-body font-medium text-ink transition-colors hover:bg-surface-2"
          >
            {page.title}
          </Link>
        ))}
      </Card>

      <Button variant="ghost" block className="mt-5 text-danger-ink" onClick={onLogout}>
        <LogOut className="size-4" aria-hidden />
        Se déconnecter
      </Button>
    </div>
  )
}
