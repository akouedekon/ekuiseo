-- ============================================================
-- V22 : types de vehicule (voiture, moto, tricycle)
-- ============================================================
-- Au Benin, le transport partage se fait autant a moto (zemidjan) et en tricycle qu en
-- voiture. Chaque vehicule porte desormais son type ; il conditionne le nombre de places
-- (moto : 1 passager, tricycle : 6 au plus, voiture : 8 au plus, controle par
-- UserService#addVehicle), l affichage (icone, badge) et un filtre de recherche.
-- Les vehicules existants sont des voitures.
-- ============================================================

ALTER TABLE vehicles
    ADD COLUMN IF NOT EXISTS vehicle_type VARCHAR(20) NOT NULL DEFAULT 'CAR';

ALTER TABLE vehicles DROP CONSTRAINT IF EXISTS chk_vehicles_type;
ALTER TABLE vehicles ADD CONSTRAINT chk_vehicles_type
    CHECK (vehicle_type IN ('CAR', 'MOTO', 'TRICYCLE'));

-- Le filtre de recherche par type lit vehicles depuis trips.vehicle_id : index couvrant.
CREATE INDEX IF NOT EXISTS idx_vehicles_type ON vehicles(vehicle_type);

COMMENT ON COLUMN vehicles.vehicle_type IS 'CAR, MOTO ou TRICYCLE (V22) ; borne le nombre de places.';
