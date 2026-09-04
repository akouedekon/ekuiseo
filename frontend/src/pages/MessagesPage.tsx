import { motion } from 'motion/react'
import { ChevronRight, MessagesSquare } from 'lucide-react'
import { Link } from 'react-router'
import { Card } from '@/components/ui/card'
import { Avatar, Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { useConversations } from '@/hooks/useMessages'
import { formatFromNow, formatRelativeDay } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'

/** Liste des conversations, une par reservation. */
export function MessagesPage() {
  const conversations = useConversations()
  const list = conversations.data?.data ?? []

  return (
    <PageContainer width="md">
      <PageHeader title="Messages" back={false} subtitle="Une conversation par réservation" />

      {conversations.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <Card key={i} className="flex items-center gap-3 p-4">
              <Skeleton className="size-11 rounded-full" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-36" />
                <Skeleton className="h-3 w-48" />
              </div>
            </Card>
          ))}
        </div>
      ) : conversations.isError ? (
        <ErrorState onRetry={() => conversations.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={MessagesSquare}
          title="Aucune conversation"
          description="Vous pourrez écrire au conducteur dès votre première réservation."
        />
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((conversation) => (
            <motion.li key={conversation.bookingId} variants={listItem}>
              <Card className={conversation.unreadCount > 0 ? 'border-l-[3px] border-l-[var(--indigo)]' : ''}>
                <Link
                  to={`/bookings/${conversation.bookingId}/messages`}
                  className="flex items-center gap-3 p-4 transition-colors hover:bg-[var(--surface-calm)]"
                >
                  <Avatar
                    firstName={conversation.counterpart.firstName}
                    lastName={conversation.counterpart.lastName}
                    photoUrl={conversation.counterpart.photoUrl}
                    size={44}
                  />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-baseline justify-between gap-2">
                      <p className="truncate font-display text-[15px] font-bold">
                        {conversation.counterpart.firstName} {conversation.counterpart.lastName}
                      </p>
                      {conversation.lastMessageAt ? (
                        <span className="shrink-0 text-[12px] text-muted">
                          {formatFromNow(conversation.lastMessageAt)}
                        </span>
                      ) : null}
                    </div>
                    <p className="truncate text-[13px] text-muted">
                      {conversation.originLabel} → {conversation.destLabel} ·{' '}
                      {formatRelativeDay(conversation.departureAt)}
                    </p>
                    {conversation.lastMessage ? (
                      <p
                        className={
                          conversation.unreadCount > 0
                            ? 'mt-0.5 truncate text-[14px] font-semibold text-ink'
                            : 'mt-0.5 truncate text-[14px] text-ink-2'
                        }
                      >
                        {conversation.lastMessage}
                      </p>
                    ) : null}
                  </div>
                  {conversation.unreadCount > 0 ? (
                    <span className="tnum flex size-6 shrink-0 items-center justify-center rounded-full bg-[var(--vermillon)] text-[12px] font-bold text-[var(--vermillon-contrast)]">
                      {conversation.unreadCount}
                    </span>
                  ) : (
                    <ChevronRight className="size-4 shrink-0 text-muted" aria-hidden />
                  )}
                </Link>
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}
    </PageContainer>
  )
}
