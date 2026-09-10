import { execFile } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

/*
 * Acces SQL direct a la base de la pile E2E (docker-compose.yml + docker-compose.e2e.yml),
 * par `docker compose exec postgis psql`, comme e2e/scripts/seed.sh. Reserve aux
 * preparations de scenario que l interface ne permet pas de produire en quelques
 * secondes : faire partir un trajet dans le passe, poser un paiement encaisse il y a
 * deux jours, verifier un compte mobile money. Jamais utilise pour verifier un resultat
 * (les assertions passent par l ecran, comme un utilisateur).
 */
const ROOT = resolve(__dirname, '..', '..')
const COMPOSE_FILES = (process.env.COMPOSE_FILES ?? '-f docker-compose.yml -f docker-compose.e2e.yml').split(/\s+/)

interface DbConfig {
  name: string
  user: string
  password: string
}

/** Lit DB_NAME / DB_USER / DB_PASSWORD dans .env (ou .env.example), comme le seed. */
function readConfig(): DbConfig {
  const env: Record<string, string> = {}
  for (const file of ['.env', '.env.example']) {
    try {
      for (const line of readFileSync(resolve(ROOT, file), 'utf8').split('\n')) {
        const match = /^([A-Z_]+)=(.*)$/.exec(line.trim())
        if (match && !(match[1] in env)) env[match[1]] = match[2].replace(/^"(.*)"$/, '$1')
      }
      break
    } catch {
      /* fichier absent : on essaie le suivant */
    }
  }
  return {
    name: process.env.E2E_DB_NAME ?? env.DB_NAME ?? 'ekuiseo',
    user: process.env.E2E_DB_USER ?? env.DB_USER ?? 'ekuiseo',
    password: process.env.E2E_DB_PASSWORD ?? env.DB_PASSWORD ?? 'ekuiseo_dev_change_me',
  }
}

/** Execute une requete et renvoie les lignes (colonnes separees par `|`, sans en-tete). */
export async function sql(query: string): Promise<string[]> {
  const config = readConfig()
  const { stdout } = await execFileAsync(
    'docker',
    ['compose', ...COMPOSE_FILES, 'exec', '-T', '-e', `PGPASSWORD=${config.password}`, 'postgis',
      'psql', '-U', config.user, '-d', config.name, '-v', 'ON_ERROR_STOP=1', '-tA', '-c', query],
    { cwd: ROOT, maxBuffer: 8 * 1024 * 1024 },
  )
  return stdout
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

/** Identifiant du trajet dedie aux parcours (e2e/seed-e2e.sql). */
export const E2E_TRIP_ID = 'e2e00000-0000-0000-0000-000000000001'

/** Remet le trajet E2E dans l etat du seed : J+3 09:00, PUBLISHED, 4 places, reservations closes. */
export async function resetE2eTrip(): Promise<void> {
  await sql(`
    UPDATE bookings SET status = 'CANCELLED_BY_PASSENGER'
     WHERE trip_id = '${E2E_TRIP_ID}' AND status IN ('PENDING_PAYMENT', 'PENDING_DRIVER_APPROVAL', 'CONFIRMED', 'COMPLETED', 'DRIVER_NO_SHOW');
    UPDATE trips
       SET departure_at = ((((now() AT TIME ZONE 'Africa/Porto-Novo')::date + INTERVAL '3 days') + TIME '09:00') AT TIME ZONE 'Africa/Porto-Novo'),
           status = 'PUBLISHED', seats_available = 4, instant_booking = TRUE, live_sharing_enabled = FALSE
     WHERE id = '${E2E_TRIP_ID}';
  `)
}
