-- Ekuiseo - V8 : index de support pour les statistiques de profil public d'un
-- conducteur (regle metier n.22 : reliabilityRate, responseTimeMinutes).
--
-- MessageRepository#getResponseTimeStats (delai median de premiere reponse du
-- conducteur) filtre les messages d'une conversation a la fois par expediteur
-- (le passager, puis le conducteur) et trie/agrege par date d'envoi ; cet index
-- composite sert les deux filtres sans balayer les messages d'un tiers dans la
-- conversation. Aucun autre index n'est necessaire pour cette regle : la
-- statistique de fiabilite (BookingRepository#getReliabilityStats) s'appuie
-- entierement sur idx_trips_driver et idx_bookings_trip, deja presents depuis
-- V1__init.sql.
CREATE INDEX idx_messages_conversation_sender_created
    ON messages (conversation_id, sender_id, created_at);
