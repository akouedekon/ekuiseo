import { motion } from 'motion/react'
import { Clock, Send } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, Skeleton } from '@/components/ui/misc'
import { ErrorState } from '@/components/ui/states'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { useBooking } from '@/hooks/useBookings'
import { useMe } from '@/hooks/useAuth'
import { useMessages, useSendMessage } from '@/hooks/useMessages'
import { useOnlineStatus } from '@/hooks/useNetwork'
import { cn } from '@/lib/cn'
import { formatRelativeDay, formatTime } from '@/lib/format'

/** Messagerie liee a une reservation. */
export function BookingMessagesPage() {
  const { id } = useParams<{ id: string }>()
  const booking = useBooking(id)
  const messages = useMessages(id)
  const me = useMe()
  const send = useSendMessage(id ?? '')
  const online = useOnlineStatus()
  const [draft, setDraft] = useState('')
  const endRef = useRef<HTMLDivElement>(null)

  const list = messages.data?.data ?? []
  const myId = me.data?.data.id

  // On colle au dernier message a chaque arrivee, sans animer si l'ecran est deja en bas.
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' })
  }, [list.length])

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const body = draft.trim()
    if (!body) return
    send.mutate(body)
    setDraft('')
  }

  const counterpart = booking.data?.data.trip.driver

  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-6rem)] flex-col pb-4">
      <PageHeader
        title={counterpart ? `${counterpart.firstName} ${counterpart.lastName}` : 'Conversation'}
        subtitle={
          booking.data ? (
            <Link
              to={`/trips/${booking.data.data.tripId}`}
              className="underline-offset-4 hover:underline"
            >
              {booking.data.data.trip.originLabel} → {booking.data.data.trip.destLabel} ·{' '}
              {formatRelativeDay(booking.data.data.trip.departureAt)}
            </Link>
          ) : undefined
        }
      />

      <div className="flex min-h-0 flex-1 flex-col gap-3">
        {messages.isPending ? (
          <div className="space-y-3">
            <Skeleton className="h-12 w-3/5 rounded-[var(--radius-card)]" />
            <Skeleton className="ml-auto h-12 w-2/5 rounded-[var(--radius-card)]" />
            <Skeleton className="h-16 w-4/5 rounded-[var(--radius-card)]" />
          </div>
        ) : messages.isError ? (
          <ErrorState onRetry={() => messages.refetch()} />
        ) : list.length === 0 ? (
          <Card className="p-5 text-center text-[14px] text-muted">
            Aucun message. Présentez-vous et convenez du point de rendez-vous.
          </Card>
        ) : (
          <ul className="flex-1 space-y-2.5">
            {list.map((message, index) => {
              const mine = message.senderId === myId
              const pending = message.id.startsWith('pending-')
              const showAvatar = !mine && list[index - 1]?.senderId !== message.senderId
              return (
                <motion.li
                  key={message.id}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.2 }}
                  className={cn('flex items-end gap-2', mine && 'flex-row-reverse')}
                >
                  {!mine ? (
                    <span className="w-7 shrink-0">
                      {showAvatar && counterpart ? (
                        <Avatar
                          firstName={counterpart.firstName}
                          lastName={counterpart.lastName}
                          photoUrl={counterpart.photoUrl}
                          size={28}
                        />
                      ) : null}
                    </span>
                  ) : null}
                  <div
                    className={cn(
                      'max-w-[78%] rounded-[var(--radius-card)] px-3 py-2',
                      mine
                        ? 'rounded-br-[4px] bg-[var(--indigo)] text-[var(--indigo-contrast)]'
                        : 'rounded-bl-[4px] border border-rule bg-surface text-ink',
                      pending && 'opacity-70',
                    )}
                  >
                    <p className="whitespace-pre-wrap break-words text-[14px] leading-relaxed">{message.body}</p>
                    <p
                      className={cn(
                        'tnum mt-0.5 flex items-center justify-end gap-1 text-[11px]',
                        mine ? 'text-[color-mix(in_srgb,var(--indigo-contrast)_75%,transparent)]' : 'text-muted',
                      )}
                    >
                      {pending ? (
                        <>
                          <Clock className="size-3" aria-hidden />
                          Envoi…
                        </>
                      ) : (
                        formatTime(message.createdAt)
                      )}
                    </p>
                  </div>
                </motion.li>
              )
            })}
            <div ref={endRef} />
          </ul>
        )}
      </div>

      {!online ? (
        <Badge tone="warning" className="mx-auto mt-3">
          Hors ligne — vos messages partiront au retour du réseau
        </Badge>
      ) : null}

      <form onSubmit={submit} className="mt-3 flex items-end gap-2">
        <label htmlFor="message-input" className="sr-only">
          Votre message
        </label>
        <textarea
          id="message-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            // Entree envoie, Maj+Entree passe a la ligne : convention attendue.
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              submit(event)
            }
          }}
          rows={1}
          placeholder="Écrire un message…"
          className="ek-field max-h-32 min-h-11 flex-1 resize-none rounded-[var(--radius-control)] px-3 py-2.5 text-base placeholder:text-muted"
        />
        <Button type="submit" size="icon" disabled={!draft.trim()} aria-label="Envoyer le message">
          <Send className="size-[18px]" aria-hidden />
        </Button>
      </form>
    </PageContainer>
  )
}
