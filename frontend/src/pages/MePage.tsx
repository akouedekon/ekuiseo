import { motion } from 'motion/react'
import {
  BadgeCheck,
  Car,
  Check,
  CreditCard,
  FileCheck2,
  LogOut,
  Monitor,
  Moon,
  Plus,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
  Sun,
  Trash2,
  UserCog,
} from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Sheet } from '@/components/ui/sheet'
import { Avatar, RatingStars, SettingRow, Skeleton, Switch } from '@/components/ui/misc'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { PageContainer, PageHeader, SectionTitle } from '@/components/layout/PageContainer'
import { PROVIDERS, providerLabel } from '@/lib/payments'
import { useLogout, useMe } from '@/hooks/useAuth'
import {
  useAddPaymentMethod,
  useAddVehicle,
  useDeletePaymentMethod,
  useDeleteVehicle,
  useIdentityVerification,
  useMyPaymentMethods,
  useMyPreferences,
  useMyVehicles,
  useSubmitIdentity,
  useUpdatePreferences,
  useUpdateProfile,
} from '@/hooks/useAccount'
import { useTheme } from '@/hooks/useTheme'
import { formatFromNow, formatPhone } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { ComfortLevel } from '@/api/types'
import type { PaymentProvider } from '@/api/extended'

const IDENTITY_LABEL = {
  NOT_SUBMITTED: { label: 'Non vérifiée', tone: 'neutral' as const, icon: ShieldAlert },
  PENDING: { label: 'En cours de vérification', tone: 'warning' as const, icon: FileCheck2 },
  APPROVED: { label: 'Identité vérifiée', tone: 'success' as const, icon: ShieldCheck },
  REJECTED: { label: 'Vérification refusée', tone: 'danger' as const, icon: ShieldAlert },
}

const DOCUMENT_LABEL: Record<string, string> = {
  CNI: "Carte nationale d'identité",
  PASSPORT: 'Passeport',
  DRIVER_LICENSE: 'Permis de conduire',
}

export function MePage() {
  const me = useMe()
  const logout = useLogout()
  const { mode, setTheme } = useTheme()

  const vehicles = useMyVehicles()
  const paymentMethods = useMyPaymentMethods()
  const preferences = useMyPreferences()
  const identity = useIdentityVerification()

  const updateProfile = useUpdateProfile()
  const updatePreferences = useUpdatePreferences()
  const addVehicle = useAddVehicle()
  const deleteVehicle = useDeleteVehicle()
  const addPaymentMethod = useAddPaymentMethod()
  const deletePaymentMethod = useDeletePaymentMethod()
  const submitIdentity = useSubmitIdentity()

  const [profileOpen, setProfileOpen] = useState(false)
  const [vehicleOpen, setVehicleOpen] = useState(false)
  const [momoOpen, setMomoOpen] = useState(false)
  const [identityOpen, setIdentityOpen] = useState(false)

  const user = me.data?.data

  if (me.isPending || !user) {
    return (
      <PageContainer width="md">
        <Card className="flex items-center gap-4 p-5">
          <Skeleton className="size-16 rounded-full" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-4 w-28" />
          </div>
        </Card>
      </PageContainer>
    )
  }

  const identityData = identity.data?.data
  const identityState = IDENTITY_LABEL[identityData?.status ?? 'NOT_SUBMITTED']
  const IdentityIcon = identityState.icon
  const prefs = preferences.data?.data

  return (
    <PageContainer width="md" className="pb-10">
      <PageHeader title="Mon compte" back={false} />

      {/* --- Carte d'identite --- */}
      <Card className="p-5">
        <div className="flex items-start gap-4">
          <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={64} />
          <div className="min-w-0 flex-1">
            <h2 className="font-display text-[20px] font-extrabold tracking-[-0.03em]">
              {user.firstName} {user.lastName}
            </h2>
            <p className="tnum text-[14px] text-muted">{formatPhone(user.phone)}</p>
            {user.ratingCount > 0 ? <RatingStars value={user.ratingAvg} count={user.ratingCount} className="mt-1" /> : null}
          </div>
          <Button variant="secondary" size="sm" onClick={() => setProfileOpen(true)}>
            <UserCog className="size-4" aria-hidden />
            <span className="hidden sm:inline">Modifier</span>
          </Button>
        </div>
        <div className="mt-3 flex flex-wrap gap-1.5">
          {user.phoneVerified ? (
            <Badge tone="success">
              <Check aria-hidden />
              Téléphone confirmé
            </Badge>
          ) : (
            <Badge tone="warning">Téléphone non confirmé</Badge>
          )}
          <Badge tone={identityState.tone}>
            <IdentityIcon aria-hidden />
            {identityState.label}
          </Badge>
        </div>
        {user.bio ? <p className="mt-3 text-[14px] leading-relaxed text-ink-2">{user.bio}</p> : null}
      </Card>

      <Tabs defaultValue="vehicles" className="mt-5">
        <TabsList>
          <TabsTrigger value="vehicles">Véhicules</TabsTrigger>
          <TabsTrigger value="identity">Identité</TabsTrigger>
          <TabsTrigger value="payment">Paiement</TabsTrigger>
          <TabsTrigger value="preferences">Préférences</TabsTrigger>
        </TabsList>

        {/* --------------------------------------------------- Vehicules */}
        <TabsContent value="vehicles">
          <SectionTitle
            action={
              <button
                type="button"
                onClick={() => setVehicleOpen(true)}
                className="flex items-center gap-1 text-[13px] font-semibold text-[var(--indigo)] underline-offset-4 hover:underline"
              >
                <Plus className="size-3.5" aria-hidden />
                Ajouter
              </button>
            }
          >
            Mes véhicules
          </SectionTitle>

          {vehicles.isPending ? (
            <Skeleton className="h-20 rounded-[var(--radius-card)]" />
          ) : (vehicles.data?.data.length ?? 0) === 0 ? (
            <Card className="p-5 text-center">
              <Car className="mx-auto size-6 text-muted" aria-hidden />
              <p className="mt-2 text-[14px] text-ink-2">Aucun véhicule enregistré.</p>
              <Button size="sm" className="mt-3" onClick={() => setVehicleOpen(true)}>
                Ajouter un véhicule
              </Button>
            </Card>
          ) : (
            <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
              {vehicles.data!.data.map((vehicle) => (
                <motion.li key={vehicle.id} variants={listItem}>
                  <Card className="flex items-center gap-3 p-4">
                    <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--surface-calm)] text-ink-2">
                      <Car className="size-5" aria-hidden />
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-display text-[15px] font-bold">
                        {vehicle.brand} {vehicle.model}
                      </p>
                      <p className="tnum text-[13px] text-muted">
                        {vehicle.plate} · {vehicle.seats} places
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
                    <button
                      type="button"
                      aria-label={`Supprimer ${vehicle.brand} ${vehicle.model}`}
                      onClick={() => {
                        deleteVehicle.mutate(vehicle.id)
                        toast.success('Véhicule supprimé')
                      }}
                      className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] text-muted transition-colors hover:bg-[var(--vermillon-soft)] hover:text-[var(--vermillon)]"
                    >
                      <Trash2 className="size-4" aria-hidden />
                    </button>
                  </Card>
                </motion.li>
              ))}
            </motion.ul>
          )}
        </TabsContent>

        {/* ---------------------------------------------------- Identite */}
        <TabsContent value="identity">
          <Card className="p-5">
            <div className="flex items-start gap-3">
              <span
                className={
                  identityData?.status === 'APPROVED'
                    ? 'flex size-11 shrink-0 items-center justify-center rounded-full bg-[var(--vert-soft)] text-[var(--vert)]'
                    : 'flex size-11 shrink-0 items-center justify-center rounded-full bg-[var(--ocre-soft)] text-[var(--ocre-ink)]'
                }
              >
                <IdentityIcon className="size-5" aria-hidden />
              </span>
              <div className="min-w-0 flex-1">
                <p className="font-display text-[16px] font-bold">{identityState.label}</p>
                {identityData?.documentType ? (
                  <p className="text-[13px] text-muted">
                    {DOCUMENT_LABEL[identityData.documentType]}
                    {identityData.submittedAt ? ` · envoyé ${formatFromNow(identityData.submittedAt)}` : ''}
                  </p>
                ) : (
                  <p className="text-[13px] text-muted">
                    La vérification d'identité rassure les passagers et augmente vos réservations.
                  </p>
                )}
                {identityData?.rejectionReason ? (
                  <p className="mt-2 rounded-[var(--radius-control)] bg-[var(--vermillon-soft)] px-3 py-2 text-[13px] text-[var(--vermillon)]">
                    {identityData.rejectionReason}
                  </p>
                ) : null}
              </div>
            </div>

            {identityData?.status !== 'APPROVED' && identityData?.status !== 'PENDING' ? (
              <Button block className="mt-4" onClick={() => setIdentityOpen(true)}>
                Vérifier mon identité
              </Button>
            ) : null}
          </Card>
        </TabsContent>

        {/* ---------------------------------------------------- Paiement */}
        <TabsContent value="payment">
          <SectionTitle
            action={
              <button
                type="button"
                onClick={() => setMomoOpen(true)}
                className="flex items-center gap-1 text-[13px] font-semibold text-[var(--indigo)] underline-offset-4 hover:underline"
              >
                <Plus className="size-3.5" aria-hidden />
                Ajouter
              </button>
            }
          >
            Comptes mobile money
          </SectionTitle>

          {paymentMethods.isPending ? (
            <Skeleton className="h-20 rounded-[var(--radius-card)]" />
          ) : (paymentMethods.data?.data.length ?? 0) === 0 ? (
            <Card className="p-5 text-center">
              <CreditCard className="mx-auto size-6 text-muted" aria-hidden />
              <p className="mt-2 text-[14px] text-ink-2">Aucun compte enregistré.</p>
            </Card>
          ) : (
            <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
              {paymentMethods.data!.data.map((method) => (
                <motion.li key={method.id} variants={listItem}>
                  <Card className="flex items-center gap-3 p-4">
                    <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--indigo-soft)] text-[var(--indigo)]">
                      <Smartphone className="size-5" aria-hidden />
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="font-display text-[15px] font-bold">
                        {providerLabel(method.provider)}
                      </p>
                      <p className="tnum text-[13px] text-muted">{formatPhone(method.phone)}</p>
                    </div>
                    {method.isDefault ? <Badge tone="indigo">Par défaut</Badge> : null}
                    <button
                      type="button"
                      aria-label={`Supprimer le compte ${formatPhone(method.phone)}`}
                      onClick={() => {
                        deletePaymentMethod.mutate(method.id)
                        toast.success('Compte supprimé')
                      }}
                      className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] text-muted transition-colors hover:bg-[var(--vermillon-soft)] hover:text-[var(--vermillon)]"
                    >
                      <Trash2 className="size-4" aria-hidden />
                    </button>
                  </Card>
                </motion.li>
              ))}
            </motion.ul>
          )}

          <p className="mt-3 text-[13px] leading-relaxed text-muted">
            Ces comptes servent à régler l'acompte de vos réservations et, si vous conduisez, à recevoir vos
            reversements.
          </p>
        </TabsContent>

        {/* ------------------------------------------------- Preferences */}
        <TabsContent value="preferences">
          <SectionTitle>Notifications</SectionTitle>
          <Card className="divide-y divide-rule">
            <SettingRow title="Notifications push" description="Confirmations, messages, rappels">
              <Switch
                checked={prefs?.notifyByPush ?? false}
                onCheckedChange={(checked) => updatePreferences.mutate({ notifyByPush: checked })}
                aria-label="Notifications push"
              />
            </SettingRow>
            <SettingRow title="SMS" description="Uniquement les informations critiques">
              <Switch
                checked={prefs?.notifyBySms ?? false}
                onCheckedChange={(checked) => updatePreferences.mutate({ notifyBySms: checked })}
                aria-label="Notifications par SMS"
              />
            </SettingRow>
            <SettingRow title="E-mail" description="Reçus et récapitulatifs">
              <Switch
                checked={prefs?.notifyByEmail ?? false}
                onCheckedChange={(checked) => updatePreferences.mutate({ notifyByEmail: checked })}
                aria-label="Notifications par e-mail"
              />
            </SettingRow>
          </Card>

          <SectionTitle className="mt-5">À bord</SectionTitle>
          <Card className="divide-y divide-rule">
            <SettingRow title="Fumeur accepté">
              <Switch
                checked={prefs?.smoking ?? false}
                onCheckedChange={(checked) => updatePreferences.mutate({ smoking: checked })}
                aria-label="Fumeur accepté"
              />
            </SettingRow>
            <SettingRow title="Musique pendant le trajet">
              <Switch
                checked={prefs?.music ?? false}
                onCheckedChange={(checked) => updatePreferences.mutate({ music: checked })}
                aria-label="Musique pendant le trajet"
              />
            </SettingRow>
            <SettingRow title="Animaux acceptés">
              <Switch
                checked={prefs?.pets ?? false}
                onCheckedChange={(checked) => updatePreferences.mutate({ pets: checked })}
                aria-label="Animaux acceptés"
              />
            </SettingRow>
          </Card>

          <SectionTitle className="mt-5">Apparence</SectionTitle>
          <Card className="p-3">
            <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-label="Thème de l'interface">
              {(
                [
                  { value: 'light', label: 'Clair', icon: Sun },
                  { value: 'dark', label: 'Sombre', icon: Moon },
                  { value: 'system', label: 'Système', icon: Monitor },
                ] as const
              ).map((option) => (
                <button
                  key={option.value}
                  type="button"
                  role="radio"
                  aria-checked={mode === option.value}
                  onClick={() => setTheme(option.value)}
                  className={
                    mode === option.value
                      ? 'flex min-h-[56px] flex-col items-center justify-center gap-1 rounded-[var(--radius-control)] border border-[var(--indigo)] bg-[var(--indigo-soft)] text-[13px] font-semibold text-[var(--indigo-deep)]'
                      : 'flex min-h-[56px] flex-col items-center justify-center gap-1 rounded-[var(--radius-control)] border border-rule-strong bg-surface text-[13px] font-medium text-ink-2'
                  }
                >
                  <option.icon className="size-[18px]" aria-hidden />
                  {option.label}
                </button>
              ))}
            </div>
          </Card>

          <Button variant="ghost" block className="mt-5 text-[var(--vermillon)]" onClick={logout}>
            <LogOut className="size-4" aria-hidden />
            Se déconnecter
          </Button>
        </TabsContent>
      </Tabs>

      {/* ------------------------------------------------------- Feuilles */}
      <ProfileSheet
        open={profileOpen}
        onOpenChange={setProfileOpen}
        initial={{ firstName: user.firstName, lastName: user.lastName, email: user.email ?? '', bio: user.bio ?? '' }}
        pending={updateProfile.isPending}
        onSubmit={(values) =>
          updateProfile.mutate(values, {
            onSuccess: () => {
              setProfileOpen(false)
              toast.success('Profil mis à jour')
            },
            onError: () => toast.error('La mise à jour a échoué.'),
          })
        }
      />

      <VehicleSheet
        open={vehicleOpen}
        onOpenChange={setVehicleOpen}
        pending={addVehicle.isPending}
        onSubmit={(values) =>
          addVehicle.mutate(values, {
            onSuccess: () => {
              setVehicleOpen(false)
              toast.success('Véhicule ajouté', { description: 'Il sera vérifié sous 48 h.' })
            },
            onError: () => toast.error("Le véhicule n'a pas pu être ajouté."),
          })
        }
      />

      <MomoSheet
        open={momoOpen}
        onOpenChange={setMomoOpen}
        pending={addPaymentMethod.isPending}
        defaultPhone={user.phone}
        onSubmit={(values) =>
          addPaymentMethod.mutate(values, {
            onSuccess: () => {
              setMomoOpen(false)
              toast.success('Compte mobile money ajouté')
            },
            onError: () => toast.error("Le compte n'a pas pu être ajouté."),
          })
        }
      />

      <IdentitySheet
        open={identityOpen}
        onOpenChange={setIdentityOpen}
        pending={submitIdentity.isPending}
        onSubmit={(values) =>
          submitIdentity.mutate(values, {
            onSuccess: () => {
              setIdentityOpen(false)
              toast.success('Document envoyé', { description: 'Vérification sous 24 à 48 h.' })
            },
            onError: () => toast.error("L'envoi a échoué."),
          })
        }
      />
    </PageContainer>
  )
}

/* ------------------------------------------------------------- Formulaires */

function ProfileSheet({
  open,
  onOpenChange,
  initial,
  pending,
  onSubmit,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  initial: { firstName: string; lastName: string; email: string; bio: string }
  pending: boolean
  onSubmit: (values: { firstName: string; lastName: string; email: string | null; bio: string | null }) => void
}) {
  const [values, setValues] = useState(initial)

  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title="Modifier mon profil"
      footer={
        <Button
          size="lg"
          block
          loading={pending}
          onClick={() =>
            onSubmit({
              firstName: values.firstName,
              lastName: values.lastName,
              email: values.email || null,
              bio: values.bio || null,
            })
          }
        >
          Enregistrer
        </Button>
      }
    >
      <div className="space-y-3 py-2">
        <Input
          label="Prénom"
          value={values.firstName}
          onChange={(e) => setValues((v) => ({ ...v, firstName: e.target.value }))}
        />
        <Input
          label="Nom"
          value={values.lastName}
          onChange={(e) => setValues((v) => ({ ...v, lastName: e.target.value }))}
        />
        <Input
          label="E-mail (facultatif)"
          type="email"
          value={values.email}
          onChange={(e) => setValues((v) => ({ ...v, email: e.target.value }))}
        />
        <Input
          label="Présentation"
          hint="Visible par les passagers sur votre profil public."
          value={values.bio}
          onChange={(e) => setValues((v) => ({ ...v, bio: e.target.value }))}
        />
      </div>
    </Sheet>
  )
}

function VehicleSheet({
  open,
  onOpenChange,
  pending,
  onSubmit,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  pending: boolean
  onSubmit: (values: {
    brand: string
    model: string
    color: string
    plate: string
    seats: number
    comfortLevel: ComfortLevel
  }) => void
}) {
  const [values, setValues] = useState({
    brand: '',
    model: '',
    color: '',
    plate: '',
    seats: 4,
    comfortLevel: 'COMFORT' as ComfortLevel,
  })
  const valid = values.brand.trim() && values.model.trim() && values.plate.trim()

  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title="Ajouter un véhicule"
      description="Il sera vérifié avant votre première publication."
      footer={
        <Button size="lg" block loading={pending} disabled={!valid} onClick={() => onSubmit(values)}>
          Ajouter
        </Button>
      }
    >
      <div className="space-y-3 py-2">
        <div className="grid grid-cols-2 gap-3">
          <Input
            label="Marque"
            placeholder="Toyota"
            value={values.brand}
            onChange={(e) => setValues((v) => ({ ...v, brand: e.target.value }))}
          />
          <Input
            label="Modèle"
            placeholder="Corolla"
            value={values.model}
            onChange={(e) => setValues((v) => ({ ...v, model: e.target.value }))}
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Input
            label="Couleur"
            placeholder="Gris"
            value={values.color}
            onChange={(e) => setValues((v) => ({ ...v, color: e.target.value }))}
          />
          <Input
            label="Immatriculation"
            placeholder="AB 1234 RB"
            className="tnum uppercase"
            value={values.plate}
            onChange={(e) => setValues((v) => ({ ...v, plate: e.target.value.toUpperCase() }))}
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Input
            label="Places (hors conducteur)"
            type="number"
            min={1}
            max={8}
            className="tnum"
            value={String(values.seats)}
            onChange={(e) => setValues((v) => ({ ...v, seats: Number(e.target.value) || 1 }))}
          />
          <div className="flex flex-col gap-1.5">
            <span className="text-[13px] font-medium text-ink-2">Confort</span>
            <Select
              value={values.comfortLevel}
              onValueChange={(value) => setValues((v) => ({ ...v, comfortLevel: value as ComfortLevel }))}
            >
              <SelectTrigger aria-label="Niveau de confort">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="BASIC">Confort simple</SelectItem>
                <SelectItem value="COMFORT">Confortable</SelectItem>
                <SelectItem value="PREMIUM">Haut de gamme</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </div>
    </Sheet>
  )
}

function MomoSheet({
  open,
  onOpenChange,
  pending,
  defaultPhone,
  onSubmit,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  pending: boolean
  defaultPhone: string
  onSubmit: (values: { provider: PaymentProvider; phone: string }) => void
}) {
  const [provider, setProvider] = useState<PaymentProvider>('MTN_MOMO')
  const [phone, setPhone] = useState(defaultPhone)

  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title="Ajouter un compte mobile money"
      footer={
        <Button size="lg" block loading={pending} onClick={() => onSubmit({ provider, phone })}>
          Ajouter
        </Button>
      }
    >
      <div className="space-y-3 py-2">
        <div className="flex flex-col gap-1.5">
          <span className="text-[13px] font-medium text-ink-2">Opérateur</span>
          <Select value={provider} onValueChange={(value) => setProvider(value as PaymentProvider)}>
            <SelectTrigger aria-label="Opérateur mobile money">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PROVIDERS.map((item) => (
                <SelectItem key={item.value} value={item.value}>
                  {item.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <Input
          label="Numéro"
          type="tel"
          inputMode="tel"
          className="tnum"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
        />
      </div>
    </Sheet>
  )
}

function IdentitySheet({
  open,
  onOpenChange,
  pending,
  onSubmit,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  pending: boolean
  onSubmit: (values: { documentType: 'CNI' | 'PASSPORT' | 'DRIVER_LICENSE'; documentNumber: string }) => void
}) {
  const [documentType, setDocumentType] = useState<'CNI' | 'PASSPORT' | 'DRIVER_LICENSE'>('CNI')
  const [documentNumber, setDocumentNumber] = useState('')

  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title="Vérifier mon identité"
      description="Vos données servent uniquement à la vérification et ne sont jamais publiées."
      footer={
        <Button
          size="lg"
          block
          loading={pending}
          disabled={!documentNumber.trim()}
          onClick={() => onSubmit({ documentType, documentNumber })}
        >
          Envoyer pour vérification
        </Button>
      }
    >
      <div className="space-y-3 py-2">
        <div className="flex flex-col gap-1.5">
          <span className="text-[13px] font-medium text-ink-2">Type de document</span>
          <Select value={documentType} onValueChange={(value) => setDocumentType(value as typeof documentType)}>
            <SelectTrigger aria-label="Type de document">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="CNI">Carte nationale d'identité</SelectItem>
              <SelectItem value="PASSPORT">Passeport</SelectItem>
              <SelectItem value="DRIVER_LICENSE">Permis de conduire</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <Input
          label="Numéro du document"
          className="tnum uppercase"
          value={documentNumber}
          onChange={(e) => setDocumentNumber(e.target.value.toUpperCase())}
        />
        {/* TODO(backend) : ajouter le televersement de la photo du document
            (POST /api/v1/me/identity, multipart) quand le stockage sera en place. */}
        <p className="rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2.5 text-[13px] leading-relaxed text-ink-2">
          Un agent Ekuiseo vérifie votre document sous 24 à 48 h. Vous recevrez une notification à la fin du contrôle.
        </p>
      </div>
    </Sheet>
  )
}
