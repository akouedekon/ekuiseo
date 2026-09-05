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
    const base = { brand: 'Toyota', model: 'Corolla', color: '', plate: 'AB 1234', comfortLevel: 'COMFORT' as const }
    expect(vehicleSchema.safeParse({ ...base, seats: 0 }).success).toBe(false)
    expect(vehicleSchema.safeParse({ ...base, seats: 9 }).success).toBe(false)
    expect(vehicleSchema.safeParse({ ...base, seats: 4 }).success).toBe(true)
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
