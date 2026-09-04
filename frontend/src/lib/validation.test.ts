import { describe, expect, it } from 'vitest'
import { identitySchema, momoSchema, optionalEmailSchema, phoneSchema, profileSchema, vehicleSchema } from './validation'

describe('phoneSchema', () => {
  it('accepte un numero beninois E.164 avec ou sans espaces', () => {
    expect(phoneSchema.safeParse('+22997000000').success).toBe(true)
    expect(phoneSchema.safeParse('+229 97 00 00 00').success).toBe(true)
  })

  it('refuse un numero incomplet, sans indicatif ou avec des lettres', () => {
    expect(phoneSchema.safeParse('9700').success).toBe(false)
    expect(phoneSchema.safeParse('abc').success).toBe(false)
    expect(phoneSchema.safeParse('').success).toBe(false)
  })
})

describe('optionalEmailSchema', () => {
  it('accepte vide ou une adresse valide, refuse le reste', () => {
    expect(optionalEmailSchema.safeParse('').success).toBe(true)
    expect(optionalEmailSchema.safeParse('a@b.co').success).toBe(true)
    expect(optionalEmailSchema.safeParse('pas-un-mail').success).toBe(false)
  })
})

describe('profileSchema', () => {
  it('exige prenom et nom', () => {
    expect(profileSchema.safeParse({ firstName: '', lastName: 'X', email: '', bio: '' }).success).toBe(false)
    expect(profileSchema.safeParse({ firstName: 'Ali', lastName: 'Bio', email: '', bio: '' }).success).toBe(true)
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
    expect(momoSchema.safeParse({ provider: 'MTN_MOMO', phone: '+22997000000' }).success).toBe(true)
    expect(momoSchema.safeParse({ provider: 'MTN_MOMO', phone: '12' }).success).toBe(false)
    expect(identitySchema.safeParse({ documentType: 'CNI', documentNumber: 'B123456' }).success).toBe(true)
    expect(identitySchema.safeParse({ documentType: 'CNI', documentNumber: '' }).success).toBe(false)
  })
})
