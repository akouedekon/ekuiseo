import { Filter, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { SelectField } from '@/components/forms/SelectField'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { AuditTable } from '@/features/admin/AuditTable'
import { AUDIT_ACTIONS, AUDIT_ENTITY_TYPES } from '@/features/admin/auditVocabulary'
import { useAuditLog } from '@/hooks/useAdmin'
import type { AuditLogFilters } from '@/api/extended'

const PAGE_SIZE = 25
const ANY = '__any__'

const ACTION_OPTIONS = [{ value: ANY, label: 'Toutes les actions' }, ...AUDIT_ACTIONS.map((a) => ({ value: a, label: a }))]
const ENTITY_OPTIONS = [{ value: ANY, label: 'Tous les objets' }, ...AUDIT_ENTITY_TYPES.map((t) => ({ value: t, label: t }))]

/** Etat du formulaire, en clair (dates au format yyyy-mm-dd des champs natifs). */
interface Draft {
  action: string
  actorId: string
  entityType: string
  entityId: string
  from: string
  to: string
}

const EMPTY_DRAFT: Draft = { action: '', actorId: '', entityType: '', entityId: '', from: '', to: '' }

function draftFromParams(params: URLSearchParams): Draft {
  return {
    action: params.get('action') ?? '',
    actorId: params.get('actorId') ?? '',
    entityType: params.get('entityType') ?? '',
    entityId: params.get('entityId') ?? '',
    from: params.get('from') ?? '',
    to: params.get('to') ?? '',
  }
}

/** Borne inclusive : le jour saisi couvre de 00:00 a 23:59:59 dans le fuseau du navigateur. */
function dayStart(date: string): string {
  return new Date(`${date}T00:00:00`).toISOString()
}
function dayEnd(date: string): string {
  return new Date(`${date}T23:59:59.999`).toISOString()
}

function filtersFromParams(params: URLSearchParams): AuditLogFilters {
  const draft = draftFromParams(params)
  return {
    action: draft.action || undefined,
    actorId: draft.actorId.trim() || undefined,
    entityType: draft.entityType || undefined,
    entityId: draft.entityId.trim() || undefined,
    from: draft.from ? dayStart(draft.from) : undefined,
    to: draft.to ? dayEnd(draft.to) : undefined,
  }
}

/**
 * Journal d'audit (GET /api/v1/admin/audit-log) : qui a fait quoi, filtre et
 * pagine cote serveur. Les filtres vivent dans l'URL pour qu'une recherche se
 * partage (« regarde ce qui s'est passe sur ce compte »).
 */
export function AdminAudit() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [draft, setDraft] = useState<Draft>(() => draftFromParams(searchParams))

  const page = Math.max(0, Number(searchParams.get('page') ?? 0) || 0)
  const filters = filtersFromParams(searchParams)
  const activeCount = Object.values(filters).filter(Boolean).length
  const audit = useAuditLog(page, PAGE_SIZE, filters)

  const write = (next: Draft, nextPage: number) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(next)) {
      if (value) params.set(key, value)
    }
    if (nextPage > 0) params.set('page', String(nextPage))
    setSearchParams(params)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    write(draft, 0)
  }

  const reset = () => {
    setDraft(EMPTY_DRAFT)
    write(EMPTY_DRAFT, 0)
  }

  return (
    <div>
      <AdminPageHeader
        title="Journal d'audit"
        count={audit.data?.totalElements}
        description="Actions sensibles du back-office et du système : suspensions, validations, reversements, remboursements."
      />

      <Card className="mb-4 p-4">
        <form onSubmit={submit} className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3" aria-label="Filtres du journal">
          <SelectField
            label="Action"
            value={draft.action || ANY}
            onValueChange={(value) => setDraft((d) => ({ ...d, action: value === ANY ? '' : value }))}
            options={ACTION_OPTIONS}
          />
          <SelectField
            label="Type d'objet"
            value={draft.entityType || ANY}
            onValueChange={(value) => setDraft((d) => ({ ...d, entityType: value === ANY ? '' : value }))}
            options={ENTITY_OPTIONS}
          />
          <Input
            label="Identifiant de l'objet"
            placeholder="UUID complet"
            value={draft.entityId}
            onChange={(event) => setDraft((d) => ({ ...d, entityId: event.target.value }))}
            spellCheck={false}
          />
          <Input
            label="Acteur (identifiant)"
            placeholder="UUID de l'administrateur ou de l'utilisateur"
            value={draft.actorId}
            onChange={(event) => setDraft((d) => ({ ...d, actorId: event.target.value }))}
            spellCheck={false}
          />
          <Input
            label="Du"
            type="date"
            value={draft.from}
            max={draft.to || undefined}
            onChange={(event) => setDraft((d) => ({ ...d, from: event.target.value }))}
          />
          <Input
            label="Au"
            type="date"
            value={draft.to}
            min={draft.from || undefined}
            onChange={(event) => setDraft((d) => ({ ...d, to: event.target.value }))}
          />
          <div className="flex flex-wrap items-end gap-2 sm:col-span-2 xl:col-span-3">
            <Button type="submit" size="sm" loading={audit.isFetching && !audit.isPending}>
              <Filter className="size-4" aria-hidden />
              Filtrer
            </Button>
            {activeCount > 0 ? (
              <Button type="button" size="sm" variant="ghost" onClick={reset}>
                <X className="size-4" aria-hidden />
                Effacer les filtres ({activeCount})
              </Button>
            ) : null}
          </div>
        </form>
      </Card>

      <AuditTable
        audit={audit}
        page={page}
        onPageChange={(next) => write(draftFromParams(searchParams), next)}
        emptyDescription={activeCount > 0 ? 'Aucune action ne correspond à ces filtres.' : 'Aucune action enregistrée pour le moment.'}
      />
    </div>
  )
}
