import { AnimatePresence, m } from 'motion/react'
import { History, LocateFixed, MapPin, X } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { FieldError } from '@/components/ui/input'
import { cn } from '@/lib/cn'
import { useCitySuggestions } from '@/hooks/useGeo'
import { normalize, shortName, type CityOption } from '@/lib/cities'

interface CityAutocompleteProps {
  label: string
  value: CityOption | null
  onChange: (city: CityOption | null) => void
  placeholder?: string
  icon?: ReactNode
  error?: string
  /** Ville a exclure des suggestions (on ne propose pas A -> A). */
  exclude?: CityOption | null
  /**
   * « Ma position » : bouton dans le champ, visible tant que rien n est saisi ni choisi.
   * Le parent obtient la position (lib/geolocation) et remplit le champ ; `locating`
   * desactive le bouton le temps de la mesure.
   */
  onLocate?: () => void
  locating?: boolean
}

/**
 * Champ ville avec autocompletion sur le referentiel serveur (GET
 * /api/v1/geo/places, charge une fois et persiste ; repli local hors ligne).
 * Champ vide : dernieres villes recherchees puis villes principales. Quartier :
 * « Agla — Cotonou » (audit F422). Combobox conforme WAI-ARIA : navigation
 * flechee, Entree pour valider, Echap pour fermer, `aria-activedescendant` sur
 * l'option survolee, surbrillance lisible au clavier (audit F322).
 */
export function CityAutocomplete({
  label,
  value,
  onChange,
  placeholder,
  icon,
  error,
  exclude,
  onLocate,
  locating = false,
}: CityAutocompleteProps) {
  const inputId = useId()
  const listId = `${inputId}-list`
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)

  const { suggestions: candidates, recentCount } = useCitySuggestions(query, 8)
  const suggestions = useMemo(
    () => (exclude ? candidates.filter((city) => city.label !== exclude.label) : candidates).slice(0, 7),
    [candidates, exclude],
  )
  const recentShown = query.trim() ? 0 : Math.min(recentCount, suggestions.filter((c) => candidates.indexOf(c) < recentCount).length)

  // Saisie laissee sans selection : on la garde affichee, avec une erreur explicite.
  const [unresolved, setUnresolved] = useState(false)

  const select = (city: CityOption) => {
    onChange(city)
    setQuery('')
    setUnresolved(false)
    setOpen(false)
  }

  /*
   * Fermeture sans selection (blur, clic exterieur) : si la saisie correspond
   * exactement a une suggestion, ou qu'il n'en reste qu'une, on la retient ; sinon
   * la saisie reste visible et le champ passe en erreur (audit F222). Elle ne
   * disparait plus silencieusement.
   */
  const commit = () => {
    setOpen(false)
    if (value || !query.trim()) {
      setUnresolved(false)
      return
    }
    const q = normalize(query)
    const exact = suggestions.find((city) => normalize(city.label) === q || normalize(shortName(city)) === q)
    const pick = exact ?? (suggestions.length === 1 ? suggestions[0] : undefined)
    if (pick) select(pick)
    else setUnresolved(true)
  }
  const commitRef = useRef(commit)
  useEffect(() => {
    commitRef.current = commit
  })

  // Fermeture au clic exterieur : le champ ne doit jamais rester ouvert « dans le vide ».
  useEffect(() => {
    if (!open) return
    const handler = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) commitRef.current()
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  const displayed = open ? query : (value?.label ?? (unresolved ? query : ''))
  const shownError = error ?? (unresolved ? 'Choisissez une ville dans la liste' : undefined)

  return (
    <div ref={rootRef} className="relative flex flex-col gap-1.5">
      <label htmlFor={inputId} className="text-label font-medium text-ink-2">
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
          aria-invalid={shownError ? true : undefined}
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
            setUnresolved(false)
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
              commit()
            }
          }}
          onBlur={(event) => {
            // Le focus passe a une suggestion : ce n'est pas une sortie du champ.
            if (rootRef.current?.contains(event.relatedTarget as Node | null)) return
            commit()
          }}
          className="ek-field h-12 w-full rounded-[var(--radius-control)] pl-10 pr-10 text-lead font-medium placeholder:font-normal placeholder:text-muted sm:text-base"
        />
        {value || query ? (
          <button
            type="button"
            aria-label={`Effacer ${label}`}
            onClick={() => {
              onChange(null)
              setQuery('')
              setUnresolved(false)
              setOpen(false)
            }}
            className="absolute right-0.5 flex size-11 items-center justify-center rounded-[var(--radius-control)] text-muted transition-colors hover:text-ink"
          >
            <X className="size-4" aria-hidden />
          </button>
        ) : onLocate ? (
          <button
            type="button"
            aria-label={locating ? 'Recherche de votre position' : 'Ma position'}
            title="Ma position"
            aria-busy={locating || undefined}
            disabled={locating}
            onClick={onLocate}
            className="absolute right-0.5 flex size-11 items-center justify-center rounded-[var(--radius-control)] text-muted transition-colors hover:text-primary-ink disabled:opacity-60"
          >
            <LocateFixed className={cn('size-[18px]', locating && 'motion-safe:animate-pulse')} aria-hidden />
          </button>
        ) : null}
      </div>

      {shownError ? <FieldError>{shownError}</FieldError> : null}

      <AnimatePresence>
        {open && suggestions.length > 0 ? (
          <m.ul
            id={listId}
            role="listbox"
            aria-label={`Suggestions pour ${label}`}
            initial={{ opacity: 0, y: -4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.14 }}
            className="scroll-thin absolute top-full z-30 mt-1 max-h-72 w-full overflow-y-auto rounded-[var(--radius-card)] border border-rule bg-surface p-1 shadow-e3"
          >
            {suggestions.map((city, index) => {
              const recent = index < recentShown
              return (
                <li key={city.id ?? city.label} id={`${listId}-${index}`} role="option" aria-selected={index === highlight}>
                  <button
                    type="button"
                    tabIndex={-1}
                    onMouseEnter={() => setHighlight(index)}
                    // Le champ garde le focus : pas de blur (donc pas de fermeture) avant le clic, y compris sur iOS.
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => select(city)}
                    className={cn(
                      'flex min-h-11 w-full items-center gap-2.5 rounded-[6px] px-2.5 text-left transition-colors',
                      index === highlight && 'bg-primary-soft text-primary-ink shadow-[inset_0_0_0_2px_var(--focus-ring)]',
                    )}
                  >
                    {recent ? (
                      <History className="size-4 shrink-0 text-muted" aria-label="Recherche récente" />
                    ) : (
                      <MapPin className="size-4 shrink-0 text-muted" aria-hidden />
                    )}
                    <span className="min-w-0 flex-1 truncate text-body font-medium">
                      {city.parentName ? (
                        <>
                          {shortName(city)} <span className="font-normal text-muted">— {city.parentName}</span>
                        </>
                      ) : (
                        city.label
                      )}
                    </span>
                    <span className="shrink-0 text-caption text-muted">{recent ? 'Récent' : city.region}</span>
                  </button>
                </li>
              )
            })}
          </m.ul>
        ) : null}
      </AnimatePresence>
    </div>
  )
}
