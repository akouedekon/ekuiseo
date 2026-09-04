import { describe, expect, it } from 'vitest'
import { toKkiapayPhone } from './kkiapay'

describe('toKkiapayPhone', () => {
  it('ne garde que les chiffres, indicatif compris', () => {
    expect(toKkiapayPhone('+229 97 00 00 00')).toBe('22997000000')
  })

  it('refuse un numero trop court ou absent', () => {
    expect(toKkiapayPhone('97 00')).toBeUndefined()
    expect(toKkiapayPhone(undefined)).toBeUndefined()
  })
})
