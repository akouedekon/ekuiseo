-- ============================================================
-- V25 : sort de l acompte quand le conducteur est declare absent
-- ============================================================
-- Depuis V21, un passager peut declarer que le conducteur n est pas venu : la reservation
-- passe DRIVER_NO_SHOW et sort des reversements, mais rien ne garantissait le remboursement
-- (la moderation devait trancher a la main). Regle a partir de cette migration :
--   1. a la declaration, une echeance de remboursement automatique est posee
--      (driver_no_show_refund_due_at = declaration + 24 h, fenetre de contestation) ;
--   2. le conducteur peut contester avant l echeance (driver_no_show_contested_at) : le
--      remboursement est gele et la moderation decide ;
--   3. sans contestation a l echeance, l acompte est rembourse integralement au passager
--      (driver_no_show_resolution = REFUND_PASSENGER, resolved_by NULL = automatique) ;
--   4. la moderation peut trancher a tout moment : REFUND_PASSENGER (remboursement) ou
--      PAY_DRIVER (la reservation redevient COMPLETED et rejoint le prochain reversement).
-- ============================================================

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS driver_no_show_refund_due_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS driver_no_show_contested_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS driver_no_show_contest_details TEXT,
    ADD COLUMN IF NOT EXISTS driver_no_show_resolution      VARCHAR(20),
    ADD COLUMN IF NOT EXISTS driver_no_show_resolved_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS driver_no_show_resolved_by     UUID;

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS chk_bookings_driver_no_show_resolution;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_driver_no_show_resolution
    CHECK (driver_no_show_resolution IS NULL OR driver_no_show_resolution IN ('REFUND_PASSENGER', 'PAY_DRIVER'));

-- Balayage du scheduler : seules les declarations non contestees et non tranchees.
CREATE INDEX IF NOT EXISTS idx_bookings_driver_no_show_due
    ON bookings (driver_no_show_refund_due_at)
    WHERE status = 'DRIVER_NO_SHOW' AND driver_no_show_resolution IS NULL AND driver_no_show_contested_at IS NULL;

-- Declarations anterieures a cette migration, jamais tranchees : le conducteur dispose de
-- 24 h a compter du deploiement pour contester, puis le passager est rembourse.
UPDATE bookings
   SET driver_no_show_refund_due_at = now() + interval '24 hours'
 WHERE status = 'DRIVER_NO_SHOW'
   AND driver_no_show_refund_due_at IS NULL
   AND driver_no_show_resolution IS NULL;

COMMENT ON COLUMN bookings.driver_no_show_refund_due_at IS
    'Echeance du remboursement automatique de l acompte apres declaration d un conducteur absent (V25) ; declaration + 24 h.';
COMMENT ON COLUMN bookings.driver_no_show_contested_at IS
    'Le conducteur conteste l absence declaree : remboursement gele, decision de la moderation (V25).';
COMMENT ON COLUMN bookings.driver_no_show_resolution IS
    'Issue : REFUND_PASSENGER (acompte rembourse) ou PAY_DRIVER (trajet maintenu, reservation COMPLETED et reversable). resolved_by NULL = automatique.';
