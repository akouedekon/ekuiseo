import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router'
import { AppShell } from '@/components/layout/AppShell'
import { RequireAuth } from '@/components/RequireAuth'
import { HomeSearchPage } from '@/pages/HomeSearchPage'
import { SearchResultsPage } from '@/pages/SearchResultsPage'
import { TripDetailPage } from '@/pages/TripDetailPage'
import { BookingPage } from '@/pages/BookingPage'
import { PublishTripPage } from '@/pages/PublishTripPage'
import { MyBookingsPage, MyTripsPage } from '@/pages/MyTripsPage'
import { MessagesPage } from '@/pages/MessagesPage'
import { BookingMessagesPage } from '@/pages/BookingMessagesPage'
import { DriverProfilePage } from '@/pages/DriverProfilePage'
import { LoginPage, RegisterPage } from '@/pages/LoginPage'
import { MePage } from '@/pages/MePage'
import { NotificationsPage } from '@/pages/NotificationsPage'
import { AppLoadingScreen, NotFoundPage, OfflinePage } from '@/pages/SystemPages'

/*
 * Le back-office est charge a la demande : il embarque Recharts et ne
 * concerne qu'une poignee d'utilisateurs. Il ne doit pas alourdir le
 * premier chargement des passagers sur reseau mobile.
 */
const AdminLayout = lazy(() => import('@/pages/admin/AdminLayout').then((m) => ({ default: m.AdminLayout })))
const AdminDashboard = lazy(() => import('@/pages/admin/AdminDashboard').then((m) => ({ default: m.AdminDashboard })))
const AdminLiquidity = lazy(() => import('@/pages/admin/AdminLiquidity').then((m) => ({ default: m.AdminLiquidity })))
const AdminReports = lazy(() => import('@/pages/admin/AdminReports').then((m) => ({ default: m.AdminReports })))
const AdminVerifications = lazy(() =>
  import('@/pages/admin/AdminVerifications').then((m) => ({ default: m.AdminVerifications })),
)
const AdminPayouts = lazy(() => import('@/pages/admin/AdminPayouts').then((m) => ({ default: m.AdminPayouts })))
const AdminUsers = lazy(() => import('@/pages/admin/AdminUsers').then((m) => ({ default: m.AdminUsers })))

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        {/* --- Parcours public --- */}
        <Route path="/" element={<HomeSearchPage />} />
        <Route path="/search" element={<SearchResultsPage />} />
        <Route path="/trips/mine" element={<RequireAuth><MyTripsPage defaultTab="driving" /></RequireAuth>} />
        <Route path="/trips/:id" element={<TripDetailPage />} />
        <Route path="/drivers/:id" element={<DriverProfilePage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/offline" element={<OfflinePage />} />

        {/* --- Parcours authentifie --- */}
        <Route path="/book/:tripId" element={<RequireAuth><BookingPage /></RequireAuth>} />
        <Route path="/publish" element={<RequireAuth><PublishTripPage /></RequireAuth>} />
        <Route path="/bookings" element={<RequireAuth><MyBookingsPage /></RequireAuth>} />
        <Route path="/bookings/:id/messages" element={<RequireAuth><BookingMessagesPage /></RequireAuth>} />
        <Route path="/messages" element={<RequireAuth><MessagesPage /></RequireAuth>} />
        <Route path="/me" element={<RequireAuth><MePage /></RequireAuth>} />
        <Route path="/notifications" element={<RequireAuth><NotificationsPage /></RequireAuth>} />

        {/* --- Back-office --- */}
        <Route
          path="/admin"
          element={
            <RequireAuth>
              <Suspense fallback={<AppLoadingScreen />}>
                <AdminLayout />
              </Suspense>
            </RequireAuth>
          }
        >
          <Route index element={<Suspense fallback={null}><AdminDashboard /></Suspense>} />
          <Route path="liquidity" element={<Suspense fallback={null}><AdminLiquidity /></Suspense>} />
          <Route path="reports" element={<Suspense fallback={null}><AdminReports /></Suspense>} />
          <Route path="verifications" element={<Suspense fallback={null}><AdminVerifications /></Suspense>} />
          <Route path="payouts" element={<Suspense fallback={null}><AdminPayouts /></Suspense>} />
          <Route path="users" element={<Suspense fallback={null}><AdminUsers /></Suspense>} />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
