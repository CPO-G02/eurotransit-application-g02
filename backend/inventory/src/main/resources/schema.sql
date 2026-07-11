CREATE TABLE IF NOT EXISTS seats (
    id         BIGSERIAL PRIMARY KEY,
    train_id   VARCHAR(64) NOT NULL,
    seat_class VARCHAR(32) NOT NULL,
    available  INTEGER     NOT NULL CHECK (available >= 0),
    CONSTRAINT uq_seats_train_class UNIQUE (train_id, seat_class)
);

-- Records what each reservation held: the order-failed compensation event only
-- carries reservation_id, not the train_id/seat_class/quantity needed to give
-- the seats back. status (RESERVED -> RELEASED) also makes compensation
-- idempotent under Kafka redelivery.
CREATE TABLE IF NOT EXISTS reservations (
    id             BIGSERIAL   PRIMARY KEY,
    reservation_id VARCHAR(64) NOT NULL UNIQUE,
    order_id       VARCHAR(64) NOT NULL,
    train_id       VARCHAR(64) NOT NULL,
    seat_class     VARCHAR(32) NOT NULL,
    quantity       INTEGER     NOT NULL CHECK (quantity > 0),
    status         VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
