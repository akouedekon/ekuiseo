import { motion } from 'motion/react'
import { Ban, RotateCcw, Search, ShieldCheck, UserX } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Avatar, RatingStars, Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { useAdminUsers, useToggleUserSuspension } from '@/hooks/useAdmin'
import { formatDayShort, formatPhone } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'

export function AdminUsers() {
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')
  const users = useAdminUsers(query)
  const toggle = useToggleUserSuspension()

  // Anti-rebond : on n'interroge l'API qu'apres 350 ms de calme.
  useEffect(() => {
    const id = window.setTimeout(() => setQuery(input), 350)
    return () => window.clearTimeout(id)
  }, [input])

  const list = users.data?.data ?? []

  return (
    <div>

      <Input
        label="Rechercher un utilisateur"
        placeholder="Nom, numéro ou e-mail"
        value={input}
        onChange={(event) => setInput(event.target.value)}
        leading={<Search />}
        className="mb-4"
      />

      {users.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-20 rounded-[var(--radius-card)]" />
          ))}
        </div>
      ) : users.isError ? (
        <ErrorState onRetry={() => users.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={UserX}
          title="Aucun utilisateur"
          description={query ? `Aucun résultat pour « ${query} ».` : 'La base est vide.'}
        />
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((user) => (
            <motion.li key={user.id} variants={listItem} layout>
              <Card className={user.suspended ? 'border-l-[3px] border-l-[var(--vermillon)]' : ''}>
                <div className="flex flex-wrap items-start gap-3 p-4">
                  <Avatar firstName={user.firstName} lastName={user.lastName} size={44} />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="font-display text-[16px] font-bold">
                        {user.firstName} {user.lastName}
                      </p>
                      {user.identityVerified ? (
                        <Badge tone="success">
                          <ShieldCheck aria-hidden />
                          Vérifié
                        </Badge>
                      ) : null}
                      {user.suspended ? (
                        <Badge tone="danger">
                          <Ban aria-hidden />
                          Suspendu
                        </Badge>
                      ) : null}
                    </div>
                    <p className="tnum text-[13px] text-muted">
                      {formatPhone(user.phone)}
                      {user.email ? ` · ${user.email}` : ''}
                    </p>
                    <p className="tnum mt-1 text-[13px] text-ink-2">
                      {user.tripsPublished} trajets publiés · {user.bookingsMade} réservations · inscrit le{' '}
                      {formatDayShort(user.createdAt)}
                    </p>
                    {user.ratingAvg > 0 ? <RatingStars value={user.ratingAvg} size={12} className="mt-1" /> : null}
                  </div>

                  <Button
                    size="sm"
                    variant={user.suspended ? 'secondary' : 'ghost'}
                    className={user.suspended ? '' : 'text-[var(--vermillon)]'}
                    onClick={() =>
                      toggle.mutate(
                        { id: user.id, suspend: !user.suspended },
                        {
                          onSuccess: () =>
                            toast.success(
                              user.suspended
                                ? `${user.firstName} a été réactivé`
                                : `${user.firstName} a été suspendu`,
                            ),
                          onError: () => toast.error("L'action a échoué."),
                        },
                      )
                    }
                  >
                    {user.suspended ? (
                      <>
                        <RotateCcw className="size-4" aria-hidden />
                        Réactiver
                      </>
                    ) : (
                      <>
                        <Ban className="size-4" aria-hidden />
                        Suspendre
                      </>
                    )}
                  </Button>
                </div>
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}
    </div>
  )
}
