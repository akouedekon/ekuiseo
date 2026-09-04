import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Concatene des classes conditionnelles puis resout les conflits Tailwind. */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
