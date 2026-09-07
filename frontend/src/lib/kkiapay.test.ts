import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { isKkiapayClosed, openKkiapay, resetKkiapayListenersForTests, toKkiapayPhone } from './kkiapay'

describe('toKkiapayPhone', () => {
  it('ne garde que les chiffres, indicatif compris', () => {
    expect(toKkiapayPhone('+229 97 00 00 00')).toBe('22997000000')
  })

  it('refuse un numero trop court ou absent', () => {
    expect(toKkiapayPhone('97 00')).toBeUndefined()
    expect(toKkiapayPhone(undefined)).toBeUndefined()
  })
})

/** Double du SDK k.js : memes fonctions globales, pilotees par le test. */
type Listener = (payload: unknown) => void

describe('openKkiapay', () => {
  const listeners: Record<string, Listener[]> = {}
  let closeListeners: (() => void)[] = []
  let opened = 0

  beforeEach(() => {
    resetKkiapayListenersForTests()
    listeners.success = []
    listeners.failed = []
    closeListeners = []
    opened = 0
    window.openKkiapayWidget = () => {
      opened += 1
    }
    window.addKkiapayListener = (event, cb) => {
      listeners[event]?.push(cb)
    }
    window.removeKkiapayListener = (event, cb) => {
      listeners[event] = (listeners[event] ?? []).filter((l) => l !== cb)
    }
    window.addKkiapayCloseListener = (cb) => {
      closeListeners.push(cb)
    }
  })

  afterEach(() => {
    delete window.openKkiapayWidget
    delete window.addKkiapayListener
    delete window.removeKkiapayListener
    delete window.addKkiapayCloseListener
  })

  const input = { amount: 1000, publicKey: 'pk', sandbox: true }

  it('se resout au succes avec le transactionId et retire ses ecouteurs', async () => {
    const promise = openKkiapay(input)
    await Promise.resolve()
    expect(opened).toBe(1)
    expect(listeners.success).toHaveLength(1)
    listeners.success[0]({ transactionId: 'tx-1' })
    await expect(promise).resolves.toMatchObject({ transactionId: 'tx-1' })
    expect(listeners.success).toHaveLength(0)
    expect(listeners.failed).toHaveLength(0)
  })

  it('regle la promesse quand la fenetre est fermee sans conclure, sans empiler d ecouteurs (F136)', async () => {
    const first = openKkiapay(input)
    await Promise.resolve()
    closeListeners.forEach((cb) => cb())
    await expect(first).rejects.toSatisfy(isKkiapayClosed)
    expect(listeners.success).toHaveLength(0)
    expect(listeners.failed).toHaveLength(0)

    // Une seule inscription de l'ecouteur de fermeture, quel que soit le nombre d'ouvertures.
    const second = openKkiapay(input)
    await Promise.resolve()
    expect(closeListeners).toHaveLength(1)
    expect(listeners.success).toHaveLength(1)
    listeners.success[0]({ transactionId: 'tx-2' })
    await expect(second).resolves.toMatchObject({ transactionId: 'tx-2' })
  })

  it('rejette au refus de l operateur avec son message', async () => {
    const promise = openKkiapay(input)
    await Promise.resolve()
    listeners.failed[0]({ reason: 'Solde insuffisant' })
    await expect(promise).rejects.toThrow('Solde insuffisant')
    expect(listeners.success).toHaveLength(0)
  })
})
