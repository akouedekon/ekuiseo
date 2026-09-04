import { BadgeCheck, Check, Info, MapPin, Search, Users, Zap } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { SelectField } from '@/components/forms/SelectField'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input, Textarea } from '@/components/ui/input'
import { Avatar, Progress, RatingStars, Separator, Stepper, Switch } from '@/components/ui/misc'
import { EmptyState } from '@/components/ui/states'
import { Logo } from '@/components/layout/Logo'
import { PageContainer, PageHeader, SectionTitle } from '@/components/layout/PageContainer'
import { cn } from '@/lib/cn'

/*
 * Charte graphique vivante (/charte). Chaque bloc montre les tokens et les
 * composants tels qu'ils sont reellement rendus, dans le theme courant :
 * basculer clair/sombre depuis le menu du compte suffit a verifier les deux.
 * Le document de reference est docs/CHARTE-GRAPHIQUE.md.
 */

const SURFACES = [
  { token: '--bg', usage: 'Fond de page' },
  { token: '--surface', usage: 'Cartes, champs, menus' },
  { token: '--surface-2', usage: 'Zones secondaires, survol' },
  { token: '--surface-sunk', usage: 'Champs désactivés, creux' },
  { token: '--rule', usage: 'Filets et séparateurs' },
  { token: '--rule-strong', usage: 'Bordures de contrôle' },
]

const INKS = [
  { token: '--ink', usage: 'Texte principal' },
  { token: '--ink-2', usage: 'Texte secondaire' },
  { token: '--muted', usage: 'Libellés, aides (≥ 4,5:1)' },
]

const HUES = [
  { name: 'Primaire · vert Bénin', base: 'primary', role: 'Action, navigation, confirmation, revenu' },
  { name: 'Accent · jaune soleil', base: 'accent', role: 'Attente, avertissement, notation, filet' },
  { name: 'Danger · rouge', base: 'danger', role: 'Destination, erreur, perte, annulation' },
]

const TYPE_SCALE = [
  { cls: 'text-hero', label: 'hero · 42/44 · Archivo 800', sample: 'Partagez la route' },
  { cls: 'text-display-lg', label: 'display-lg · 32/34 · Archivo 800', sample: 'Cotonou → Bohicon' },
  { cls: 'text-display', label: 'display · 24/28 · Archivo 800', sample: '3 500 FCFA' },
  { cls: 'text-heading', label: 'heading · 20/26 · Archivo 700', sample: 'Réservation confirmée' },
  { cls: 'text-title', label: 'title · 17/22 · Archivo 700', sample: 'Itinéraire et tarif par tronçon' },
  { cls: 'text-lead', label: 'lead · 16/24 · Inter 500', sample: 'Trajets interurbains et navettes quotidiennes partout au Bénin.' },
  { cls: 'text-base', label: 'base · 15/22 · Inter 400', sample: 'Acompte en mobile money, solde en espèces à bord.' },
  { cls: 'text-body', label: 'body · 14/21 · Inter 400', sample: 'Un acompte de 1 000 FCFA bloque la place.' },
  { cls: 'text-label', label: 'label · 13/18 · Inter 500', sample: 'Numéro de téléphone' },
  { cls: 'text-caption', label: 'caption · 12/16 · Inter 600', sample: 'ÉTAPE 1 — MAINTENANT' },
]

export function StyleGuidePage() {
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [seats, setSeats] = useState(2)
  const [comfort, setComfort] = useState<'BASIC' | 'COMFORT' | 'PREMIUM'>('COMFORT')
  const [checked, setChecked] = useState(true)

  return (
    <PageContainer width="lg" className="pb-16">
      <PageHeader
        title="Charte graphique"
        subtitle="Tokens, typographie, composants et états, rendus dans le thème courant."
        back={false}
      />

      {/* ------------------------------------------------------------ Marque */}
      <Section title="Marque" intro="Un carré vert Bénin à coins 28 % portant un E dont le bras central se prolonge en flèche : l'initiale devient une route qui avance. Le filet tricolore (vert, jaune, rouge du drapeau) souligne le mot-symbole et signe l'en-tête, jamais ailleurs en décor.">
        <div className="grid gap-4 sm:grid-cols-3">
          <Card className="flex flex-col items-start gap-4 p-5">
            <Logo size={44} />
            <p className="text-label text-muted">Logotype complet · fond clair ou sombre</p>
          </Card>
          <Card className="flex flex-col items-start gap-4 bg-primary p-5">
            <span className="rounded-[var(--radius-control)] bg-surface p-2">
              <Logo size={40} variant="mark" />
            </span>
            <p className="text-label text-on-primary">Sur aplat vert : le symbole seul, sur pastille</p>
          </Card>
          <Card className="flex flex-col justify-between gap-4 p-5">
            <div aria-hidden className="banner-rule h-[3px] w-full" />
            <p className="text-label text-muted">Filet tricolore · primaire 16 · accent 8 · danger 8 · vide 16</p>
          </Card>
        </div>
      </Section>

      {/* ---------------------------------------------------------- Couleurs */}
      <Section title="Couleurs" intro="Neutres graphite sur pierre claire, sans nuance bleue. Trois teintes de signal, celles du drapeau, chacune en quatre rôles : pleine, survol/appui, fond pâle, encre lisible sur fond pâle.">
        <SectionTitle>Surfaces et encres</SectionTitle>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {[...SURFACES, ...INKS].map((item) => (
            <Swatch key={item.token} token={item.token} usage={item.usage} />
          ))}
        </div>

        <SectionTitle className="mt-6">Teintes de signalisation</SectionTitle>
        <div className="grid gap-3 lg:grid-cols-2">
          {HUES.map((hue) => (
            <Card key={hue.base} className="p-4">
              <div className="flex items-baseline justify-between gap-3">
                <p className="font-display text-title font-bold">{hue.name}</p>
                <p className="text-label text-muted">{hue.role}</p>
              </div>
              <div className="mt-3 grid grid-cols-4 gap-2">
                <HueChip token={`--${hue.base}`} label="pleine" text={`--${hue.base}-contrast`} />
                <HueChip token={`--${hue.base}-hover`} label="survol" text={`--${hue.base}-contrast`} />
                <HueChip token={`--${hue.base}-soft`} label="fond pâle" text={`--${hue.base}-ink`} />
                <HueChip token={`--${hue.base}-ink`} label="encre" text="--surface" />
              </div>
            </Card>
          ))}
        </div>
      </Section>

      {/* ------------------------------------------------------- Typographie */}
      <Section title="Typographie" intro="Archivo pour tout ce qui se lit de loin (titres, prix, heures), Inter pour tout ce qui se lit de près. Chiffres tabulaires dès qu'ils s'alignent.">
        <Card className="divide-y divide-rule">
          {TYPE_SCALE.map((row) => (
            <div key={row.cls} className="grid gap-1 px-4 py-3 sm:grid-cols-[220px_minmax(0,1fr)] sm:items-baseline sm:gap-4">
              <span className="text-caption uppercase tracking-wide text-muted">{row.label}</span>
              <span
                className={cn(
                  row.cls,
                  row.cls.startsWith('text-hero') || row.cls.startsWith('text-display') || row.cls === 'text-heading' || row.cls === 'text-title'
                    ? 'font-display font-extrabold tracking-[-0.03em]'
                    : row.cls === 'text-caption'
                      ? 'font-semibold uppercase tracking-[0.06em]'
                      : row.cls === 'text-label'
                        ? 'font-medium'
                        : '',
                  row.cls === 'text-display' && 'tnum',
                )}
              >
                {row.sample}
              </span>
            </div>
          ))}
        </Card>
      </Section>

      {/* ----------------------------------------------------------- Boutons */}
      <Section title="Boutons" intro="Une seule action principale par écran. Le survol descend d'un cran de teinte, l'appui de deux avec un léger enfoncement, le focus clavier dessine un anneau décollé.">
        <Card className="p-5">
          <div className="flex flex-wrap items-center gap-3">
            <Button>Réserver</Button>
            <Button variant="secondary">Filtres</Button>
            <Button variant="subtle">Voir plus</Button>
            <Button variant="ghost">Annuler</Button>
            <Button variant="outlineBrand">Voir les départs</Button>
            <Button variant="danger">Supprimer</Button>
            <Button variant="success">Valider</Button>
            <Button variant="link">Tout voir</Button>
          </div>
          <Separator className="my-4" />
          <div className="flex flex-wrap items-center gap-3">
            <Button size="lg">
              <Search className="size-5" aria-hidden />
              Principale 54 px
            </Button>
            <Button size="md">Standard 44 px</Button>
            <Button size="sm">Compacte 36 px</Button>
            <Button size="icon" aria-label="Rechercher">
              <Search className="size-5" aria-hidden />
            </Button>
            <Button loading>Chargement</Button>
            <Button disabled>Désactivé</Button>
          </div>
        </Card>
      </Section>

      {/* -------------------------------------------------------- Puces & états */}
      <Section title="Puces d'état" intro="Fond pâle + encre de la teinte, jamais la couleur pleine en texte. L'icône précise le sens pour qui ne distingue pas les couleurs.">
        <Card className="flex flex-wrap gap-2 p-5">
          <Badge tone="success">
            <Check aria-hidden />
            Confirmée
          </Badge>
          <Badge tone="warning">Acompte attendu</Badge>
          <Badge tone="danger">Annulée</Badge>
          <Badge tone="indigo">
            <Zap aria-hidden />
            Immédiat
          </Badge>
          <Badge tone="neutral">
            <Users aria-hidden />3 pl.
          </Badge>
          <Badge tone="solid">Nouveau</Badge>
          <Badge tone="outline">Non-fumeur</Badge>
          <Badge tone="success">
            <BadgeCheck aria-hidden />
            Identité vérifiée
          </Badge>
        </Card>
      </Section>

      {/* ------------------------------------------------------------ Champs */}
      <Section title="Champs" intro="Même anatomie partout : filet fort au repos, filet indigo et anneau pâle au focus, filet vermillon et message sous le champ en erreur. Le libellé est toujours visible, jamais remplacé par le placeholder.">
        <Card className="grid gap-4 p-5 sm:grid-cols-2">
          <Input label="Départ" placeholder="D'où partez-vous ?" leading={<MapPin />} hint="Ville ou quartier du Bénin." />
          <Input label="Numéro de téléphone" defaultValue="+229 97" error="Numéro de téléphone incomplet" />
          <Input label="Champ désactivé" value="Cotonou" disabled readOnly />
          <SelectField
            label="Confort"
            value={comfort}
            onValueChange={setComfort}
            options={[
              { value: 'BASIC', label: 'Confort simple' },
              { value: 'COMFORT', label: 'Confortable' },
              { value: 'PREMIUM', label: 'Haut de gamme' },
            ]}
          />
          <div className="sm:col-span-2">
            <Textarea label="Précisions" placeholder="Point de rendez-vous, arrêts, habitudes…" hint="400 caractères maximum" />
          </div>
          <div className="flex items-center justify-between gap-4">
            <span className="text-body font-medium">Places</span>
            <Stepper value={seats} onChange={setSeats} min={1} max={8} label="places" />
          </div>
          <div className="flex items-center justify-between gap-4">
            <span className="text-body font-medium">Réservation immédiate</span>
            <Switch checked={checked} onCheckedChange={setChecked} aria-label="Réservation immédiate" />
          </div>
        </Card>
      </Section>

      {/* ----------------------------------------------------- Surfaces & retours */}
      <Section title="Surfaces, retours et vides" intro="Rayons 4 / 8 / 12 / 16 px, ombres courtes avec un filet quasi invisible. Un état vide propose toujours une action ; une confirmation précède toute action qui engage.">
        <div className="grid gap-4 lg:grid-cols-3">
          <Card className="p-4">
            <div className="flex items-center gap-3">
              <Avatar firstName="Koffi" lastName="Aholou" size={44} />
              <div className="min-w-0">
                <p className="font-display text-base font-bold">Koffi Aholou</p>
                <RatingStars value={4.9} count={132} />
              </div>
            </div>
            <Separator className="my-3" />
            <Progress value={62} tone="indigo" aria-label="Exemple de progression" />
            <p className="mt-2 text-label text-muted">Carte · rayon 12 px · ombre e1</p>
          </Card>
          <Card>
            <EmptyState
              icon={Search}
              title="Aucun trajet ce jour-là"
              description="Créez une alerte : nous vous prévenons dès qu'une place se libère."
              action={<Button size="sm">Créer une alerte</Button>}
              className="py-8"
            />
          </Card>
          <Card className="flex flex-col items-start gap-3 p-4">
            <p className="flex items-start gap-2 text-body text-ink-2">
              <Info className="mt-0.5 size-4 shrink-0 text-muted" aria-hidden />
              Les retours passent par un toast (succès, échec) ou une boîte de confirmation (action qui engage).
            </p>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" onClick={() => toast.success('Réservation confirmée', { description: 'Acompte de 1 000 FCFA reçu.' })}>
                Toast succès
              </Button>
              <Button size="sm" variant="secondary" onClick={() => toast.error("Le paiement n'a pas pu être lancé.")}>
                Toast échec
              </Button>
              <Button size="sm" variant="danger" onClick={() => setConfirmOpen(true)}>
                Confirmation
              </Button>
            </div>
          </Card>
        </div>
      </Section>

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="Annuler cette réservation ?"
        description="Si le départ est dans moins de 24 h, l'acompte reste acquis au conducteur."
        tone="danger"
        confirmLabel="Confirmer l'annulation"
        onConfirm={() => {
          setConfirmOpen(false)
          toast.success('Réservation annulée')
        }}
      />
    </PageContainer>
  )
}

/* ------------------------------------------------------------ Sous-elements */

function Section({ title, intro, children }: { title: string; intro: string; children: React.ReactNode }) {
  return (
    <section className="mt-10 first:mt-0">
      <h2 className="font-display text-heading font-extrabold tracking-[-0.03em]">{title}</h2>
      <p className="mb-4 mt-1 max-w-2xl text-body leading-relaxed text-ink-2">{intro}</p>
      {children}
    </section>
  )
}

function Swatch({ token, usage }: { token: string; usage: string }) {
  return (
    <div className="overflow-hidden rounded-[var(--radius-card)] border border-rule bg-surface">
      <div className="h-14" style={{ background: `var(${token})` }} />
      <div className="px-3 py-2">
        <p className="tnum font-mono text-caption text-ink">{token}</p>
        <p className="text-caption text-muted">{usage}</p>
      </div>
    </div>
  )
}

function HueChip({ token, label, text }: { token: string; label: string; text: string }) {
  return (
    <div
      className="flex h-16 flex-col justify-between rounded-[var(--radius-control)] border border-rule p-2"
      style={{ background: `var(${token})`, color: `var(${text})` }}
    >
      <span className="text-caption font-semibold">Aa</span>
      <span className="text-[11px] font-medium leading-none">{label}</span>
    </div>
  )
}
