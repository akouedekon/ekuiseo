export type ThemeMode = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'ekuiseo.theme'

export function readStoredTheme(): ThemeMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === 'light' || raw === 'dark' || raw === 'system') return raw
  } catch {
    /* stockage indisponible */
  }
  return 'system'
}

export function prefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** Applique le theme au document et met a jour la couleur de la barre systeme. */
export function applyTheme(mode: ThemeMode): void {
  const dark = mode === 'dark' || (mode === 'system' && prefersDark())
  document.documentElement.classList.toggle('dark', dark)
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', dark ? '#0d0d0f' : '#0e7c4a')
}

export function storeTheme(mode: ThemeMode): void {
  try {
    localStorage.setItem(STORAGE_KEY, mode)
  } catch {
    /* stockage indisponible : le theme reste valable pour la session */
  }
}
