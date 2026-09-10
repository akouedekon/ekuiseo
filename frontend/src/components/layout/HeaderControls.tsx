import { Bell, LayoutDashboard, LogOut, MessageSquare, Monitor, Moon, Sun, User } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { Avatar } from '@/components/ui/misc'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useIsAuthenticated, useLogout, useMe } from '@/hooks/useAuth'
import { useUnreadMessagesCount } from '@/hooks/useMessages'
import { useUnreadNotificationCount } from '@/hooks/useNotifications'
import { useTheme } from '@/hooks/useTheme'
import { cn } from '@/lib/cn'

/*
 * Commandes communes aux deux en-tetes (barre haute d application en dessous de 768 px,
 * en-tete web au-dela) : cloche des notifications, menu du compte, pastille de non-lus.
 */

/** Pastille de non-lus, partagee entre les navigations. */
export function UnreadPill({ count, className }: { count: number; className?: string }) {
  return (
    <span
      className={cn(
        'tnum flex min-w-[17px] items-center justify-center rounded-full bg-danger px-1 text-micro font-bold leading-[17px] text-on-danger',
        className,
      )}
      aria-hidden
    >
      {count > 9 ? '9+' : count}
    </span>
  )
}

/** Cloche des notifications avec compteur ; ne s affiche qu en session ouverte. */
export function NotificationBell({ className }: { className?: string }) {
  const authed = useIsAuthenticated()
  const unread = useUnreadNotificationCount()
  if (!authed) return null
  return (
    <Link
      to="/notifications"
      aria-label={unread > 0 ? `Notifications, ${unread} non lues` : 'Notifications'}
      className={cn(
        'relative flex size-11 items-center justify-center rounded-[var(--radius-control)] text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink active:bg-surface-2',
        className,
      )}
    >
      <Bell className="size-6 md:size-5" aria-hidden />
      {unread > 0 ? (
        <span className="tnum absolute right-1 top-1 flex min-w-[17px] items-center justify-center rounded-full bg-danger px-1 text-micro font-bold leading-[17px] text-on-danger ring-2 ring-bg">
          {unread > 9 ? '9+' : unread}
        </span>
      ) : null}
    </Link>
  )
}

/** Avatar et menu du compte : acces au compte, messagerie, back-office, theme, deconnexion. */
export function AccountMenu() {
  const navigate = useNavigate()
  const authed = useIsAuthenticated()
  const logoutLocal = useLogout()
  const logout = () => {
    logoutLocal()
    navigate('/', { replace: true })
  }
  const { data: user } = useMe()
  const unreadMessages = useUnreadMessagesCount()
  const { mode, setTheme } = useTheme()
  const isAdmin = user?.role === 'ADMIN'

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          className="flex size-11 items-center justify-center rounded-full text-ink-2 transition-[box-shadow,color] hover:text-ink data-[state=open]:ring-2 data-[state=open]:ring-primary-soft-2"
          aria-label={authed && user ? `Menu du compte de ${user.firstName}` : 'Menu du compte'}
        >
          {authed && user ? (
            <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={34} />
          ) : (
            <span className="flex size-9 items-center justify-center rounded-full bg-surface-2">
              <User className="size-[18px]" aria-hidden />
            </span>
          )}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="min-w-60">
        {authed && user ? (
          <>
            <div className="flex items-center gap-3 px-2.5 py-2">
              <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={36} />
              <div className="min-w-0">
                <p className="truncate text-body font-semibold text-ink">
                  {user.firstName} {user.lastName}
                </p>
                <p className="truncate text-caption text-muted">{user.phone}</p>
              </div>
            </div>
            <DropdownMenuSeparator />
            <DropdownMenuItem asChild>
              <Link to="/me">
                <User aria-hidden />
                Mon compte
              </Link>
            </DropdownMenuItem>
            {/* Repli d'acces a la messagerie quand la barre basse est masquee (audit F318). */}
            <DropdownMenuItem asChild>
              <Link to="/messages">
                <MessageSquare aria-hidden />
                Messages
                {unreadMessages > 0 ? <UnreadPill count={unreadMessages} className="ml-auto" /> : null}
              </Link>
            </DropdownMenuItem>
            {isAdmin ? (
              <DropdownMenuItem asChild>
                <Link to="/admin">
                  <LayoutDashboard aria-hidden />
                  Back-office
                </Link>
              </DropdownMenuItem>
            ) : null}
            <DropdownMenuSeparator />
          </>
        ) : null}

        <DropdownMenuLabel>Apparence</DropdownMenuLabel>
        {(
          [
            { value: 'light', label: 'Clair', icon: Sun },
            { value: 'dark', label: 'Sombre', icon: Moon },
            { value: 'system', label: 'Système', icon: Monitor },
          ] as const
        ).map((option) => (
          <DropdownMenuItem key={option.value} onSelect={() => setTheme(option.value)}>
            <option.icon aria-hidden />
            {option.label}
            {mode === option.value ? (
              <>
                <span aria-hidden className="ml-auto size-1.5 rounded-full bg-primary" />
                <span className="sr-only">Thème actif</span>
              </>
            ) : null}
          </DropdownMenuItem>
        ))}

        {authed ? (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem tone="danger" onSelect={logout}>
              <LogOut aria-hidden />
              Déconnexion
            </DropdownMenuItem>
          </>
        ) : null}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
