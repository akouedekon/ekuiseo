import { m } from 'motion/react'
import {
  BadgeCheck,
  CalendarCheck,
  Car,
  Cigarette,
  Dog,
  Flag,
  MessageCircle,
  Music,
  Phone,
  Route,
  ShieldCheck,
  Timer,
} from 'lucide-react'
import { useState } from 'react'
import { useParams } from 'react-router'
import { ReportDialog } from '@/components/feedback/ReportDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, RatingStars, Separator, Skeleton } from '@/components/ui/misc'
import { ErrorState, OfflineState, isOfflineWithoutData } from '@/components/ui/states'
import { PageContainer, PageHeader, SectionTitle } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import { useIsAuthenticated, useMe } from '@/hooks/useAuth'
import { usePublicUser, useUserReviews } from '@/hooks/useReviews'
import { describeError, isDefinitiveError } from '@/lib/errors'
import { formatDuration, formatFromNow, formatRating } from '@/lib/format'
import { CHATTY_LABEL, COMFORT_LABEL } from '@/lib/labels'
import { listContainer, listItem } from '@/lib/motion'
import type { ReviewResponse } from '@/api/types'

/**
 * Profil public d'un membre. `lastName` peut n'etre qu'une initiale (« A. »)
 * pour un visiteur non connecte : on l'affiche tel quel, sans presumer d'un nom
 * complet. Les avis recus sont separes par role : comme conducteur (section
 * principale) et comme passager (confiance bilaterale, section 5 #20).
 */
export function DriverProfilePage() {
  const { id } = useParams<{ id: string }>()
  const profile = usePublicUser(id)
  const reviews = useUserReviews(id)
  const authed = useIsAuthenticated()
  const me = useMe()
  const [reportOpen, setReportOpen] = useState(false)

  if (isOfflineWithoutData(profile)) {
    return (
      <PageContainer width="md">
        <PageMeta title="Profil" />
        <PageHeader title="Profil" backTo="/" />
        <OfflineState
          description="Ce profil n'a pas encore été enregistré sur cet appareil. Il s'affichera dès que la connexion reviendra."
          onRetry={() => profile.refetch()}
        />
      </PageContainer>
    )
  }

  if (profile.isPending) {
    return (
      <PageContainer width="md">
        <PageMeta title="Profil" />
        <PageHeader title="Profil" backTo="/" />
        <Card className="flex items-center gap-4 p-5">
          <Skeleton className="size-16 rounded-full" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-4 w-28" />
          </div>
        </Card>
        <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-20 rounded-[var(--radius-card)]" />
          ))}
        </div>
      </PageContainer>
    )
  }

  if (profile.isError || !profile.data) {
    const final = isDefinitiveError(profile.error)
    return (
      <PageContainer width="md">
        <PageMeta title="Profil introuvable" noindex />
        <PageHeader title="Profil" backTo="/" />
        <ErrorState
          title={final ? 'Profil introuvable' : 'Chargement impossible'}
          description={final ? "Ce membre n'existe pas ou son compte a été fermé." : describeError(profile.error)}
          onRetry={final ? undefined : () => profile.refetch()}
        />
      </PageContainer>
    )
  }

  const user = profile.data
  const fullName = `${user.firstName} ${user.lastName}`.trim()
  const allReviews = reviews.data ?? []
  // `role` = role de la personne notee : DRIVER quand un passager a note ce membre comme conducteur.
  const driverReviews = allReviews.filter((r) => r.role === 'DRIVER')
  const passengerReviews = allReviews.filter((r) => r.role === 'PASSENGER')
  const distribution = [5, 4, 3, 2, 1].map((star) => ({
    star,
    count: driverReviews.filter((r) => Math.round(r.rating) === star).length,
  }))
  const maxCount = Math.max(1, ...distribution.map((d) => d.count))
  const isDriver = user.vehicles.length > 0 || user.tripsCompleted > 0 || driverReviews.length > 0

  return (
    <PageContainer width="md" className="pb-10">
      <PageMeta
        title={`${fullName} · profil`}
        description={`${fullName} sur Ekuiseo : ${user.tripsCompleted} trajet${user.tripsCompleted > 1 ? 's' : ''} effectué${user.tripsCompleted > 1 ? 's' : ''}, ${user.ratingCount} avis.`}
      />
      {/* En-tete avec retour (audit F221) : un lien partage arrive ici sans historique, le repli mene a l'accueil. */}
      {/* Sous 768 px la barre haute dit deja « Profil » : la carte d identite ouvre l ecran. */}
      <PageHeader title={isDriver ? 'Profil conducteur' : 'Profil'} backTo="/" className="mb-4" mobileTitle={false} />

      {/* --- Identite --- */}
      <Card className="p-5">
        <div className="flex items-start gap-4">
          <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={64} />
          <div className="min-w-0 flex-1">
            <h2 className="headline text-display">{fullName}</h2>
            <RatingStars value={user.ratingAvg} count={user.ratingCount} className="mt-1" />
            <p className="mt-1 text-label text-muted">Inscrit {formatFromNow(user.memberSince)}</p>
          </div>
        </div>

        <div className="mt-4 flex flex-wrap gap-1.5">
          {user.identityVerified ? (
            <Badge tone="success">
              <ShieldCheck aria-hidden />
              Identité vérifiée
            </Badge>
          ) : (
            <Badge tone="neutral">Identité non vérifiée</Badge>
          )}
          {user.phoneVerified ? (
            <Badge tone="success">
              <Phone aria-hidden />
              Téléphone confirmé
            </Badge>
          ) : null}
          {user.ratingAvg >= 4.8 && user.ratingCount >= 20 ? (
            <Badge tone="indigo">
              <BadgeCheck aria-hidden />
              Conducteur d'excellence
            </Badge>
          ) : null}
        </div>

        {user.bio ? (
          <>
            <Separator className="my-4" />
            <p className="text-body leading-relaxed text-ink-2">{user.bio}</p>
          </>
        ) : null}
      </Card>

      {/* --- Statistiques --- */}
      <m.div
        variants={listContainer}
        initial="hidden"
        animate="show"
        className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4"
      >
        <Stat icon={Route} label="Trajets effectués" value={String(user.tripsCompleted)} />
        <Stat icon={CalendarCheck} label="Trajets honorés" value={user.reliabilityRate == null ? '—' : `${user.reliabilityRate} %`} />
        <Stat
          icon={Timer}
          label="Répond en"
          value={user.responseTimeMinutes ? formatDuration(user.responseTimeMinutes) : '—'}
        />
        <Stat icon={MessageCircle} label="Avis reçus" value={String(user.ratingCount)} />
      </m.div>

      {/* --- Vehicules --- */}
      {user.vehicles.length > 0 ? (
        <section className="mt-6">
          <SectionTitle>Véhicules</SectionTitle>
          <div className="space-y-2">
            {user.vehicles.map((vehicle) => (
              <Card key={vehicle.id} className="flex items-center gap-3 p-4">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-ink-2">
                  <Car className="size-5" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-display text-base font-bold">
                    {vehicle.brand} {vehicle.model}
                  </p>
                  <p className="text-label text-muted">
                    {vehicle.color ?? 'Couleur non précisée'} · {COMFORT_LABEL[vehicle.comfortLevel]}
                  </p>
                </div>
              </Card>
            ))}
          </div>
        </section>
      ) : null}

      {/* --- Preferences a bord --- */}
      {user.preferences ? (
        <section className="mt-6">
          <SectionTitle>À bord</SectionTitle>
          <Card className="flex flex-wrap gap-1.5 p-4">
            <Badge tone={user.preferences.smoking ? 'neutral' : 'outline'}>
              <Cigarette aria-hidden />
              {user.preferences.smoking ? 'Fumeur accepté' : 'Non-fumeur'}
            </Badge>
            <Badge tone={user.preferences.music ? 'neutral' : 'outline'}>
              <Music aria-hidden />
              {user.preferences.music ? 'Musique' : 'Sans musique'}
            </Badge>
            <Badge tone={user.preferences.pets ? 'neutral' : 'outline'}>
              <Dog aria-hidden />
              {user.preferences.pets ? 'Animaux acceptés' : 'Sans animaux'}
            </Badge>
            <Badge tone="outline">{CHATTY_LABEL[user.preferences.chatty]}</Badge>
          </Card>
        </section>
      ) : null}

      {/* --- Avis recus comme conducteur --- */}
      <section className="mt-6">
        <SectionTitle>Avis des passagers</SectionTitle>

        {driverReviews.length > 0 ? (
          <Card className="mb-3 flex items-center gap-5 p-4">
            <div className="shrink-0 text-center">
              <p className="tnum font-display text-display-lg font-extrabold leading-none tracking-[-0.03em]">
                {formatRating(user.ratingAvg)}
              </p>
              <RatingStars value={user.ratingAvg} size={12} className="mt-1 [&>span:not(:first-child)]:hidden" />
              <p className="mt-1 text-caption text-muted">{user.ratingCount} avis</p>
            </div>
            {/* Repartition : barres horizontales, sobres */}
            <div className="min-w-0 flex-1 space-y-1">
              {distribution.map((row) => (
                <div key={row.star} className="flex items-center gap-2">
                  <span className="tnum w-3 text-caption text-muted">{row.star}</span>
                  <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
                    <m.span
                      className="block h-full rounded-full bg-accent"
                      initial={{ scaleX: 0 }}
                      animate={{ scaleX: row.count / maxCount }}
                      style={{ originX: 0 }}
                      transition={{ duration: 0.4, delay: 0.05 * (5 - row.star) }}
                    />
                  </span>
                  <span className="tnum w-5 text-right text-caption text-muted">{row.count}</span>
                </div>
              ))}
            </div>
          </Card>
        ) : null}

        <ReviewList query={reviews} list={driverReviews} emptyText="Aucun avis de passager pour l'instant." />
      </section>

      {/* --- Avis recus comme passager (confiance bilaterale) --- */}
      {passengerReviews.length > 0 ? (
        <section className="mt-6">
          <SectionTitle>Avis des conducteurs</SectionTitle>
          <ReviewList query={reviews} list={passengerReviews} emptyText="Aucun avis de conducteur pour l'instant." />
        </section>
      ) : null}

      {authed && me.data?.id !== user.id ? (
        <div className="mt-6 flex justify-end">
          <Button variant="ghost" size="sm" className="text-muted" onClick={() => setReportOpen(true)}>
            <Flag className="size-4" aria-hidden />
            Signaler ce membre
          </Button>
        </div>
      ) : null}

      <ReportDialog open={reportOpen} onOpenChange={setReportOpen} target={{ userId: user.id, label: fullName }} />
    </PageContainer>
  )
}

function ReviewList({
  query,
  list,
  emptyText,
}: {
  query: ReturnType<typeof useUserReviews>
  list: ReviewResponse[]
  emptyText: string
}) {
  if (query.isPending) {
    return (
      <Card className="space-y-2 p-4">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-4 w-full" />
      </Card>
    )
  }
  if (query.isError) {
    return (
      <Card className="flex flex-wrap items-center justify-between gap-2 p-4 text-body text-muted">
        Avis indisponibles pour l'instant.
        <Button variant="secondary" size="sm" onClick={() => query.refetch()}>
          Réessayer
        </Button>
      </Card>
    )
  }
  if (list.length === 0) return <Card className="p-4 text-body text-muted">{emptyText}</Card>
  return (
    <m.div variants={listContainer} initial="hidden" animate="show" className="space-y-2">
      {list.map((review) => (
        <m.div key={review.id} variants={listItem}>
          <Card className="p-4">
            <div className="flex items-center justify-between gap-3">
              <RatingStars value={review.rating} size={13} />
              <span className="shrink-0 text-caption text-muted">{formatFromNow(review.createdAt)}</span>
            </div>
            {review.comment ? (
              <p className="mt-1.5 text-body leading-relaxed text-ink-2">{review.comment}</p>
            ) : (
              <p className="mt-1.5 text-body italic text-muted">Note sans commentaire.</p>
            )}
          </Card>
        </m.div>
      ))}
    </m.div>
  )
}

function Stat({
  icon: Icon,
  label,
  value,
}: {
  icon: typeof Route
  label: string
  value: string
}) {
  return (
    <m.div variants={listItem}>
      <Card className="p-3.5">
        <Icon className="size-4 text-muted" aria-hidden />
        <p className="tnum mt-2 font-display text-heading font-extrabold leading-none tracking-[-0.02em]">{value}</p>
        <p className="mt-1 text-caption leading-tight text-muted">{label}</p>
      </Card>
    </m.div>
  )
}
