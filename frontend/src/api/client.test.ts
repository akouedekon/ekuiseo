import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError, authStore, NetworkError, restoreSession } from './client'
import { searchTripsRequest } from '@/hooks/useTrips'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** Reponse de /auth/refresh et /otp/verify : le refresh token n'y est plus (cookie HttpOnly). */
function sessionResponse(accessToken: string): Response {
  return jsonResponse(200, { accessToken, user: { id: 'u1' } })
}

function headersOf(call: Parameters<typeof fetch> | undefined): Record<string, string> {
  return (call?.[1]?.headers ?? {}) as Record<string, string>
}

describe('authStore', () => {
  afterEach(() => authStore.clear('logout'))

  it('memorise le jeton d acces en memoire seulement et notifie les abonnes', () => {
    const seen: string[] = []
    const unsubscribe = authStore.subscribe((authenticated, reason) => seen.push(`${authenticated}:${reason}`))
    authStore.setAccessToken('a')
    expect(authStore.isAuthenticated()).toBe(true)
    expect(authStore.getAccessToken()).toBe('a')
    authStore.clear('expired')
    expect(authStore.isAuthenticated()).toBe(false)
    unsubscribe()
    expect(seen).toEqual(['true:login', 'false:expired'])
  })

  it('n ecrit aucun jeton dans localStorage apres connexion (constats F355/F405)', () => {
    localStorage.clear()
    authStore.setAccessToken('access-apres-otp', 'login')
    expect(authStore.isAuthenticated()).toBe(true)
    expect(localStorage.length).toBe(0)
    expect(localStorage.getItem('ekuiseo.accessToken')).toBeNull()
    expect(localStorage.getItem('ekuiseo.refreshToken')).toBeNull()
  })

  it('efface les restes de l ancien client a la fin de session', () => {
    localStorage.setItem('ekuiseo.accessToken', 'ancien-acces')
    localStorage.setItem('ekuiseo.refreshToken', 'ancien-refresh')
    authStore.setAccessToken('a')
    authStore.clear('logout')
    expect(localStorage.getItem('ekuiseo.accessToken')).toBeNull()
    expect(localStorage.getItem('ekuiseo.refreshToken')).toBeNull()
  })
})

describe('apiClient', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
    localStorage.clear()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    authStore.clear('logout')
  })

  it('renvoie le JSON et envoie le jeton', async () => {
    authStore.setAccessToken('access-1')
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { id: 'u1' }))
    const user = await apiClient.get<{ id: string }>('/api/v1/me')
    expect(user.id).toBe('u1')
    const [, init] = fetchMock.mock.calls[0]
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer access-1')
    expect(init?.credentials).toBe('same-origin')
    expect(headersOf(fetchMock.mock.calls[0])['X-Requested-With']).toBeUndefined()
  })

  it('joint le cookie de session et l en-tete anti-CSRF sur les routes /auth/*', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
    await apiClient.post('/api/v1/auth/logout', undefined, { auth: false })
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/api/v1/auth/logout')
    expect(init?.credentials).toBe('include')
    expect(headersOf(fetchMock.mock.calls[0])['X-Requested-With']).toBe('XMLHttpRequest')
    expect(init?.body).toBeUndefined()
  })

  it('transforme une reponse RFC 7807 en ApiError', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(409, { status: 409, detail: 'Conflit' }))
    await expect(apiClient.post('/api/v1/x', {})).rejects.toMatchObject({ status: 409, message: 'Conflit' })
  })

  it('rafraichit le jeton une seule fois sur 401 depuis le cookie, puis rejoue la requete', async () => {
    authStore.setAccessToken('old')
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { status: 401 }))
      .mockResolvedValueOnce(sessionResponse('new'))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }))
    const result = await apiClient.get<{ ok: boolean }>('/api/v1/me')
    expect(result.ok).toBe(true)
    expect(authStore.getAccessToken()).toBe('new')
    expect(fetchMock).toHaveBeenCalledTimes(3)
    const [url, init] = fetchMock.mock.calls[1]
    expect(String(url)).toContain('/api/v1/auth/refresh')
    expect(init?.method).toBe('POST')
    // Le cookie HttpOnly porte le jeton : aucun corps, credentials et en-tete anti-CSRF obligatoires.
    expect(init?.credentials).toBe('include')
    expect(headersOf(fetchMock.mock.calls[1])['X-Requested-With']).toBe('XMLHttpRequest')
    expect(init?.body).toBeUndefined()
    expect(headersOf(fetchMock.mock.calls[2]).Authorization).toBe('Bearer new')
    expect(localStorage.length).toBe(0)
  })

  it('termine la session quand le rafraichissement est refuse', async () => {
    authStore.setAccessToken('old')
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

  it('garde la session quand le rafraichissement echoue pour cause de reseau', async () => {
    authStore.setAccessToken('old')
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { status: 401 }))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(apiClient.get('/api/v1/me')).rejects.toMatchObject({ status: 401 })
    expect(authStore.isAuthenticated()).toBe(true)
    expect(authStore.getAccessToken()).toBe('old')
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
    authStore.setAccessToken('access-search')
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

describe('restoreSession (restauration de session au chargement)', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
    localStorage.clear()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    authStore.clear('logout')
  })

  it('rouvre la session depuis le cookie : restauration en cours pendant l appel, puis jeton en memoire', async () => {
    let resolveRefresh: (res: Response) => void = () => undefined
    fetchMock.mockReturnValueOnce(new Promise<Response>((resolve) => (resolveRefresh = resolve)))
    const seen: string[] = []
    const unsubscribe = authStore.subscribe((authenticated, reason) => seen.push(`${authenticated}:${reason}`))

    const pending = restoreSession()
    expect(authStore.isRestoring()).toBe(true)
    expect(authStore.isAuthenticated()).toBe(false)

    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/api/v1/auth/refresh')
    expect(init?.method).toBe('POST')
    expect(init?.credentials).toBe('include')
    expect(headersOf(fetchMock.mock.calls[0])['X-Requested-With']).toBe('XMLHttpRequest')
    expect(init?.body).toBeUndefined()

    resolveRefresh(sessionResponse('restored-access'))
    const data = await pending
    expect(data?.accessToken).toBe('restored-access')
    expect(data?.user.id).toBe('u1')
    expect(authStore.isRestoring()).toBe(false)
    expect(authStore.getAccessToken()).toBe('restored-access')
    expect(seen).toEqual(['true:restored'])
    expect(localStorage.length).toBe(0)
    unsubscribe()
  })

  it('conclut silencieusement a l absence de session sur 401 (aucune expiration signalee)', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { status: 401, detail: 'Jeton de rafraichissement inconnu' }))
    const seen: string[] = []
    const unsubscribe = authStore.subscribe((authenticated, reason) => seen.push(`${authenticated}:${reason}`))

    await expect(restoreSession()).resolves.toBeNull()

    expect(authStore.isRestoring()).toBe(false)
    expect(authStore.isAuthenticated()).toBe(false)
    expect(seen).toEqual(['false:restored'])
    unsubscribe()
  })

  it('ne fait rien quand un jeton est deja en memoire', async () => {
    authStore.setAccessToken('deja-la')
    await expect(restoreSession()).resolves.toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(authStore.getAccessToken()).toBe('deja-la')
  })

  it('transition : un refresh token laisse dans localStorage part une seule fois dans le corps, puis est efface', async () => {
    localStorage.setItem('ekuiseo.accessToken', 'ancien-acces')
    localStorage.setItem('ekuiseo.refreshToken', 'ancien-refresh')
    fetchMock.mockResolvedValueOnce(sessionResponse('migre'))

    await restoreSession()

    const [, init] = fetchMock.mock.calls[0]
    expect(init?.credentials).toBe('include')
    expect(headersOf(fetchMock.mock.calls[0])['Content-Type']).toBe('application/json')
    expect(JSON.parse(String(init?.body))).toEqual({ refreshToken: 'ancien-refresh' })
    expect(authStore.getAccessToken()).toBe('migre')
    expect(localStorage.getItem('ekuiseo.accessToken')).toBeNull()
    expect(localStorage.getItem('ekuiseo.refreshToken')).toBeNull()

    // Le second appel ne porte plus rien : le cookie a pris le relais.
    authStore.clear('logout')
    fetchMock.mockResolvedValueOnce(sessionResponse('encore'))
    await restoreSession()
    expect(fetchMock.mock.calls[1][1]?.body).toBeUndefined()
  })

  it('transition : un ancien jeton refuse est efface lui aussi', async () => {
    localStorage.setItem('ekuiseo.refreshToken', 'ancien-mort')
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { status: 401 }))
    await restoreSession()
    expect(authStore.isAuthenticated()).toBe(false)
    expect(localStorage.getItem('ekuiseo.refreshToken')).toBeNull()
  })

  it('serveur injoignable : pas de session, restauration terminee, nouvel essai au retour du reseau', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(restoreSession()).resolves.toBeNull()
    expect(authStore.isRestoring()).toBe(false)
    expect(authStore.isAuthenticated()).toBe(false)

    fetchMock.mockResolvedValueOnce(sessionResponse('de-retour'))
    window.dispatchEvent(new Event('online'))
    await vi.waitFor(() => expect(authStore.getAccessToken()).toBe('de-retour'))
  })
})
