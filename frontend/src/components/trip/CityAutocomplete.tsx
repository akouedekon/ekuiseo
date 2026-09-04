import { AnimatePresence, motion } from 'motion/react'
import { MapPin, X } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { useCitySuggestions } from '@/hooks/useGeo'
import type { CityOption } from '@/lib/cities'

interface CityAutocompleteProps {
  label: string
  value: CityOption | null
  onChange: (city: CityOption | null) => void
  placeholder?: string
  icon?: ReactNode
  error?: string
  /** Ville a exclure des suggestions (on ne propose pas A -> A). */
  exclude?: CityOption | null
}

/**
 * Champ ville avec autocompletion : referentiel serveur (GET /api/v1/geo/search)
 * complete par la liste locale (lib/cities.ts), qui sert seule hors ligne.
 * Combobox conforme WAI-ARIA : navigation flechee, Entree pour valider,
 * Echap pour fermer, `aria-activedescendant` sur l'option survolee.
 */
export function CityAutocomplete({
  label,
  value,
  onChange,
  placeholder,
  icon,
  error,
  exclude,
}: CityAutocompleteProps) {
  const inputId = useId()
  const listId = `${inputId}-list`
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)

  // Referentiel serveur + liste locale (voir useCitySuggestions) : on ne propose pas A -> A.
  const candidates = useCitySuggestions(query, 8)
  const suggestions = useMemo(
    () => (exclude ? candidates.filter((city) => city.label !== exclude.label) : candidates).slice(0, 7),
    [candidates, exclude],
  )

  // Fermeture au clic exterieur : le champ ne doit jamais rester ouvert « dans le vide ».
  useEffect(() => {
    if (!open) return
    const handler = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  const select = (city: CityOption) => {
    onChange(city)
    setQuery('')
    setOpen(false)
  }

  const displayed = open ? query : (value?.label ?? '')

  return (
    <div ref={rootRef} className="relative flex flex-col gap-1.5">
      <label htmlFor={inputId} className="text-[13px] font-medium text-ink-2">
        {label}
      </label>
      <div className="relative flex items-center">
        <span aria-hidden className="pointer-events-none absolute left-3 text-muted [&>svg]:size-[18px]">
          {icon ?? <MapPin />}
        </span>
        <input
          id={inputId}
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && suggestions[highlight] ? `${listId}-${highlight}` : undefined}
          aria-invalid={error ? true : undefined}
          autoComplete="off"
          value={displayed}
          placeholder={placeholder ?? 'Ville ou quartier'}
          onFocus={() => {
            setOpen(true)
            setHighlight(0)
          }}
          onChange={(event) => {
            setQuery(event.target.value)
            setOpen(true)
            setHighlight(0)
            if (value) onChange(null)
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown') {
              event.preventDefault()
              setOpen(true)
              setHighlight((h) => Math.min(h + 1, suggestions.length - 1))
            } else if (event.key === 'ArrowUp') {
              event.preventDefault()
              setHighlight((h) => Math.max(h - 1, 0))
            } else if (event.key === 'Enter' && open && suggestions[highlight]) {
              event.preventDefault()
              select(suggestions[highlight])
            } else if (event.key === 'Escape') {
              setOpen(false)
            }
          }}
          className="ek-field h-12 w-full rounded-[var(--radius-control)] pl-10 pr-10 text-base font-medium placeholder:font-normal placeholder:text-muted"
        />
        {value || query ? (
          <button
            type="button"
            aria-label={`Effacer ${label}`}
            onClick={() => {
              onChange(null)
              setQuery('')
              setOpen(false)
            }}
            className="absolute right-1 flex size-10 items-center justify-center rounded-[var(--radius-control)] text-muted transition-colors hover:text-ink"
          >
            <X className="size-4" aria-hidden />
          </button>
        ) : null}
      </div>

      {error ? (
        <p role="alert" className="text-[12px] font-medium text-[var(--vermillon)]">
          {error}
        </p>
      ) : null}

      <AnimatePresence>
        {open && suggestions.length > 0 ? (
          <motion.ul
            id={listId}
            role="listbox"
            aria-label={`Suggestions pour ${label}`}
            initial={{ opacity: 0, y: -4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.14 }}
            className="scroll-thin absolute top-full z-30 mt-1 max-h-72 w-full overflow-y-auto rounded-[var(--radius-card)] border border-rule bg-surface p-1 shadow-e3"
          >
            {suggestions.map((city, index) => (
              <li key={city.label} id={`${listId}-${index}`} role="option" aria-selected={index === highlight}>
                <button
                  type="button"
                  onMouseEnter={() => setHighlight(index)}
                  onClick={() => select(city)}
                  className={cn(
                    'flex min-h-11 w-full items-center gap-2.5 rounded-[6px] px-2.5 text-left transition-colors',
                    index === highlight ? 'bg-[var(--surface-calm)]' : '',
                  )}
                >
                  <MapPin className="size-4 shrink-0 text-muted" aria-hidden />
                  <span className="min-w-0 flex-1 truncate text-[14px] font-medium">{city.label}</span>
                  <span className="shrink-0 text-[12px] text-muted">{city.region}</span>
                </button>
              </li>
            ))}
          </motion.ul>
        ) : null}
      </AnimatePresence>
    </div>
  )
}
