import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authStore } from '@/api/client'
import { createTestQueryClient, installFakeApi } from '@/test/api'
import { RequireAdmin, RequireAuth } from './RequireAuth'

function LocationProbe() {
  const location = useLocation()
  return <p data-testid="location">{location.pathname + location.search}</p>
}

function renderAt(path: string, element: React.ReactNode) {
  const client = createTestQueryClient()
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<LocationProbe />} />
          <Route path="*" element={element} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RequireAuth', () => {
  afterEach(() => {
    authStore.clear('logout')
    vi.unstubAllGlobals()
  })

  it('renvoie vers /login en memorisant la destination quand il n y a pas de session', () => {
    renderAt('/bookings?tab=past', <RequireAuth><p>Prive</p></RequireAuth>)
    expect(screen.queryByText('Prive')).not.toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fbookings%3Ftab%3Dpast')
  })

  it('rend les enfants quand un jeton existe, puis redirige a l expiration de session', async () => {
    authStore.setTokens('access', 'refresh')
    renderAt('/me', <RequireAuth><p>Prive</p></RequireAuth>)
    expect(screen.getByText('Prive')).toBeInTheDocument()

    // Expiration detectee par le client HTTP : la garde reagit sans rechargement.
    act(() => authStore.clear('expired'))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Fme'))
    expect(screen.queryByText('Prive')).not.toBeInTheDocument()
  })
})

describe('RequireAdmin', () => {
  beforeEach(() => authStore.setTokens('access', 'refresh'))
  afterEach(() => {
    authStore.clear('logout')
    vi.unstubAllGlobals()
  })

  it('refuse un compte USER avec l ecran « Acces reserve »', async () => {
    installFakeApi({ '/api/v1/me': { body: { id: 'u1', role: 'USER', firstName: 'A', lastName: 'B', phone: '+22901' } } })
    renderAt('/admin', <RequireAdmin><p>Back-office</p></RequireAdmin>)
    expect(await screen.findByText('Accès réservé')).toBeInTheDocument()
    expect(screen.queryByText('Back-office')).not.toBeInTheDocument()
  })

  it('laisse passer un compte ADMIN', async () => {
    installFakeApi({ '/api/v1/me': { body: { id: 'u1', role: 'ADMIN', firstName: 'A', lastName: 'B', phone: '+22901' } } })
    renderAt('/admin', <RequireAdmin><p>Back-office</p></RequireAdmin>)
    expect(await screen.findByText('Back-office')).toBeInTheDocument()
  })

  it('propose un reessai quand le profil ne se charge pas', async () => {
    installFakeApi({ '/api/v1/me': { status: 500, body: { status: 500, detail: 'Panne' } } })
    renderAt('/admin', <RequireAdmin><p>Back-office</p></RequireAdmin>)
    expect(await screen.findByText('Vérification impossible')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Réessayer' })).toBeInTheDocument()
  })
})
