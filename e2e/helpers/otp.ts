import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

/**
 * Lecture des codes de connexion dans les journaux du backend.
 *
 * En mode MAIL_MODE=log avec OTP_LOG_PLAIN_CODES=true (docker-compose.e2e.yml),
 * LoggingMailGateway ecrit une ligne par e-mail :
 *   [MAIL-STUB] a <destinataire> | Votre code Ekuiseo : 123456 | Bonjour, ...
 * (objet compose par OtpDeliveryService : "Votre code Ekuiseo : " + code a 6 chiffres).
 * Sans OTP_LOG_PLAIN_CODES, le code est remplace par des etoiles et ces tests ne
 * peuvent pas se connecter : c'est voulu, un journal n'est pas un canal de livraison.
 */
const CONTAINER = process.env.E2E_BACKEND_CONTAINER ?? 'ekuiseo-backend'
const LOG_TAIL = process.env.E2E_LOG_TAIL ?? '6000'
const CODE_LINE = /\[MAIL-STUB\] a (\S+) \| Votre code Ekuiseo : (\d{6})/g

async function readBackendLogs(): Promise<string> {
  // `docker logs` renvoie la sortie standard ET la sortie d'erreur du conteneur.
  const { stdout, stderr } = await execFileAsync('docker', ['logs', '--tail', LOG_TAIL, CONTAINER], {
    maxBuffer: 64 * 1024 * 1024,
  })
  return `${stdout}\n${stderr}`
}

function codesFor(logs: string, email: string): string[] {
  const wanted = email.trim().toLowerCase()
  const codes: string[] = []
  for (const match of logs.matchAll(CODE_LINE)) {
    if (match[1].toLowerCase() === wanted) codes.push(match[2])
  }
  return codes
}

/**
 * Boite aux lettres d'un compte de test. Prendre un instantane AVANT de demander un
 * code, puis attendre un code plus recent : on ne rejoue jamais un code deja journalise
 * (le meme compte recoit plusieurs codes au fil d'un parcours).
 */
export class OtpMailbox {
  private seen = 0

  constructor(private readonly email: string) {}

  /** Memorise le nombre de codes deja journalises pour cette adresse. */
  async snapshot(): Promise<void> {
    this.seen = codesFor(await readBackendLogs(), this.email).length
  }

  /** Attend (sondage toutes les secondes) un code posterieur a l'instantane et le renvoie. */
  async waitForCode(timeoutMs = 45_000): Promise<string> {
    const deadline = Date.now() + timeoutMs
    let lastError: unknown
    while (Date.now() < deadline) {
      try {
        const codes = codesFor(await readBackendLogs(), this.email)
        if (codes.length > this.seen) {
          this.seen = codes.length
          return codes[codes.length - 1]
        }
      } catch (error) {
        lastError = error
      }
      await new Promise((resolve) => setTimeout(resolve, 1000))
    }
    const hint = lastError instanceof Error ? ` (${lastError.message})` : ''
    throw new Error(
      `Aucun code de connexion pour ${this.email} dans les journaux de ${CONTAINER} apres ${timeoutMs} ms${hint}. ` +
        'Verifiez que la pile tourne avec docker-compose.e2e.yml (MAIL_MODE=log, OTP_LOG_PLAIN_CODES=true).',
    )
  }
}
