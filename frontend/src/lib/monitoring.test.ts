import { describe, expect, it } from 'vitest'
import { buildReport, isStaleChunkError, resolveReportUrl } from './monitoring'

describe('monitoring', () => {
  it('construit un rapport sans donnee personnelle : route sans parametres, pile tronquee', () => {
    window.history.replaceState({}, '', '/book/t1?booking=secret-id')
    const error = new Error('Boum')
    error.stack = 'x'.repeat(5_000)
    const report = buildReport(error, { source: 'test' })
    expect(report.route).toBe('/book/t1')
    expect(report.route).not.toContain('secret-id')
    expect(report.stack?.length).toBeLessThanOrEqual(2_000)
    expect(report.message).toBe('Boum')
    expect(report.source).toBe('test')
  })

  it('vise le collecteur de l API par defaut, une URL explicite sinon, rien si « off »', () => {
    expect(resolveReportUrl({})).toBe('/api/v1/client-errors')
    expect(resolveReportUrl({ VITE_API_URL: 'https://api.example.com/' })).toBe('https://api.example.com/api/v1/client-errors')
    expect(resolveReportUrl({ VITE_ERROR_REPORT_URL: 'https://sentry.example/ingest' })).toBe('https://sentry.example/ingest')
    expect(resolveReportUrl({ VITE_ERROR_REPORT_URL: 'off' })).toBeNull()
    expect(resolveReportUrl({ VITE_ERROR_REPORT_URL: '  ' })).toBe('/api/v1/client-errors')
  })

  it('reconnait un import paresseux casse par un deploiement', () => {
    expect(isStaleChunkError(new Error('Failed to fetch dynamically imported module: /assets/MePage-abc.js'))).toBe(true)
    expect(isStaleChunkError(new Error('Importing a module script failed.'))).toBe(true)
    expect(isStaleChunkError(new Error('Cannot read properties of undefined'))).toBe(false)
  })
})
