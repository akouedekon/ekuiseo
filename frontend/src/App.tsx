import { lazy, Suspense, type ComponentType, type ReactNode } from 'react'
import { Route, Routes } from 'react-router'
import { AppShell } from '@/components/layout/AppShell'
import { RequireAdmin, RequireAuth } from '@/components/RequireAuth'
import { HomeSearchPage } from '@/pages/HomeSearchPage'
import { SearchResultsPage } from '@/pages/SearchResultsPage'
import { TripDetailPage } from '@/pages/TripDetailPage'
import { DriverProfilePage } from '@/pages/DriverProfilePage'
import { AppLoadingScreen, NotFoundPage } from '@/pages/SystemPages'

/**
 * Chargement paresseux par route (audit F338) : le premier paquet ne contient
 * que le parcours public (accueil, resultats, detail, connexion). Les formulaires
 * (RHF + Zod), le tunnel de reservation, le compte, la messagerie, les
 * notifications et le back-office n'arrivent qu'a leur premiere ouverture.
 */
function lazyPage<K extends string, P extends object>(loader: () => Promise<Record<K, ComponentType<P>>>, name: K) {
  return lazy(() => loader().then((module) => ({ default: module[name] })))
}

const BookingPage = lazyPage(() => import('@/pages/BookingPage'), 'BookingPage')
const PublishTripPage = lazyPage(() => import('@/pages/PublishTripPage'), 'PublishTripPage')
const MyTripsPage = lazyPage(() => import('@/pages/MyTripsPage'), 'MyTripsPage')
const MyBookingsPage = lazyPage(() => import('@/pages/MyTripsPage'), 'MyBookingsPage')
const MessagesPage = lazyPage(() => import('@/pages/MessagesPage'), 'MessagesPage')
const BookingMessagesPage = lazyPage(() => import('@/pages/BookingMessagesPage'), 'BookingMessagesPage')
const MePage = lazyPage(() => import('@/pages/MePage'), 'MePage')
const NotificationsPage = lazyPage(() => import('@/pages/NotificationsPage'), 'NotificationsPage')
// Connexion / inscription : RHF n'y est pas, mais Zod (validation) et OTP n'ont rien a faire dans le premier paquet.
const LoginPage = lazyPage(() => import('@/pages/LoginPage'), 'LoginPage')
const RegisterPage = lazyPage(() => import('@/pages/LoginPage'), 'RegisterPage')
const LegalPage = lazyPage(() => import('@/pages/LegalPage'), 'LegalPage')

/*
 * Le back-office est charge a la demande : il embarque Recharts et ne
 * concerne qu'une poignee d'utilisateurs. Il ne doit pas alourdir le
 * premier chargement des passagers sur reseau mobile.
 */
const AdminLayout = lazyPage(() => import('@/pages/admin/AdminLayout'), 'AdminLayout')
const AdminDashboard = lazyPage(() => import('@/pages/admin/AdminDashboard'), 'AdminDashboard')
const AdminLiquidity = lazyPage(() => import('@/pages/admin/AdminLiquidity'), 'AdminLiquidity')
const AdminRetention = lazyPage(() => import('@/pages/admin/AdminRetention'), 'AdminRetention')
const AdminReports = lazyPage(() => import('@/pages/admin/AdminReports'), 'AdminReports')
const AdminVerifications = lazyPage(() => import('@/pages/admin/AdminVerifications'), 'AdminVerifications')
const AdminPayouts = lazyPage(() => import('@/pages/admin/AdminPayouts'), 'AdminPayouts')
const AdminPayments = lazyPage(() => import('@/pages/admin/AdminPayments'), 'AdminPayments')
const AdminUsers = lazyPage(() => import('@/pages/admin/AdminUsers'), 'AdminUsers')
const AdminUserDetail = lazyPage(() => import('@/pages/admin/AdminUserDetail'), 'AdminUserDetail')
const AdminAudit = lazyPage(() => import('@/pages/admin/AdminAudit'), 'AdminAudit')
/* Charte graphique vivante : reference de l'equipe, servie uniquement en developpement. */
const StyleGuidePage = import.meta.env.DEV ? lazyPage(() => import('@/pages/StyleGuidePage'), 'StyleGuidePage') : null

/** Ecran charge a la demande, derriere la garde de session. */
function Authed({ children }: { children: ReactNode }) {
  return (
    <RequireAuth>
      <Suspense fallback={<AppLoadingScreen />}>{children}</Suspense>
    </RequireAuth>
  )
}

function Deferred({ children }: { children: ReactNode }) {
  return <Suspense fallback={<AppLoadingScreen />}>{children}</Suspense>
}

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        {/* --- Parcours public --- */}
        <Route path="/" element={<HomeSearchPage />} />
        <Route path="/search" element={<SearchResultsPage />} />
        <Route path="/trips/mine" element={<Authed><MyTripsPage /></Authed>} />
        <Route path="/trips/:id" element={<TripDetailPage />} />
        <Route path="/drivers/:id" element={<DriverProfilePage />} />
        <Route path="/login" element={<Deferred><LoginPage /></Deferred>} />
        <Route path="/register" element={<Deferred><RegisterPage /></Deferred>} />
        <Route path="/cgu" element={<Deferred><LegalPage slug="cgu" /></Deferred>} />
        <Route path="/confidentialite" element={<Deferred><LegalPage slug="confidentialite" /></Deferred>} />
        <Route path="/mentions-legales" element={<Deferred><LegalPage slug="mentions-legales" /></Deferred>} />
        {StyleGuidePage ? <Route path="/charte" element={<Deferred><StyleGuidePage /></Deferred>} /> : null}

        {/* --- Parcours authentifie --- */}
        <Route path="/book/:tripId" element={<Authed><BookingPage /></Authed>} />
        <Route path="/publish" element={<Authed><PublishTripPage /></Authed>} />
        <Route path="/bookings" element={<Authed><MyBookingsPage /></Authed>} />
        <Route path="/bookings/:id/messages" element={<Authed><BookingMessagesPage /></Authed>} />
        <Route path="/messages" element={<Authed><MessagesPage /></Authed>} />
        <Route path="/me" element={<Authed><MePage /></Authed>} />
        <Route path="/notifications" element={<Authed><NotificationsPage /></Authed>} />

        {/* --- Back-office (role ADMIN, verifie par le profil puis par l'API) --- */}
        <Route
          path="/admin"
          element={
            <RequireAuth>
              <RequireAdmin>
                <Suspense fallback={<AppLoadingScreen />}>
                  <AdminLayout />
                </Suspense>
              </RequireAdmin>
            </RequireAuth>
          }
        >
          <Route index element={<Suspense fallback={null}><AdminDashboard /></Suspense>} />
          <Route path="liquidity" element={<Suspense fallback={null}><AdminLiquidity /></Suspense>} />
          <Route path="retention" element={<Suspense fallback={null}><AdminRetention /></Suspense>} />
          <Route path="reports" element={<Suspense fallback={null}><AdminReports /></Suspense>} />
          <Route path="verifications" element={<Suspense fallback={null}><AdminVerifications /></Suspense>} />
          <Route path="payouts" element={<Suspense fallback={null}><AdminPayouts /></Suspense>} />
          <Route path="payments" element={<Suspense fallback={null}><AdminPayments /></Suspense>} />
          <Route path="users" element={<Suspense fallback={null}><AdminUsers /></Suspense>} />
          <Route path="users/:id" element={<Suspense fallback={null}><AdminUserDetail /></Suspense>} />
          <Route path="audit" element={<Suspense fallback={null}><AdminAudit /></Suspense>} />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
