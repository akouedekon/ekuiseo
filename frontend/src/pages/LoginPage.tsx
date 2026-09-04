import { AnimatePresence, motion } from 'motion/react'
import { ArrowLeft, MessageSquareLock, Phone, ShieldCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { OtpInput } from '@/components/ui/otp-input'
import { PageContainer } from '@/components/layout/PageContainer'
import { Logo } from '@/components/layout/Logo'
import { useRequestOtp, useVerifyOtp } from '@/hooks/useAuth'
import { useCountdown } from '@/hooks/useNetwork'
import { formatCountdown, formatPhone } from '@/lib/format'

const RESEND_DELAY_MS = 45_000

/**
 * Connexion / inscription par telephone + code OTP.
 * Un seul parcours : pas de mot de passe, le numero est l'identite.
 */
export function LoginPage({ mode = 'login' }: { mode?: 'login' | 'register' }) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirectTo = searchParams.get('next') ?? '/'

  const [step, setStep] = useState<'phone' | 'code'>('phone')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [phoneError, setPhoneError] = useState<string>()
  const [codeError, setCodeError] = useState<string>()
  const [resendAt, setResendAt] = useState<number | null>(null)

  const requestOtp = useRequestOtp()
  const verifyOtp = useVerifyOtp()
  const resendIn = useCountdown(resendAt)

  // Le champ de code prend le focus des l'arrivee a l'etape 2.
  useEffect(() => {
    if (step === 'code') {
      const first = document.querySelector<HTMLInputElement>('input[autocomplete="one-time-code"]')
      first?.focus()
    }
  }, [step])

  const sendCode = (event?: React.FormEvent) => {
    event?.preventDefault()
    const digits = phone.replace(/\D/g, '')
    if (digits.length < 8) {
      setPhoneError('Numéro de téléphone incomplet')
      return
    }
    setPhoneError(undefined)
    requestOtp.mutate(phone, {
      onSuccess: () => {
        setResendAt(Date.now() + RESEND_DELAY_MS)
        setStep('code')
        toast.success('Code envoyé', { description: `Un SMS vient de partir vers le ${formatPhone(phone)}.` })
      },
      onError: () => toast.error("Le code n'a pas pu être envoyé. Réessayez."),
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
          toast.success('Connexion réussie')
          navigate(redirectTo, { replace: true })
        },
        onError: () => {
          setCodeError('Code incorrect ou expiré')
          setCode('')
        },
      },
    )
  }

  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-8rem)] flex-col justify-center">
      <div className="mb-6 text-center">
        <Logo size={44} withWordmark={false} className="justify-center" />
        <h1 className="headline mt-4 text-[28px]">
          {mode === 'register' ? 'Créer un compte' : 'Bienvenue sur Ekuiseo'}
        </h1>
        <p className="mt-1.5 text-[14px] text-muted">
          {step === 'phone'
            ? 'Votre numéro suffit : nous vous envoyons un code par SMS.'
            : `Code envoyé au ${formatPhone(phone)}`}
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
              <form onSubmit={sendCode} noValidate>
                <Input
                  label="Numéro de téléphone"
                  type="tel"
                  inputMode="tel"
                  autoComplete="tel"
                  autoFocus
                  value={phone}
                  onChange={(event) => setPhone(event.target.value)}
                  error={phoneError}
                  hint="Bénin (+229), Togo, Nigéria acceptés."
                  leading={<Phone />}
                  placeholder="+229 97 00 00 00"
                  className="tnum text-[17px] font-semibold"
                />
                <Button type="submit" size="lg" block className="mt-4" loading={requestOtp.isPending}>
                  Recevoir le code
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
                  <Link to="/login" className="font-semibold text-[var(--indigo)] underline-offset-4 hover:underline">
                    Se connecter
                  </Link>
                </>
              ) : (
                <>
                  Première visite ?{' '}
                  <Link
                    to="/register"
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

              {/* Renvoi avec minuterie : evite le matraquage de l'API SMS. */}
              <div className="mt-4 text-center text-[13px]">
                {resendIn > 0 ? (
                  <span className="tnum text-muted">Renvoyer le code dans {formatCountdown(resendIn)}</span>
                ) : (
                  <button
                    type="button"
                    onClick={() => sendCode()}
                    className="font-semibold text-[var(--indigo)] underline-offset-4 hover:underline"
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

/** Meme parcours, accroche differente : /register. */
export function RegisterPage() {
  return <LoginPage mode="register" />
}
