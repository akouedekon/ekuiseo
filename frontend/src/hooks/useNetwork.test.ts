import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SLOW_NETWORK_MS, useSlow } from './useNetwork'

/* Audit F252 : un chargement qui dure devient « lent » apres 8 s, et cesse de l'etre des qu'il aboutit. */
describe('useSlow', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('ne signale rien avant le seuil, puis signale, puis se remet a zero', () => {
    const { result, rerender } = renderHook(({ fetching }) => useSlow(fetching), { initialProps: { fetching: true } })
    expect(result.current).toBe(false)
    act(() => vi.advanceTimersByTime(SLOW_NETWORK_MS - 1))
    expect(result.current).toBe(false)
    act(() => vi.advanceTimersByTime(1))
    expect(result.current).toBe(true)
    rerender({ fetching: false })
    expect(result.current).toBe(false)
  })

  it('n arme rien quand rien ne charge', () => {
    const { result } = renderHook(() => useSlow(false))
    act(() => vi.advanceTimersByTime(SLOW_NETWORK_MS * 2))
    expect(result.current).toBe(false)
  })
})
