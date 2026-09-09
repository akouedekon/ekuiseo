import { render, screen } from '@testing-library/react'
import { Suspense } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createLazyPage, preloadPages, resetPageRegistry } from './lazyPage'

function Screen({ label }: { label: string }) {
  return <h1>{label}</h1>
}

afterEach(() => resetPageRegistry())

describe('createLazyPage', () => {
  it('suspend au premier rendu tant que le module n est pas connu', async () => {
    const loader = vi.fn(async () => ({ Screen }))
    const Page = createLazyPage(loader, 'Screen', 'authed')
    expect(Page.isReady()).toBe(false)

    render(
      <Suspense fallback={<p>attente</p>}>
        <Page label="Mes trajets" />
      </Suspense>,
    )
    expect(screen.getByText('attente')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Mes trajets' })).toBeInTheDocument()
    expect(Page.isReady()).toBe(true)
  })

  it('se rend de facon synchrone, sans repli Suspense, une fois precharge', async () => {
    const loader = vi.fn(async () => ({ Screen }))
    const Page = createLazyPage(loader, 'Screen', 'admin')
    await preloadPages('admin')
    expect(Page.isReady()).toBe(true)
    expect(loader).toHaveBeenCalledTimes(1)

    render(
      <Suspense fallback={<p>attente</p>}>
        <Page label="Vérifications" />
      </Suspense>,
    )
    // Rendu immediat : le repli n apparait jamais, et React.lazy n a pas rappele le chargeur.
    expect(screen.queryByText('attente')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Vérifications' })).toBeInTheDocument()
    expect(loader).toHaveBeenCalledTimes(1)
  })

  it('ne leve pas quand le prechargement echoue, et retente au rendu', async () => {
    let attempts = 0
    const loader = vi.fn(async () => {
      attempts += 1
      if (attempts === 1) throw new Error('hors ligne')
      return { Screen }
    })
    const Page = createLazyPage(loader, 'Screen', 'public')
    await expect(preloadPages('public')).resolves.toBeUndefined()
    expect(Page.isReady()).toBe(false)

    render(
      <Suspense fallback={<p>attente</p>}>
        <Page label="Accueil" />
      </Suspense>,
    )
    expect(await screen.findByRole('heading', { name: 'Accueil' })).toBeInTheDocument()
  })
})
