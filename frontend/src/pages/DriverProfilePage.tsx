import { motion } from 'motion/react'
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
import { ErrorState } from '@/components/ui/states'
import { PageContainer, SectionTitle } from '@/components/layout/PageContainer'
import { useIsAuthenticated, useMe } from '@/hooks/useAuth'
import { usePublicUser, useUserReviews } from '@/hooks/useReviews'
import { formatDuration, formatFromNow, formatRating } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'

export function DriverProfilePage() {
  const { id } = useParams<{ id: string }>()
  const profile = usePublicUser(id)
  const reviews = useUserReviews(id)
  const authed = useIsAuthenticated()
  const me = useMe()
  const [reportOpen, setReportOpen] = useState(false)

  if (profile.isPending) {
    return (
      <PageContainer width="md">
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
    return (
      <PageContainer width="md">
        <ErrorState title="Profil introuvable" onRetry={() => profile.refetch()} />
      </PageContainer>
    )
  }

  const user = profile.data
  const reviewList = reviews.data ?? []
  const distribution = [5, 4, 3, 2, 1].map((star) => ({
    star,
    count: reviewList.filter((r) => Math.round(r.rating) === star).length,
  }))
  const maxCount = Math.max(1, ...distribution.map((d) => d.count))

  return (
    <PageContainer width="md" className="pb-10">

      {/* --- Identite --- */}
      <Card className="p-5">
        <div className="flex items-start gap-4">
          <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={64} />
          <div className="min-w-0 flex-1">
            <h1 className="headline text-[24px]">
              {user.firstName} {user.lastName}
            </h1>
            <RatingStars value={user.ratingAvg} count={user.ratingCount} className="mt-1" />
            <p className="mt-1 text-[13px] text-muted">Inscrit {formatFromNow(user.memberSince)}</p>
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
            <p className="text-[14px] leading-relaxed text-ink-2">{user.bio}</p>
          </>
        ) : null}
      </Card>

      {/* --- Statistiques --- */}
      <motion.div
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
      </motion.div>

      {/* --- Vehicules --- */}
      {user.vehicles.length > 0 ? (
        <section className="mt-6">
          <SectionTitle>Véhicules</SectionTitle>
          <div className="space-y-2">
            {user.vehicles.map((vehicle) => (
              <Card key={vehicle.id} className="flex items-center gap-3 p-4">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--surface-calm)] text-ink-2">
                  <Car className="size-5" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-display text-[15px] font-bold">
                    {vehicle.brand} {vehicle.model}
                  </p>
                  <p className="text-[13px] text-muted">
                    {vehicle.color ?? 'Couleur non précisée'} ·{' '}
                    {
                      { BASIC: 'Confort simple', COMFORT: 'Confortable', PREMIUM: 'Haut de gamme' }[
                        vehicle.comfortLevel
                      ]
                    }
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
            <Badge tone="outline">
              {
                { QUIET: 'Trajet calme', DEPENDS: 'Discussion selon l’humeur', TALKATIVE: 'Aime discuter' }[
                  user.preferences.chatty
                ]
              }
            </Badge>
          </Card>
        </section>
      ) : null}

      {/* --- Avis --- */}
      <section className="mt-6">
        <SectionTitle>Avis des passagers</SectionTitle>

        {reviewList.length > 0 ? (
          <Card className="mb-3 flex items-center gap-5 p-4">
            <div className="shrink-0 text-center">
              <p className="tnum font-display text-[34px] font-extrabold leading-none tracking-[-0.03em]">
                {formatRating(user.ratingAvg)}
              </p>
              <RatingStars value={user.ratingAvg} size={12} className="mt-1 [&>span:not(:first-child)]:hidden" />
              <p className="mt-1 text-[12px] text-muted">{user.ratingCount} avis</p>
            </div>
            {/* Repartition : barres horizontales, sobres */}
            <div className="min-w-0 flex-1 space-y-1">
              {distribution.map((row) => (
                <div key={row.star} className="flex items-center gap-2">
                  <span className="tnum w-3 text-[12px] text-muted">{row.star}</span>
                  <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-[var(--surface-calm)]">
                    <motion.span
                      className="block h-full rounded-full bg-[var(--ocre)]"
                      initial={{ scaleX: 0 }}
                      animate={{ scaleX: row.count / maxCount }}
                      style={{ originX: 0 }}
                      transition={{ duration: 0.4, delay: 0.05 * (5 - row.star) }}
                    />
                  </span>
                  <span className="tnum w-5 text-right text-[12px] text-muted">{row.count}</span>
                </div>
              ))}
            </div>
          </Card>
        ) : null}

        {reviews.isPending ? (
          <Card className="space-y-2 p-4">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-4 w-full" />
          </Card>
        ) : reviews.isError ? (
          <Card className="flex flex-wrap items-center justify-between gap-2 p-4 text-[14px] text-muted">
            Avis indisponibles pour l'instant.
            <Button variant="secondary" size="sm" onClick={() => reviews.refetch()}>
              Réessayer
            </Button>
          </Card>
        ) : reviewList.length === 0 ? (
          <Card className="p-4 text-[14px] text-muted">Aucun avis pour l'instant.</Card>
        ) : (
          <motion.div variants={listContainer} initial="hidden" animate="show" className="space-y-2">
            {reviewList.map((review) => (
              <motion.div key={review.id} variants={listItem}>
                <Card className="p-4">
                  <div className="flex items-center justify-between gap-3">
                    <RatingStars value={review.rating} size={13} />
                    <span className="shrink-0 text-[12px] text-muted">{formatFromNow(review.createdAt)}</span>
                  </div>
                  {review.comment ? (
                    <p className="mt-1.5 text-[14px] leading-relaxed text-ink-2">{review.comment}</p>
                  ) : (
                    <p className="mt-1.5 text-[14px] italic text-muted">Note sans commentaire.</p>
                  )}
                </Card>
              </motion.div>
            ))}
          </motion.div>
        )}
      </section>

      {authed && me.data?.id !== user.id ? (
        <div className="mt-6 flex justify-end">
          <Button variant="ghost" size="sm" className="text-muted" onClick={() => setReportOpen(true)}>
            <Flag className="size-4" aria-hidden />
            Signaler ce membre
          </Button>
        </div>
      ) : null}

      <ReportDialog
        open={reportOpen}
        onOpenChange={setReportOpen}
        target={{ userId: user.id, label: `${user.firstName} ${user.lastName}` }}
      />
    </PageContainer>
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
    <motion.div variants={listItem}>
      <Card className="p-3.5">
        <Icon className="size-4 text-muted" aria-hidden />
        <p className="tnum mt-2 font-display text-[20px] font-extrabold leading-none tracking-[-0.02em]">{value}</p>
        <p className="mt-1 text-[12px] leading-tight text-muted">{label}</p>
      </Card>
    </motion.div>
  )
}
