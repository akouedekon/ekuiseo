/**
 * Cle de transition d ecran (AppShell, AnimatePresence) pour un chemin donne.
 *
 * Par defaut, chaque chemin est un ecran : la transition joue a chaque navigation. Le
 * back-office fait exception : ses ecrans partagent une coque (AdminLayout : menu, files
 * d attente) et se chargent a la demande. Les faire sortir et rentrer a chaque clic
 * demontait la coque, rejouait ses requetes, et surtout combinait une sortie animee
 * (`mode="wait"`) avec un enfant qui suspend (React.lazy) : c est la recette d un ecran
 * vide sur Safari iOS. Une seule cle pour tout /admin : la coque reste en place, seul
 * le contenu change.
 */
export function transitionKeyOf(pathname: string): string {
  if (pathname === '/admin' || pathname.startsWith('/admin/')) return '/admin'
  return pathname
}
