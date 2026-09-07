import * as RadioGroupPrimitive from '@radix-ui/react-radio-group'
import { Star } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/input'
import { useCreateReview } from '@/hooks/useReviews'
import { cn } from '@/lib/cn'
import { describeError } from '@/lib/errors'
import type { ReviewRole } from '@/api/types'

interface ReviewDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  tripId: string
  target: { id: string; name: string }
  /** Role de la personne NOTEE : DRIVER quand un passager note son conducteur, PASSENGER dans l'autre sens. */
  role: ReviewRole
  /** Appele apres enregistrement : l'appelant memorise que cette personne est notee (le serveur n'expose pas toujours l'etat). */
  onReviewed?: () => void
}

const RATING_LABEL = ['', 'Très mauvais', 'Mauvais', 'Correct', 'Bien', 'Excellent']

/**
 * Avis apres un trajet termine (POST /api/v1/trips/{id}/reviews) : note de 1 a 5
 * et commentaire facultatif. Les etoiles forment un groupe radio Radix :
 * fleches du clavier et tabindex tournant fournis (audit F321).
 */
export function ReviewDialog({ open, onOpenChange, tripId, target, role, onReviewed }: ReviewDialogProps) {
  const [rating, setRating] = useState(0)
  const [hover, setHover] = useState(0)
  const [comment, setComment] = useState('')
  const review = useCreateReview()
  const shown = hover || rating

  const submit = () => {
    if (rating === 0) return
    review.mutate(
      { tripId, input: { targetId: target.id, role, rating, comment: comment.trim() || undefined } },
      {
        onSuccess: () => {
          onOpenChange(false)
          onReviewed?.()
          toast.success('Merci pour votre avis', { description: `${target.name} a été noté ${rating}/5.` })
        },
        onError: (error) => toast.error(describeError(error, "L'avis n'a pas pu être enregistré.")),
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !review.isPending && onOpenChange(next)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Noter {target.name}</DialogTitle>
          <DialogDescription>
            Votre note est publique et aide les autres membres à choisir. Un seul avis par trajet.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <RadioGroupPrimitive.Root
              value={rating === 0 ? undefined : String(rating)}
              onValueChange={(value) => setRating(Number(value))}
              orientation="horizontal"
              aria-label="Note sur 5"
              className="flex justify-center gap-1"
            >
              {[1, 2, 3, 4, 5].map((value) => (
                <RadioGroupPrimitive.Item
                  key={value}
                  value={String(value)}
                  aria-label={`${value} sur 5 : ${RATING_LABEL[value]}`}
                  onMouseEnter={() => setHover(value)}
                  onMouseLeave={() => setHover(0)}
                  onFocus={() => setHover(value)}
                  onBlur={() => setHover(0)}
                  className="flex size-11 items-center justify-center rounded-[var(--radius-control)] transition-transform hover:scale-110"
                >
                  <Star
                    className={cn('size-8 transition-colors', value <= shown ? 'fill-accent text-accent-ink' : 'text-field-border')}
                    aria-hidden
                  />
                </RadioGroupPrimitive.Item>
              ))}
            </RadioGroupPrimitive.Root>
            <p className="mt-1 text-center text-label font-medium text-ink-2" aria-live="polite">
              {shown > 0 ? RATING_LABEL[shown] : 'Touchez une étoile'}
            </p>
          </div>
          <Textarea
            label="Commentaire (facultatif)"
            hint="Ponctualité, conduite, ambiance… 400 caractères maximum."
            maxLength={400}
            rows={3}
            value={comment}
            onChange={(event) => setComment(event.target.value)}
          />
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={review.isPending}>
            Plus tard
          </Button>
          <Button onClick={submit} loading={review.isPending} disabled={rating === 0}>
            Publier l'avis
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
