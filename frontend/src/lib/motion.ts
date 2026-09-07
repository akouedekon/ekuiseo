import type { Transition, Variants } from 'motion/react'

/**
 * Vocabulaire de mouvement unique pour toute l'application.
 * Regle : le mouvement sert a orienter (d'ou vient l'ecran, quel element repond),
 * jamais a decorer. Toutes les durees restent sous 400 ms, et `<MotionConfig
 * reducedMotion="user">` (main.tsx) neutralise tout sous prefers-reduced-motion.
 */

const EASE_OUT: [number, number, number, number] = [0.22, 1, 0.36, 1]
const EASE_IN_OUT: [number, number, number, number] = [0.65, 0, 0.35, 1]

export const springSoft: Transition = { type: 'spring', stiffness: 420, damping: 38, mass: 0.9 }

/** Transition de page directionnelle : +1 on avance, -1 on revient. */
export const pageVariants: Variants = {
  enter: (dir: number) => ({ opacity: 0, x: dir === 0 ? 0 : dir * 18, y: dir === 0 ? 8 : 0 }),
  center: { opacity: 1, x: 0, y: 0, transition: { duration: 0.26, ease: EASE_OUT } },
  exit: (dir: number) => ({
    opacity: 0,
    x: dir === 0 ? 0 : dir * -12,
    y: dir === 0 ? -6 : 0,
    transition: { duration: 0.16, ease: EASE_IN_OUT },
  }),
}

/** Apparition en cascade d'une liste (resultats de recherche, reservations…). */
export const listContainer: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.045, delayChildren: 0.02 } },
}

export const listItem: Variants = {
  hidden: { opacity: 0, y: 12 },
  show: { opacity: 1, y: 0, transition: { duration: 0.3, ease: EASE_OUT } },
}
