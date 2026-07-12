CREATE TABLE IF NOT EXISTS products (
    train_id    VARCHAR(64) PRIMARY KEY,
    origin      VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    departure   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS seat_classes (
    id         BIGSERIAL PRIMARY KEY,
    train_id   VARCHAR(64)   NOT NULL REFERENCES products (train_id),
    seat_class VARCHAR(32)   NOT NULL,
    price      NUMERIC(10, 2) NOT NULL,
    currency   VARCHAR(3)    NOT NULL,
    available  INTEGER       NOT NULL,
    CONSTRAINT uq_seat_class_per_train UNIQUE (train_id, seat_class)
);
