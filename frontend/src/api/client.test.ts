import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError, authStore, NetworkError } from './client'
import { searchTripsRequest } from '@/hooks/useTrips'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('authStore', () => {
  afterEach(() => authStore.clear('logout'))

  it('memorise les jetons meme sans localStorage et notifie les abonnes', () => {
    const seen: string[] = []
    const unsubscribe = authStore.subscribe((authenticated, reason) => seen.push(`${authenticated}:${reason}`))
    authStore.setTokens('a', 'r')
    expect(authStore.isAuthenticated()).toBe(true)
    expect(authStore.getRefreshToken()).toBe('r')
    authStore.clear('expired')
    expect(authStore.isAuthenticated()).toBe(false)
    unsubscribe()
    expect(seen).toEqual(['true:login', 'false:expired'])
  })
})

describe('apiClient', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    authStore.clear('logout')
  })

  it('renvoie le JSON et envoie le jeton', async () => {
    authStore.setTokens('access-1', 'refresh-1')
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { id: 'u1' }))
    const user = await apiClient.get<{ id: string }>('/api/v1/me')
    expect(user.id).toBe('u1')
    const [, init] = fetchMock.mock.calls[0]
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer access-1')
  })

  it('transforme une reponse RFC 7807 en ApiError', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(409, { status: 409, detail: 'Conflit' }))
    await expect(apiClient.post('/api/v1/x', {})).rejects.toMatchObject({ status: 409, message: 'Conflit' })
  })

  it('rafraichit le jeton une seule fois sur 401 puis rejoue la requete', async () => {
    authStore.setTokens('old', 'refresh-1')
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { status: 401 }))
      .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'new', refreshToken: 'refresh-2', user: {} }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }))
    const result = await apiClient.get<{ ok: boolean }>('/api/v1/me')
    expect(result.ok).toBe(true)
    expect(authStore.getAccessToken()).toBe('new')
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(String(fetchMock.mock.calls[1][0])).toContain('/api/v1/auth/refresh')
  })

  it('termine la session quand le rafraichissement est refuse', async () => {
    authStore.setTokens('old', 'refresh-1')
    const reasons: string[] = []
    const unsubscribe = authStore.subscribe((_a, reason) => reasons.push(reason))
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { status: 401 }))
      .mockResolvedValueOnce(jsonResponse(401, { status: 401 }))
    await expect(apiClient.get('/api/v1/me')).rejects.toBeInstanceOf(ApiError)
    expect(authStore.isAuthenticated()).toBe(false)
    expect(reasons).toContain('expired')
    unsubscribe()
  })

  it('convertit un echec fetch en NetworkError', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(apiClient.get('/api/v1/trips/popular', { auth: false })).rejects.toBeInstanceOf(NetworkError)
  })

  it('renvoie undefined sur 204', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
    await expect(apiClient.delete('/api/v1/me/vehicles/1')).resolves.toBeUndefined()
  })

  it('envoie le jeton sur /trips/search quand une session est ouverte (attribution search_events)', async () => {
    authStore.setTokens('access-search', 'refresh-search')
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }))
    const page = await searchTripsRequest({ originLat: 6.37, originLng: 2.39, destLat: 6.45, destLng: 2.36, radiusKm: 5 })
    expect(page.totalElements).toBe(0)
    const [url, init] = fetchMock.mock.calls[0]
    const headers = init?.headers as Record<string, string> | undefined
    expect(String(url)).toContain('/api/v1/trips/search?')
    expect(String(url)).toContain('radiusKm=5')
    expect(headers?.Authorization).toBe('Bearer access-search')
  })

  it('laisse /trips/search anonyme sans session', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }))
    await searchTripsRequest({ originLat: 6.37, originLng: 2.39, destLat: 6.45, destLng: 2.36 }, 1)
    const [url, init] = fetchMock.mock.calls[0]
    const headers = init?.headers as Record<string, string> | undefined
    expect(String(url)).toContain('page=1')
    expect(headers?.Authorization).toBeUndefined()
  })
})
