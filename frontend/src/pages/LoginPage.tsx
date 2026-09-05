import { AnimatePresence, motion } from 'motion/react'
import { ArrowLeft, Mail, MailCheck, MessageSquareLock, Phone, ShieldCheck, UserRound } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { OtpInput } from '@/components/ui/otp-input'
import { PageContainer } from '@/components/layout/PageContainer'
import { Logo } from '@/components/layout/Logo'
import { ApiError } from '@/api/client'
import type { OtpRequestResponse } from '@/api/types'
import { useRegisterOtp, useRequestOtp, useVerifyOtp } from '@/hooks/useAuth'
import { useCountdown } from '@/hooks/useNetwork'
import { describeError } from '@/lib/errors'
import { formatCountdown, formatPhone } from '@/lib/format'
import { emailSchema, phoneSchema } from '@/lib/validation'

const RESEND_DELAY_MS = 45_000

/** Seuls les chemins internes sont acceptes comme destination de retour. */
function safeNext(value: string | null): string {
  if (!value || !value.startsWith('/') || value.startsWith('//')) return '/'
  return value
}

/**
 * Connexion et inscription par telephone + code a 6 chiffres. Pas de mot de passe :
 * le numero est l'identifiant, le code part a l'adresse e-mail du compte (SMS en
 * repli si le serveur l'a configure). L'inscription demande prenom, nom et e-mail,
 * tous obligatoires cote serveur.
 */
export function LoginPage({ mode = 'login' }: { mode?: 'login' | 'register' }) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirectTo = safeNext(searchParams.get('next'))

  const [step, setStep] = useState<'phone' | 'code'>('phone')
  const [phone, setPhone] = useState('')
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [errors, setErrors] = useState<{ phone?: string; firstName?: string; lastName?: string; email?: string }>({})
  const [codeError, setCodeError] = useState<string>()
  const [resendAt, setResendAt] = useState<number | null>(null)
  /** Ou le dernier code est parti (canal + destination masquee), pour guider l'utilisateur. */
  const [delivery, setDelivery] = useState<OtpRequestResponse | null>(null)

  const requestOtp = useRequestOtp()
  const registerOtp = useRegisterOtp()
  const verifyOtp = useVerifyOtp()
  const resendIn = useCountdown(resendAt)
  const sending = requestOtp.isPending || registerOtp.isPending

  // Le champ de code prend le focus des l'arrivee a l'etape 2.
  useEffect(() => {
    if (step === 'code') {
      const first = document.querySelector<HTMLInputElement>('input[autocomplete="one-time-code"]')
      first?.focus()
    }
  }, [step])

  const validate = (): boolean => {
    const next: typeof errors = {}
    const parsedPhone = phoneSchema.safeParse(phone)
    if (!parsedPhone.success) next.phone = parsedPhone.error.issues[0]?.message ?? 'Numéro de téléphone incomplet'
    if (mode === 'register') {
      if (!firstName.trim()) next.firstName = 'Indiquez votre prénom'
      if (!lastName.trim()) next.lastName = 'Indiquez votre nom'
      const parsedEmail = emailSchema.safeParse(email)
      if (!parsedEmail.success) next.email = parsedEmail.error.issues[0]?.message ?? 'Adresse e-mail invalide'
    }
    setErrors(next)
    return Object.keys(next).length === 0
  }

  const onCodeSent = (sent: OtpRequestResponse) => {
    setDelivery(sent)
    setResendAt(Date.now() + RESEND_DELAY_MS)
    setStep('code')
    toast.success('Code envoyé', {
      description:
        sent.channel === 'EMAIL'
          ? `Un e-mail vient de partir vers ${sent.destination}.`
          : `Un SMS vient de partir vers le ${formatPhone(phone)}.`,
    })
  }

  /** Erreurs metier de la demande de code, affichees sous le champ plutot qu'en toast. */
  const onSendError = (error: unknown, fallback: string) => {
    if (error instanceof ApiError && error.status === 404) {
      setErrors({ phone: 'Aucun compte pour ce numéro : créez-en un.' })
      return
    }
    if (error instanceof ApiError && error.status === 401) {
      setErrors({ phone: 'Ce compte est suspendu. Contactez le support Ekuiseo.' })
      return
    }
    if (error instanceof ApiError && error.status === 400) {
      setErrors({ phone: error.message })
      return
    }
    toast.error(describeError(error, fallback))
  }

  const sendCode = (event?: React.FormEvent) => {
    event?.preventDefault()
    if (!validate()) return
    if (mode === 'register' && step === 'phone') {
      registerOtp.mutate(
        { phone, firstName: firstName.trim(), lastName: lastName.trim(), email: email.trim() },
        {
          onSuccess: onCodeSent,
          onError: (error) => {
            if (error instanceof ApiError && error.status === 409) {
              const onEmail = /e-mail/i.test(error.message)
              setErrors(
                onEmail
                  ? { email: 'Cette adresse a déjà un compte : connectez-vous.' }
                  : { phone: 'Ce numéro a déjà un compte : connectez-vous.' },
              )
              return
            }
            toast.error(describeError(error, "L'inscription n'a pas abouti. Réessayez."))
          },
        },
      )
      return
    }
    requestOtp.mutate(phone, {
      onSuccess: onCodeSent,
      onError: (error) => onSendError(error, "Le code n'a pas pu être envoyé. Réessayez."),
    })
  }

  const verify = (value: string) => {
    if (value.length < 6) {
      setCodeError('Le code comporte 6 chiffres')
      return
    }
    setCodeError(undefined)
    verifyOtp.mutate(
      { phone, code: value },
      {
        onSuccess: () => {
          toast.success(mode === 'register' ? 'Bienvenue sur Ekuiseo' : 'Connexion réussie')
          navigate(redirectTo, { replace: true })
        },
        onError: (error) => {
          if (error instanceof ApiError && error.status === 404) {
            setCodeError('Aucun compte pour ce numéro : créez-en un.')
          } else if (error instanceof ApiError && error.status === 401) {
            setCodeError('Ce compte est suspendu. Contactez le support Ekuiseo.')
          } else {
            setCodeError(describeError(error, 'Code incorrect ou expiré'))
          }
          setCode('')
        },
      },
    )
  }

  const sentTo =
    delivery?.channel === 'SMS'
      ? `Code envoyé par SMS au ${formatPhone(phone)}`
      : `Code envoyé par e-mail à ${delivery?.destination ?? 'votre adresse'}`

  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-8rem)] flex-col justify-center">
      <div className="mb-8 text-center">
        <Logo size={48} variant="mark" className="justify-center drop-shadow-[0_8px_16px_rgb(14_124_74/0.28)]" />
        <h1 className="headline mt-5 text-[28px] sm:text-display-lg">
          {mode === 'register' ? 'Créer un compte' : 'Bienvenue sur Ekuiseo'}
        </h1>
        <p className="mt-1.5 text-[14px] text-muted">
          {step === 'phone'
            ? mode === 'register'
              ? 'Quelques informations, puis un code envoyé sur votre e-mail pour ouvrir la session.'
              : "Votre numéro suffit : nous envoyons un code sur l'e-mail de votre compte."
            : sentTo}
        </p>
      </div>

      <AnimatePresence mode="wait">
        {step === 'phone' ? (
          <motion.div
            key="phone"
            initial={{ opacity: 0, x: 16 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -16 }}
            transition={{ duration: 0.22 }}
          >
            <Card className="p-5">
              <form onSubmit={sendCode} noValidate className="space-y-4">
                {mode === 'register' ? (
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Input
                      label="Prénom"
                      autoComplete="given-name"
                      autoFocus
                      value={firstName}
                      onChange={(event) => setFirstName(event.target.value)}
                      error={errors.firstName}
                      leading={<UserRound />}
                      placeholder="Koffi"
                    />
                    <Input
                      label="Nom"
                      autoComplete="family-name"
                      value={lastName}
                      onChange={(event) => setLastName(event.target.value)}
                      error={errors.lastName}
                      placeholder="Aholou"
                    />
                  </div>
                ) : null}
                <Input
                  label="Numéro de téléphone"
                  type="tel"
                  inputMode="tel"
                  autoComplete="tel"
                  autoFocus={mode === 'login'}
                  value={phone}
                  onChange={(event) => setPhone(event.target.value)}
                  error={errors.phone}
                  hint="Bénin : +229 suivi des 10 chiffres (01 …). Togo et Nigéria acceptés."
                  leading={<Phone />}
                  placeholder="+229 01 97 00 00 00"
                  className="tnum text-[17px] font-semibold"
                />
                {mode === 'register' ? (
                  <Input
                    label="E-mail"
                    type="email"
                    inputMode="email"
                    autoComplete="email"
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                    error={errors.email}
                    hint="Le code de connexion est envoyé à cette adresse."
                    leading={<Mail />}
                    placeholder="vous@exemple.com"
                  />
                ) : null}
                <Button type="submit" size="lg" block loading={sending}>
                  {mode === 'register' ? 'Créer mon compte' : 'Recevoir le code'}
                </Button>
              </form>

              <p className="mt-4 flex items-start gap-2 text-[12px] leading-relaxed text-muted">
                <ShieldCheck className="mt-0.5 size-4 shrink-0" aria-hidden />
                Votre numéro n'est jamais affiché publiquement. Il sert à vous identifier et à joindre le conducteur
                le jour du trajet.
              </p>
            </Card>

            <p className="mt-4 text-center text-[14px] text-muted">
              {mode === 'register' ? (
                <>
                  Vous avez déjà un compte ?{' '}
                  <Link
                    to={`/login${searchParams.get('next') ? `?next=${encodeURIComponent(redirectTo)}` : ''}`}
                    className="font-semibold text-[var(--indigo)] underline-offset-4 hover:underline"
                  >
                    Se connecter
                  </Link>
                </>
              ) : (
                <>
                  Première visite ?{' '}
                  <Link
                    to={`/register${searchParams.get('next') ? `?next=${encodeURIComponent(redirectTo)}` : ''}`}
                    className="font-semibold text-[var(--indigo)] underline-offset-4 hover:underline"
                  >
                    Créer un compte
                  </Link>
                </>
              )}
            </p>
          </motion.div>
        ) : (
          <motion.div
            key="code"
            initial={{ opacity: 0, x: 16 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -16 }}
            transition={{ duration: 0.22 }}
          >
            <Card className="p-5">
              <div className="mb-4 flex items-center gap-2 text-[13px] font-medium text-ink-2">
                <MessageSquareLock className="size-4 text-[var(--indigo)]" aria-hidden />
                Saisissez le code à 6 chiffres
              </div>

              {delivery?.channel !== 'SMS' ? (
                <p className="mb-4 flex items-start gap-2 rounded-lg bg-[var(--surface-2)] p-3 text-[12px] leading-relaxed text-muted">
                  <MailCheck className="mt-0.5 size-4 shrink-0 text-[var(--indigo)]" aria-hidden />
                  L'e-mail peut mettre une minute à arriver. Vérifiez aussi le dossier « Spam » ou « Indésirables ».
                </p>
              ) : null}

              <OtpInput
                value={code}
                onChange={(value) => {
                  setCode(value)
                  if (codeError) setCodeError(undefined)
                }}
                onComplete={verify}
                error={!!codeError}
                disabled={verifyOtp.isPending}
              />

              {codeError ? (
                <p role="alert" className="mt-2 text-center text-[13px] font-medium text-[var(--vermillon)]">
                  {codeError}
                </p>
              ) : null}

              <Button
                size="lg"
                block
                className="mt-4"
                loading={verifyOtp.isPending}
                disabled={code.length < 6}
                onClick={() => verify(code)}
              >
                Valider
              </Button>

              {/* Renvoi avec minuterie : evite le matraquage du fournisseur d'e-mail ou de SMS. */}
              <div className="mt-4 text-center text-[13px]">
                {resendIn > 0 ? (
                  <span className="tnum text-muted">Renvoyer le code dans {formatCountdown(resendIn)}</span>
                ) : (
                  <button
                    type="button"
                    disabled={sending}
                    onClick={() =>
                      requestOtp.mutate(phone, {
                        onSuccess: onCodeSent,
                        onError: (error) => toast.error(describeError(error, "Le code n'a pas pu être renvoyé.")),
                      })
                    }
                    className="font-semibold text-[var(--indigo)] underline-offset-4 hover:underline disabled:opacity-60"
                  >
                    Renvoyer le code
                  </button>
                )}
              </div>
            </Card>

            <Button
              variant="ghost"
              block
              className="mt-3"
              onClick={() => {
                setStep('phone')
                setCode('')
                setCodeError(undefined)
              }}
            >
              <ArrowLeft className="size-4" aria-hidden />
              Modifier le numéro
            </Button>
          </motion.div>
        )}
      </AnimatePresence>
    </PageContainer>
  )
}

/** Inscription : memes etapes, avec prenom, nom et e-mail (obligatoire). */
export function RegisterPage() {
  return <LoginPage mode="register" />
}
