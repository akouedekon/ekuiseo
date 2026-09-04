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
  /** Role de la personne NOTEE : DRIVER quand un passager note son conducteur. */
  role: ReviewRole
}

const RATING_LABEL = ['', 'Très mauvais', 'Mauvais', 'Correct', 'Bien', 'Excellent']

/** Avis apres un trajet termine (POST /api/v1/trips/{id}/reviews) : note de 1 a 5 et commentaire facultatif. */
export function ReviewDialog({ open, onOpenChange, tripId, target, role }: ReviewDialogProps) {
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
            <div className="flex justify-center gap-1" role="radiogroup" aria-label="Note sur 5">
              {[1, 2, 3, 4, 5].map((value) => (
                <button
                  key={value}
                  type="button"
                  role="radio"
                  aria-checked={rating === value}
                  aria-label={`${value} sur 5 : ${RATING_LABEL[value]}`}
                  onMouseEnter={() => setHover(value)}
                  onMouseLeave={() => setHover(0)}
                  onFocus={() => setHover(value)}
                  onBlur={() => setHover(0)}
                  onClick={() => setRating(value)}
                  className="flex size-11 items-center justify-center rounded-[var(--radius-control)] transition-transform hover:scale-110"
                >
                  <Star
                    className={cn(
                      'size-8 transition-colors',
                      value <= shown ? 'fill-[var(--ocre)] text-[var(--ocre)]' : 'text-rule-strong',
                    )}
                    aria-hidden
                  />
                </button>
              ))}
            </div>
            <p className="mt-1 text-center text-[13px] font-medium text-ink-2" aria-live="polite">
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
