-- Ekuiseo - covoiturage Benin - schema initial
-- NB : PostGIS est utilise pour les colonnes geography(Point,4326).
-- Par simplicite cote applicatif (pas de dependance JTS/hibernate-spatial),
-- les entites JPA manipulent des couples (lat, lng) en double precision et
-- des triggers PostgreSQL recalculent automatiquement les colonnes
-- geography a chaque insertion/mise a jour. Les requetes geospatiales
-- (ST_DWithin, ST_Distance) s'appuient directement sur ces colonnes
-- geography en SQL natif.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================
-- USERS
-- ============================================================
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    phone               VARCHAR(20) NOT NULL UNIQUE,
    email               VARCHAR(255),
    first_name          VARCHAR(100) NOT NULL,
    last_name           VARCHAR(100) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    photo_url           VARCHAR(500),
    bio                 TEXT,
    birth_date          DATE,
    gender_pref_note    VARCHAR(255),
    phone_verified      BOOLEAN NOT NULL DEFAULT FALSE,
    identity_verified   BOOLEAN NOT NULL DEFAULT FALSE,
    rating_avg          NUMERIC(3,2) NOT NULL DEFAULT 0,
    rating_count        INTEGER NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_phone_e164 CHECK (phone ~ '^\+[1-9][0-9]{7,14}$')
);

-- ============================================================
-- OTP (verification de telephone)
-- ============================================================
CREATE TABLE otp_codes (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    phone       VARCHAR(20) NOT NULL,
    code_hash   VARCHAR(255) NOT NULL,
    purpose     VARCHAR(30) NOT NULL DEFAULT 'REGISTER',
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_codes_phone ON otp_codes(phone);

-- ============================================================
-- VEHICLES
-- ============================================================
CREATE TABLE vehicles (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    brand           VARCHAR(100) NOT NULL,
    model           VARCHAR(100) NOT NULL,
    color           VARCHAR(50),
    plate           VARCHAR(20) NOT NULL,
    seats           INTEGER NOT NULL CHECK (seats BETWEEN 1 AND 8),
    comfort_level   VARCHAR(20) NOT NULL DEFAULT 'BASIC',
    photo_url       VARCHAR(500),
    verified        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_vehicles_owner ON vehicles(owner_id);

-- ============================================================
-- TRIPS
-- ============================================================
CREATE TABLE trips (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    driver_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    vehicle_id          UUID NOT NULL REFERENCES vehicles(id),
    trip_type           VARCHAR(20) NOT NULL,
    origin_label        VARCHAR(255) NOT NULL,
    origin_lat          DOUBLE PRECISION NOT NULL,
    origin_lng          DOUBLE PRECISION NOT NULL,
    origin_point        geography(Point,4326),
    dest_label          VARCHAR(255) NOT NULL,
    dest_lat            DOUBLE PRECISION NOT NULL,
    dest_lng            DOUBLE PRECISION NOT NULL,
    dest_point          geography(Point,4326),
    departure_at        TIMESTAMPTZ NOT NULL,
    seats_total         INTEGER NOT NULL CHECK (seats_total BETWEEN 1 AND 8),
    seats_available      INTEGER NOT NULL CHECK (seats_available >= 0),
    price_per_seat       BIGINT NOT NULL CHECK (price_per_seat >= 0),
    instant_booking      BOOLEAN NOT NULL DEFAULT TRUE,
    luggage_policy       VARCHAR(255),
    description          TEXT,
    status                VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    recurrence_rule       VARCHAR(255),
    parent_trip_id         UUID REFERENCES trips(id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trips_driver ON trips(driver_id);
CREATE INDEX idx_trips_status ON trips(status);
CREATE INDEX idx_trips_departure ON trips(departure_at);
CREATE INDEX idx_trips_type ON trips(trip_type);
CREATE INDEX idx_trips_origin_point ON trips USING GIST(origin_point);
CREATE INDEX idx_trips_dest_point ON trips USING GIST(dest_point);
CREATE INDEX idx_trips_parent ON trips(parent_trip_id);

CREATE OR REPLACE FUNCTION ekuiseo_set_trip_geography() RETURNS trigger AS $$
BEGIN
    NEW.origin_point := ST_SetSRID(ST_MakePoint(NEW.origin_lng, NEW.origin_lat), 4326)::geography;
    NEW.dest_point := ST_SetSRID(ST_MakePoint(NEW.dest_lng, NEW.dest_lat), 4326)::geography;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_trips_geography
    BEFORE INSERT OR UPDATE ON trips
    FOR EACH ROW EXECUTE FUNCTION ekuiseo_set_trip_geography();

-- ============================================================
-- TRIP_STOPS
-- ============================================================
CREATE TABLE trip_stops (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id             UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    position            INTEGER NOT NULL,
    label               VARCHAR(255) NOT NULL,
    lat                 DOUBLE PRECISION NOT NULL,
    lng                 DOUBLE PRECISION NOT NULL,
    point               geography(Point,4326),
    planned_at          TIMESTAMPTZ,
    price_from_origin   BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_trip_stops_trip ON trip_stops(trip_id);

CREATE OR REPLACE FUNCTION ekuiseo_set_stop_geography() RETURNS trigger AS $$
BEGIN
    NEW.point := ST_SetSRID(ST_MakePoint(NEW.lng, NEW.lat), 4326)::geography;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_trip_stops_geography
    BEFORE INSERT OR UPDATE ON trip_stops
    FOR EACH ROW EXECUTE FUNCTION ekuiseo_set_stop_geography();

-- ============================================================
-- BOOKINGS
-- ============================================================
CREATE TABLE bookings (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id         UUID NOT NULL REFERENCES trips(id),
    passenger_id    UUID NOT NULL REFERENCES users(id),
    seats           INTEGER NOT NULL CHECK (seats > 0),
    pickup_stop_id  UUID REFERENCES trip_stops(id),
    dropoff_stop_id UUID REFERENCES trip_stops(id),
    amount          BIGINT NOT NULL,
    service_fee     BIGINT NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT',
    payment_method  VARCHAR(20) NOT NULL DEFAULT 'MOMO',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_bookings_trip_passenger_active ON bookings(trip_id, passenger_id)
    WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED');
CREATE INDEX idx_bookings_trip ON bookings(trip_id);
CREATE INDEX idx_bookings_passenger ON bookings(passenger_id);
CREATE INDEX idx_bookings_status ON bookings(status);

-- ============================================================
-- PAYMENTS
-- ============================================================
CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id      UUID NOT NULL REFERENCES bookings(id),
    provider        VARCHAR(30) NOT NULL DEFAULT 'KKIAPAY',
    provider_tx_id  VARCHAR(255) NOT NULL,
    amount          BIGINT NOT NULL,
    fee             BIGINT NOT NULL DEFAULT 0,
    channel         VARCHAR(20),
    status          VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    raw_payload     JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_provider_tx UNIQUE (provider, provider_tx_id)
);
CREATE INDEX idx_payments_booking ON payments(booking_id);

-- ============================================================
-- DRIVER_PAYOUTS
-- ============================================================
CREATE TABLE driver_payouts (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    driver_id           UUID NOT NULL REFERENCES users(id),
    amount              BIGINT NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at          TIMESTAMPTZ,
    destination_msisdn  VARCHAR(20) NOT NULL
);
CREATE INDEX idx_driver_payouts_driver ON driver_payouts(driver_id);

-- ============================================================
-- REVIEWS
-- ============================================================
CREATE TABLE reviews (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id     UUID NOT NULL REFERENCES trips(id),
    author_id   UUID NOT NULL REFERENCES users(id),
    target_id   UUID NOT NULL REFERENCES users(id),
    role        VARCHAR(20) NOT NULL,
    rating      SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reviews_trip_author_target UNIQUE (trip_id, author_id, target_id)
);
CREATE INDEX idx_reviews_target ON reviews(target_id);

-- ============================================================
-- CONVERSATIONS / MESSAGES
-- ============================================================
CREATE TABLE conversations (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id  UUID NOT NULL REFERENCES bookings(id) UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE messages (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id       UUID NOT NULL REFERENCES users(id),
    body            TEXT NOT NULL,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_conversation ON messages(conversation_id);

-- ============================================================
-- NOTIFICATIONS
-- ============================================================
CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(50) NOT NULL,
    payload     JSONB,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications(user_id);

-- ============================================================
-- SEARCH_ALERTS
-- ============================================================
CREATE TABLE search_alerts (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    origin_label    VARCHAR(255) NOT NULL,
    origin_lat      DOUBLE PRECISION NOT NULL,
    origin_lng      DOUBLE PRECISION NOT NULL,
    dest_label      VARCHAR(255) NOT NULL,
    dest_lat        DOUBLE PRECISION NOT NULL,
    dest_lng        DOUBLE PRECISION NOT NULL,
    date_from       DATE,
    date_to         DATE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_search_alerts_user ON search_alerts(user_id);
