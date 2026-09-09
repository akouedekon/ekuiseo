import { describe, expect, it } from 'vitest'
import { transitionKeyOf } from './navigation'

describe('transitionKeyOf', () => {
  it('regroupe tout le back-office sous une seule cle de transition', () => {
    expect(transitionKeyOf('/admin')).toBe('/admin')
    expect(transitionKeyOf('/admin/verifications')).toBe('/admin')
    expect(transitionKeyOf('/admin/users/42')).toBe('/admin')
  })

  it('laisse chaque autre ecran a sa propre cle', () => {
    expect(transitionKeyOf('/')).toBe('/')
    expect(transitionKeyOf('/trips/abc')).toBe('/trips/abc')
    expect(transitionKeyOf('/administration')).toBe('/administration')
  })
})
