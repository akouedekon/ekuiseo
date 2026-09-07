-- V15 : phase 2 de l audit, bornes de plateforme et retention.
--
-- messages.body : borne dure alignee sur SendMessageRequest (@Size(max = 2000), constat F547).
--   Les messages existants plus longs (aucun attendu) sont tronques pour que la contrainte passe.
-- messages(sender_id, created_at) : l index V8 (conversation_id, sender_id, created_at) ne sert
--   pas un compte par expediteur seul (limitation de debit par utilisateur, purge par auteur).
-- reports.reason_code : le motif est desormais type (ReportReason) des l API (constat F551) ;
--   les valeurs libres deja enregistrees sont repliees sur OTHER puis la liste est verrouillee.
-- vehicles.deleted_at : suppression logique (constat F124). Un vehicule ayant servi sur un
--   trajet passe ou annule gardait une FK vivante depuis trips.vehicle_id, et sa suppression
--   physique echouait en 409 generique. Il est desormais masque (deleted_at) et l historique
--   des trajets conserve la reference.
UPDATE messages SET body = left(body, 2000) WHERE length(body) > 2000;
ALTER TABLE messages ADD CONSTRAINT chk_messages_body_len CHECK (length(body) <= 2000);
CREATE INDEX IF NOT EXISTS idx_messages_sender_created ON messages (sender_id, created_at);

UPDATE reports SET reason_code = 'OTHER'
WHERE reason_code NOT IN ('NO_SHOW', 'DANGEROUS_DRIVING', 'HARASSMENT', 'FRAUD', 'VEHICLE_MISMATCH', 'OTHER');
ALTER TABLE reports ADD CONSTRAINT chk_reports_reason_code
    CHECK (reason_code IN ('NO_SHOW', 'DANGEROUS_DRIVING', 'HARASSMENT', 'FRAUD', 'VEHICLE_MISMATCH', 'OTHER'));

ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_vehicles_owner_active ON vehicles (owner_id) WHERE deleted_at IS NULL;
