import { describe, expect, it } from 'vitest'
import {
  emailSchema,
  identitySchema,
  momoSchema,
  optionalEmailSchema,
  phoneSchema,
  profileSchema,
  toE164,
  vehicleSchema,
} from './validation'

describe('toE164', () => {
  it('normalise les numeros beninois a 10 chiffres, avec ou sans indicatif ni espaces', () => {
    expect(toE164('+229 01 97 00 03 22')).toBe('+2290197000322')
    expect(toE164('0197000322')).toBe('+2290197000322')
    expect(toE164('01 97 00 03 22')).toBe('+2290197000322')
    expect(toE164('00229 0197000322')).toBe('+2290197000322')
    expect(toE164('+229-01-96-87-03-71')).toBe('+2290196870371')
  })

  it('accepte les autres pays en E.164', () => {
    expect(toE164('+228 90 00 00 00')).toBe('+22890000000')
    expect(toE164('+234 803 000 0000')).toBe('+2348030000000')
  })

  it('refuse les anciens numeros beninois a 8 chiffres et les saisies invalides', () => {
    expect(toE164('+22997000322')).toBeNull()
    expect(toE164('97 00 03 22')).toBeNull()
    expect(toE164('+229 02 97 00 03 22')).toBeNull()
    expect(toE164('abc')).toBeNull()
    expect(toE164('')).toBeNull()
    expect(toE164('123456789')).toBeNull()
  })
})

describe('phoneSchema', () => {
  it('accepte un numero beninois a 10 chiffres, avec ou sans espaces ni indicatif', () => {
    expect(phoneSchema.safeParse('+2290197000322').success).toBe(true)
    expect(phoneSchema.safeParse('+229 01 97 00 03 22').success).toBe(true)
    expect(phoneSchema.safeParse('01 97 00 03 22').success).toBe(true)
  })

  it('explique le refus d un ancien numero a 8 chiffres', () => {
    const result = phoneSchema.safeParse('+229 97 00 03 22')
    expect(result.success).toBe(false)
    expect(result.success ? '' : result.error.issues[0]?.message).toContain('10 chiffres')
    const local = phoneSchema.safeParse('97 00 03 22')
    expect(local.success ? '' : local.error.issues[0]?.message).toContain('10 chiffres')
  })

  it('refuse un numero incomplet, sans indicatif ou avec des lettres', () => {
    expect(phoneSchema.safeParse('9700').success).toBe(false)
    expect(phoneSchema.safeParse('abc').success).toBe(false)
    expect(phoneSchema.safeParse('').success).toBe(false)
    const noPrefix = phoneSchema.safeParse('2290197000322')
    expect(noPrefix.success ? '' : noPrefix.error.issues[0]?.message).toContain('indicatif')
  })
})

describe('emailSchema et optionalEmailSchema', () => {
  it('exige une adresse valide, ou accepte vide pour la variante facultative', () => {
    expect(emailSchema.safeParse('').success).toBe(false)
    expect(emailSchema.safeParse('a@b.co').success).toBe(true)
    expect(optionalEmailSchema.safeParse('').success).toBe(true)
    expect(optionalEmailSchema.safeParse('a@b.co').success).toBe(true)
    expect(optionalEmailSchema.safeParse('pas-un-mail').success).toBe(false)
  })
})

describe('profileSchema', () => {
  it('exige prenom et nom, sans champ e-mail', () => {
    expect(profileSchema.safeParse({ firstName: '', lastName: 'X', bio: '' }).success).toBe(false)
    expect(profileSchema.safeParse({ firstName: 'Ali', lastName: 'Bio', bio: '' }).success).toBe(true)
    expect('email' in profileSchema.shape).toBe(false)
  })
})

describe('vehicleSchema', () => {
  it('borne les places entre 1 et 8', () => {
    const base = { vehicleType: 'CAR' as const, brand: 'Toyota', model: 'Corolla', color: '', plate: 'AB 1234', comfortLevel: 'COMFORT' as const }
    expect(vehicleSchema.safeParse({ ...base, seats: 0 }).success).toBe(false)
    expect(vehicleSchema.safeParse({ ...base, seats: 9 }).success).toBe(false)
    expect(vehicleSchema.safeParse({ ...base, seats: 4 }).success).toBe(true)
  })

  it('borne les places selon le type (V22) : moto 1, tricycle 6, voiture 8', () => {
    const base = { brand: 'Haojue', model: 'HJ 125', color: '', plate: 'MT 4521 RB', comfortLevel: 'BASIC' as const }
    expect(vehicleSchema.safeParse({ ...base, vehicleType: 'MOTO', seats: 1 }).success).toBe(true)
    const moto = vehicleSchema.safeParse({ ...base, vehicleType: 'MOTO', seats: 2 })
    expect(moto.success).toBe(false)
    expect(moto.success ? '' : moto.error.issues[0]?.message).toMatch(/un passager/)
    expect(vehicleSchema.safeParse({ ...base, vehicleType: 'TRICYCLE', seats: 6 }).success).toBe(true)
    expect(vehicleSchema.safeParse({ ...base, vehicleType: 'TRICYCLE', seats: 7 }).success).toBe(false)
    expect(vehicleSchema.safeParse({ ...base, vehicleType: 'AVION', seats: 1 }).success).toBe(false)
  })
})

describe('momoSchema et identitySchema', () => {
  it('valident operateur + numero, et type + numero de piece', () => {
    expect(momoSchema.safeParse({ provider: 'MTN_MOMO', phone: '+2290197000322' }).success).toBe(true)
    expect(momoSchema.safeParse({ provider: 'MTN_MOMO', phone: '+22997000322' }).success).toBe(false)
    expect(momoSchema.safeParse({ provider: 'MTN_MOMO', phone: '12' }).success).toBe(false)
    expect(identitySchema.safeParse({ documentType: 'CNI', documentNumber: 'B123456' }).success).toBe(true)
    expect(identitySchema.safeParse({ documentType: 'CNI', documentNumber: '' }).success).toBe(false)
  })
})

describe('horaires de publication (heure du Benin, quel que soit le fuseau de la machine)', () => {
  // Instants exprimes en UTC : le Benin est a UTC+1 toute l'annee (07:02 au Benin = 06:02Z).
  it('propose la prochaine demi-heure ronde au moins 15 minutes plus tard', async () => {
    const { nextHalfHour } = await import('./validation')
    expect(nextHalfHour(new Date('2026-09-07T06:02:00Z'))).toEqual({ date: '2026-09-07', time: '07:30' })
    expect(nextHalfHour(new Date('2026-09-07T06:20:00Z'))).toEqual({ date: '2026-09-07', time: '08:00' })
    expect(nextHalfHour(new Date('2026-09-07T22:50:00Z'))).toEqual({ date: '2026-09-08', time: '00:30' })
  })

  it('reconstruit un instant depuis une saisie en heure du Benin et refuse une saisie incomplete', async () => {
    const { departureFromFields } = await import('./validation')
    expect(departureFromFields('2026-09-07', '07:30')?.toISOString()).toBe('2026-09-07T06:30:00.000Z')
    expect(departureFromFields('', '07:30')).toBeNull()
    expect(departureFromFields('2026-09-07', '7h30')).toBeNull()
  })
})

describe('registerSchema', () => {
  it("exige l'acceptation des CGU", async () => {
    const { registerSchema } = await import('./validation')
    const base = { phone: '+2290197000322', firstName: 'Koffi', lastName: 'Aholou', email: 'k@example.com' }
    expect(registerSchema.safeParse({ ...base, acceptTerms: true }).success).toBe(true)
    const refused = registerSchema.safeParse({ ...base, acceptTerms: false })
    expect(refused.success).toBe(false)
    if (!refused.success) {
      expect(refused.error.issues.some((i) => i.path[0] === 'acceptTerms')).toBe(true)
    }
  })
})
