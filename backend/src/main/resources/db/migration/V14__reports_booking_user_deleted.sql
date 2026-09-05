-- V14 : lot 1.4 de l audit (signalements, suppression de compte).
--
-- reports.booking_id : reservation qui lie le signalant a la cible (constat F548). Nullable :
--   un signalement FRAUD/OTHER peut viser un profil sans trajet commun ; les motifs qui
--   supposent un trajet partage (NO_SHOW, DANGEROUS_DRIVING, HARASSMENT, VEHICLE_MISMATCH)
--   exigent une reservation, verifiee par ReportService.
-- users.status DELETED + users.deleted_at : compte anonymise (constat F507, droit a l
--   effacement). users.status n a jamais eu de contrainte CHECK (V1) : rien a etendre.
--   Le numero de telephone d un compte supprime devient '+999' + 12 chiffres derives de
--   l identifiant (conforme a chk_users_phone_e164 et a l unicite), voir UserService#anonymize.
ALTER TABLE reports ADD COLUMN IF NOT EXISTS booking_id UUID REFERENCES bookings(id);
CREATE INDEX IF NOT EXISTS idx_reports_reporter_created ON reports (reporter_id, created_at DESC);

ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
